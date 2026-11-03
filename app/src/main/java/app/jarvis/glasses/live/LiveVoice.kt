package app.jarvis.glasses.live

import android.content.Context
import android.util.Log
import app.jarvis.glasses.audio.GlassesAudio
import app.jarvis.glasses.chat.Shown
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Live voice: a GPT-Live session over WebRTC, with the glasses as the microphone and speakers, or
 * the phone as a speakerphone without them.
 * The offer goes to Jarvis's `/api/session`, which opens it with OpenAI and hands back the answer,
 * and [LiveRelay] does the rest. Needs `OPENAI_API_KEY` on the Jarvis side; [start] reports
 * failure so the caller can fall back to a push-to-talk conversation.
 *
 * It bills by the minute, so it closes itself after [quietMs] with nobody speaking, and at most
 * [WORKING_MS] into a slow answer, as the web app does (src/app/quiet.ts there). The glasses'
 * microphone hears everyone nearby, so it also closes [UNANSWERED_MS] after Jarvis last spoke or
 * answered, however much is said around it (a TV, people talking). Through the glasses, a Jarvis
 * that knows them is told so, and stays silent unless spoken to ([JarvisClient.liveSession]).
 * Hanging up by itself, it plays Talk's closing tone ([GlassesAudio.closingCue]).
 */
