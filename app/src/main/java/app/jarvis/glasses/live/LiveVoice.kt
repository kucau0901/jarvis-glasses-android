package app.jarvis.glasses.live

import android.content.Context
import app.jarvis.glasses.audio.GlassesAudio
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Live voice: a GPT-Live session over WebRTC, with the glasses as the microphone and speakers.
 * The offer goes to Jarvis's `/api/session`, which opens it with OpenAI and hands back the answer,
 * and [LiveRelay] does the rest. Needs `OPENAI_API_KEY` on the Jarvis side; [start] reports
 * failure so the caller can fall back to a push-to-talk conversation.
 *
 * It bills by the minute, so it closes itself after [QUIET_MS] with nobody speaking, and at most
 * [WORKING_MS] into a slow answer, as the web app does (src/app/quiet.ts there).
 */
class LiveVoice(
    context: Context,
    private val client: JarvisClient,
    private val audio: GlassesAudio,
    private val scope: CoroutineScope,
) {
  enum class State { OFF, CONNECTING, LIVE }

  private val context = context.applicationContext
  private val _state = MutableStateFlow(State.OFF)
  val state: StateFlow<State> = _state

  private val _note = MutableStateFlow("")
  /** Why the last session couldn't open, if it couldn't. */
  val note: StateFlow<String> = _note

  private val _turns = MutableStateFlow<List<Turn>>(emptyList())
  /** What was said in the session, kept for the push-to-talk conversation that may follow. */
  val turns: StateFlow<List<Turn>> = _turns

  private var job: Job? = null
  private var factory: PeerConnectionFactory? = null

  /** Opens a session; [onFail] when it can't be opened ([note] says why). [onEnd] gets what was said. */
  @Synchronized
  fun start(history: List<Turn>, onFail: () -> Unit, onEnd: (List<Turn>) -> Unit): Boolean {
    if (job?.isActive == true) return false
    job = scope.launch { run(history, { why -> _note.value = why; onFail() }, onEnd) }
    return true
  }

  fun stop() {
    job?.cancel()
  }

  private suspend fun run(history: List<Turn>, onFail: (String) -> Unit, onEnd: (List<Turn>) -> Unit) {
    _state.value = State.CONNECTING
    _note.value = ""
    _turns.value = history
    var pc: PeerConnection? = null
    var track: AudioTrack? = null
    var relay: LiveRelay? = null
    var openedAt = 0L
    try {
      if (!audio.open()) return onFail("The glasses aren't connected as a headset")
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
        channel.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray()), false))
      })
      r.seed(history)
      relay = r
      channel.registerObserver(
          object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) = Unit

            override fun onStateChange() {
              when (channel.state()) {
                DataChannel.State.OPEN -> _state.value = State.LIVE
                DataChannel.State.CLOSED -> ended.complete(Unit)
                else -> Unit
              }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
              val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
              r.onMessage(String(bytes))
              _turns.value = r.turns.value
            }
          },
      )

      val offer = peer.offer()
      peer.setLocal(offer)
      // Everything in one offer: the Worker forwards it once.
      withTimeoutOrNull(3_000) { gathered.await() }
      val answer = client.liveSession(peer.localDescription.description, history)
      peer.setRemote(SessionDescription(SessionDescription.Type.ANSWER, answer))
      openedAt = System.currentTimeMillis()

      val watch = scope.launch {
        while (isActive) {
          delay(1_000)
          val quiet = System.currentTimeMillis() - r.lastActivity
          if ((!r.working && quiet > QUIET_MS) || quiet > WORKING_MS) ended.complete(Unit)
        }
      }
      ended.await()
      watch.cancel()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onFail(e.message ?: "Live voice couldn't start")
    } finally {
      relay?.stop()
      runCatching { pc?.close() }
      runCatching { pc?.dispose() }
      runCatching { track?.dispose() }
      audio.close()
      if (openedAt > 0) scope.launch { client.reportLive(((System.currentTimeMillis() - openedAt) / 1000).toInt()) }
      _state.value = State.OFF
      onEnd(_turns.value)
    }
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
    const val QUIET_MS = 30_000L
    const val WORKING_MS = 120_000L
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