class LiveVoice(
    context: Context,
    private val client: JarvisClient,
    private val audio: GlassesAudio,
    private val scope: CoroutineScope,
    /** How long a quiet session stays open: it bills by the minute. */
    private val quietMs: () -> Long = { QUIET_MS },
) {
  enum class State { OFF, CONNECTING, LIVE }

  private val context = context.applicationContext
  private val _state = MutableStateFlow(State.OFF)
  val state: StateFlow<State> = _state

  private val _note = MutableStateFlow("")
  /** Why the last session couldn't open, if it couldn't. */
  val note: StateFlow<String> = _note

  private val _voices = MutableStateFlow(Voices())
  /** How loud each side is while a session is live, about ten times a second, for the wave. */
  val voices: StateFlow<Voices> = _voices

  private var job: Job? = null
  private var factory: PeerConnectionFactory? = null
  // The open session's `oai-events` channel and relay, for an alert said into it ([tell]).
  @Volatile private var channel: DataChannel? = null
  @Volatile private var openRelay: LiveRelay? = null
  // What to send as the channel opens: with an opening, the event that has Jarvis say the alert. Read on WebRTC's thread.
  @Volatile private var first: String? = null

  /**
   * Opens a session from [history]; [onFail] when it can't be opened ([note] says why). [onSaid]
   * gets each turn said in it as it is finished, [onShown] what Jarvis puts on the screen, and
   * [onEnd] is told once the session has ended and its last turn is handed on. With [opening], it
   * starts by Jarvis saying that alert, then listens for the reply.
   */
  @Synchronized
  fun start(
      history: List<Turn>,
      onFail: () -> Unit,
      onSaid: (Turn) -> Unit,
      opening: Alert? = null,
      onShown: (Shown) -> Unit = {},
      onEnd: () -> Unit = {},
  ): Boolean {
    if (job?.isActive == true) return false
    job = scope.launch {
      try {
        run(history, opening, { why ->
          Log.w(TAG, "could not open: $why")
          _note.value = why
          onFail()
        }, onSaid, onShown)
      } finally {
        onEnd()
      }
    }
    return true
  }

  /** An alert said into the session that is open, as the web app does: false when none is. */
  fun tell(alert: Alert): Boolean {
    val c = channel?.takeIf { it.state() == DataChannel.State.OPEN } ?: return false
    val sent = c.send(DataChannel.Buffer(ByteBuffer.wrap(LiveRelay.say(alert.said).toByteArray()), false))
    if (sent) openRelay?.telling()
    return sent
  }

  fun stop() {
    job?.cancel()
  }

  private suspend fun run(history: List<Turn>, opening: Alert?, onFail: (String) -> Unit, onSaid: (Turn) -> Unit, onShown: (Shown) -> Unit) {
    _state.value = State.CONNECTING
    _note.value = ""
    var pc: PeerConnection? = null
    var track: AudioTrack? = null
    var relay: LiveRelay? = null
    var openedAt = 0L
    // Both stop before the connection is closed: stats asked of a disposed one crash natively.
    var watch: Job? = null
    var meter: Job? = null
    try {
      if (!audio.open()) return onFail("Couldn't open the microphone and speaker")
      val f = factory()
      val gathered = CompletableDeferred<Unit>()
      val ended = CompletableDeferred<Unit>()
      val peer =
          f.createPeerConnection(
              PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN },
              object : PeerObserver() {
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                  if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
                }

                override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                  if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.CLOSED) {
                    ended.complete(Unit)
                  }
                }
              },
          ) ?: return onFail("Live voice couldn't start on this phone")
      pc = peer
      track = f.createAudioTrack("mic", f.createAudioSource(MediaConstraints()))
      peer.addTrack(track, listOf("jarvis"))

      // GPT-Live carries every session event on a channel with this exact label.
      val channel = peer.createDataChannel("oai-events", DataChannel.Init())
      val r = LiveRelay(scope, client::delegate, send = { text ->
        // Its work can outlive the session, and a closed channel takes nothing.
        runCatching { channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false)) }
      }, onSaid = onSaid, onShown = onShown)
      r.seed(history)
      relay = r
      first = null
      channel.registerObserver(
          object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit

            override fun onStateChange() {
              when (channel.state()) {
                DataChannel.State.OPEN -> {
                  Log.i(TAG, "live")
                  this@LiveVoice.channel = channel
                  openRelay = r
                  r.opened()
                  // Not before: GPT-Live drops what is sent into a channel that isn't open yet.
                  first?.let {
                    r.telling()
                    channel.send(DataChannel.Buffer(ByteBuffer.wrap(it.toByteArray()), false))
                  }
                  _state.value = State.LIVE
                }
                DataChannel.State.CLOSED -> {
                  this@LiveVoice.channel = null
                  openRelay = null
                  ended.complete(Unit)
                }
                else -> Unit
              }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
              val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
              r.onMessage(String(bytes))
            }
          },
      )

      val offer = peer.offer()
      peer.setLocal(offer)
      // Everything in one offer: the Worker forwards it once.
      withTimeoutOrNull(3_000) { gathered.await() }
      Log.i(TAG, "offer ready, asking Jarvis for a session")
      val answer = client.liveSession(peer.localDescription.description, history, opening, glasses = audio.onGlasses())
      // Before the answer is set: the channel can open only after it.
      first = answer.first
      peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
      openedAt = System.currentTimeMillis()

      watch = scope.launch {
        while (isActive) {
          delay(1_000)
          val quiet = System.currentTimeMillis() - r.lastActivity
          if ((!r.working && quiet > quietMs()) || quiet > WORKING_MS || r.saidGoodbye() || r.unanswered(UNANSWERED_MS)) ended.complete(Unit)
        }
      }
      meter = scope.launch {
        while (isActive) {
          delay(METER_MS)
          // A report can come back after the session ended: it must not move the next one's wave.
          peer.getStats { report -> if (_state.value != State.OFF) _voices.value = heard(report, _voices.value, r.working) }
        }
      }
      ended.await()
      Log.i(TAG, "ended after ${(System.currentTimeMillis() - openedAt) / 1000} s")
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onFail(e.message ?: "Live voice couldn't start")
    } finally {
      // Stopped (Stop, or the glasses taken off) rather than hung up by itself.
      val stopped = !currentCoroutineContext().isActive
      val wasLive = _state.value == State.LIVE
      watch?.cancel()
      meter?.cancel()
      // Let go first: an alert that comes meanwhile goes to a notification, not into a closing session.
      val open = channel
      channel = null
      openRelay = null
      // Ended on GPT-Live's side at once, not when it notices the connection gone: it bills by the minute.
      open?.let { c ->
        if (runCatching { c.send(DataChannel.Buffer(ByteBuffer.wrap(LiveRelay.CLOSE.toByteArray()), false)) }.getOrDefault(false)) {
          withContext(NonCancellable) { withTimeoutOrNull(300) { while (c.bufferedAmount() > 0) delay(20) } }
        }
      }
      // Only then is Jarvis's work cut short. Hung up by itself (quiet, or a slow answer), the session
      // lets Jarvis finish: what it was asked to do is done, and its answer kept ([LiveRelay.finish]).
      if (stopped) relay?.stop()
      runCatching { pc?.close() }
      runCatching { pc?.dispose() }
      runCatching { track?.dispose() }
      // Hung up by itself after being live: Talk's closing tone, so the wearer knows Jarvis stopped
      // listening. Not when stopped: the glasses came off, or Stop was pressed.
      if (wasLive && !stopped) runCatching { audio.closingCue() }
      audio.close()
      if (openedAt > 0) scope.launch { client.reportLive(((System.currentTimeMillis() - openedAt) / 1000).toInt()) }
      // Before the session reads as off, so a Talk right after it starts from all of it.
      relay?.finish()
      _voices.value = Voices()
      _state.value = State.OFF
    }
  }

  /** The two voices in a stats [report]: the microphone's as sent ("media-source"), Jarvis's as received ("inbound-rtp"). */
  private fun heard(report: RTCStatsReport, before: Voices, thinking: Boolean): Voices {
    var user = 0.0
    var agent = 0.0
    for (s in report.statsMap.values) {
      if (s.members["kind"] != "audio") continue
      val level = (s.members["audioLevel"] as? Number)?.toDouble() ?: continue
      when (s.type) {
        "media-source" -> user = maxOf(user, level)
        "inbound-rtp" -> agent = maxOf(agent, level)
      }
    }
    return Voices(
        VoiceLevel.smooth(before.user, VoiceLevel.peakLoudness(user)),
        VoiceLevel.smooth(before.agent, VoiceLevel.peakLoudness(agent)),
        thinking,
    )
  }

  private fun factory(): PeerConnectionFactory =
      factory
          ?: run {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
            // Hardware echo cancelling matters: the glasses' speakers sit next to their microphones.
            val adm =
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
            PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory().also { factory = it }
          }

  companion object {
    private const val TAG = "LiveVoice"
    const val QUIET_MS = 30_000L
    const val WORKING_MS = 120_000L
    /** How long a session stays open after Jarvis last spoke or answered, whatever it hears meanwhile. */
    const val UNANSWERED_MS = 20_000L
    private const val METER_MS = 100L
  }
}

private suspend fun PeerConnection.offer(): SessionDescription = suspendCancellableCoroutine { c ->
  createOffer(
      object : SdpAdapter() {
        override fun onCreateSuccess(sdp: SessionDescription) = c.resume(sdp)

        override fun onCreateFailure(error: String?) = c.resumeWithException(IllegalStateException(error ?: "no offer"))
      },
      MediaConstraints(),
  )
}

private suspend fun PeerConnection.setLocal(sdp: SessionDescription) = suspendCancellableCoroutine { c ->
  setLocalDescription(
      object : SdpAdapter() {
        override fun onSetSuccess() = c.resume(Unit)

        override fun onSetFailure(error: String?) = c.resumeWithException(IllegalStateException(error ?: "offer refused"))
      },
      sdp,
  )
}

private suspend fun PeerConnection.setRemote(sdp: SessionDescription) = suspendCancellableCoroutine { c ->
  setRemoteDescription(
      object : SdpAdapter() {
        override fun onSetSuccess() = c.resume(Unit)

        override fun onSetFailure(error: String?) = c.resumeWithException(IllegalStateException(error ?: "answer refused"))
      },
      sdp,
  )
}

private open class SdpAdapter : SdpObserver {
  override fun onCreateSuccess(sdp: SessionDescription) = Unit

  override fun onSetSuccess() = Unit

  override fun onCreateFailure(error: String?) = Unit

  override fun onSetFailure(error: String?) = Unit
}

private open class PeerObserver : PeerConnection.Observer {
  override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

  override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

  override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

  override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit

  override fun onIceCandidate(candidate: IceCandidate) = Unit

  override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

  override fun onAddStream(stream: MediaStream) = Unit

  override fun onRemoveStream(stream: MediaStream) = Unit

  override fun onDataChannel(channel: DataChannel) = Unit

  override fun onRenegotiationNeeded() = Unit

  override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
}
