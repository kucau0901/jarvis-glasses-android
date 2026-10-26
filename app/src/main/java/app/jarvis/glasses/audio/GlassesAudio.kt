package app.jarvis.glasses.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import app.jarvis.glasses.conversation.Ears
import app.jarvis.glasses.conversation.Mouth
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The glasses as a Bluetooth headset: their microphone and speakers over HFP (SCO), which any app
 * can use without the Wearables toolkit. While a conversation is open the phone is in
 * communication mode with the glasses as its communication device, so recording and playback both
 * go through them, and the phone's own microphone is never opened (as in Meta's CameraAccess
 * sample, `AudioInputHandler`).
 *
 * Only a headset whose name looks like Meta glasses counts, so a car or earbuds connected at the
 * same time are not mistaken for them.
 */
class GlassesAudio(context: Context) : Ears, Mouth {
  private val context = context.applicationContext
  private val audio = this.context.getSystemService(AudioManager::class.java)
  private var previousMode = AudioManager.MODE_NORMAL
  private var routed = false
  private var tts: TextToSpeech? = null

  private val _connected = MutableStateFlow(false)
  /** Whether the glasses are connected to the phone as a headset right now. */
  val connected: StateFlow<Boolean> = _connected

  init {
    _connected.value = glasses() != null
    audio.registerAudioDeviceCallback(
        object : AudioDeviceCallback() {
          override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            _connected.value = glasses() != null
          }

          override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            _connected.value = glasses() != null
          }
        },
        Handler(Looper.getMainLooper()),
    )
  }

  private fun glasses(): AudioDeviceInfo? =
      runCatching {
            audio.availableCommunicationDevices.firstOrNull {
              it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && GLASSES.containsMatchIn(it.productName.toString())
            }
          }
          .getOrNull()

  override suspend fun open(): Boolean {
    if (routed) return true
    val device = glasses() ?: return false
    previousMode = audio.mode
    routed = true
    audio.mode = AudioManager.MODE_IN_COMMUNICATION
    val ok =
        runCatching { audio.setCommunicationDevice(device) }.getOrDefault(false) &&
            withTimeoutOrNull(3_000) {
              while (audio.communicationDevice?.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) delay(100)
              true
            } == true
    if (!ok) close()
    return ok
  }

  override fun close() {
    if (!routed) return
    runCatching { audio.clearCommunicationDevice() }
    runCatching { audio.mode = previousMode }
    routed = false
  }

  @SuppressLint("MissingPermission") // checked below
  override suspend fun listen(startTimeoutMs: Long): ByteArray? =
      withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
          return@withContext null
        }
        val input =
            audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: return@withContext null
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record =
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 4)
        if (record.state != AudioRecord.STATE_INITIALIZED) {
          record.release()
          return@withContext null
        }
        record.setPreferredDevice(input)
        val gate = SpeechGate(RATE, startTimeoutMs)
        val pcm = ByteArrayOutputStream()
        val buf = ShortArray(RATE / 50) // 20 ms
        val bytes = ByteArray(buf.size * 2)
        var verdict = SpeechGate.Verdict.LISTENING
        var loudest = 0
        try {
          record.startRecording()
          while (isActive && verdict == SpeechGate.Verdict.LISTENING) {
            val n = record.read(buf, 0, buf.size)
            if (n < 0) break
            if (n == 0) continue
            for (i in 0 until n) {
              bytes[2 * i] = (buf[i].toInt() and 0xff).toByte()
              bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte()
              loudest = maxOf(loudest, abs(buf[i].toInt()))
            }
            pcm.write(bytes, 0, n * 2)
            verdict = gate.feed(buf, n)
          }
        } finally {
          runCatching { record.stop() }
          record.release()
          // How a turn ended, and how loud the glasses' microphone got: tells "nobody spoke" from
          // "spoke, but too quietly to count".
          Log.i(TAG, "listen: $verdict, ${pcm.size() / 2 / (RATE / 1000)} ms, loudest $loudest, from ${record.routedDevice?.productName}")
        }
        if (verdict == SpeechGate.Verdict.DONE) Wav.wrap(pcm.toByteArray(), RATE) else null
      }

  override suspend fun cue() {
    val tone = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70)
    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    delay(220)
    tone.release()
  }

  override suspend fun play(audio: ByteArray) {
    val file = withContext(Dispatchers.IO) { File.createTempFile("say", ".mp3", context.cacheDir).apply { writeBytes(audio) } }
    try {
      suspendCancellableCoroutine { cont ->
        val player = MediaPlayer()
        player.setAudioAttributes(SPEECH)
        player.setDataSource(file.path)
        player.setOnCompletionListener {
          it.release()
          if (cont.isActive) cont.resume(Unit)
        }
        player.setOnErrorListener { mp, _, _ ->
          mp.release()
          if (cont.isActive) cont.resume(Unit)
          true
        }
        cont.invokeOnCancellation { runCatching { player.release() } }
        player.prepare()
        player.start()
      }
    } finally {
      file.delete()
    }
  }

  override suspend fun say(text: String) {
    val engine = speaker() ?: return
    suspendCancellableCoroutine { cont ->
      val id = "say-${System.nanoTime()}"
      engine.setOnUtteranceProgressListener(
          object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
              if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
              if (utteranceId == id && cont.isActive) cont.resume(Unit)
            }
          },
      )
      cont.invokeOnCancellation { engine.stop() }
      engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }
  }

  private suspend fun speaker(): TextToSpeech? {
    tts?.let { return it }
    return suspendCancellableCoroutine { cont ->
      var engine: TextToSpeech? = null
      engine =
          TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
              engine?.setAudioAttributes(SPEECH)
              tts = engine
              cont.resume(engine)
            } else {
              cont.resume(null)
            }
          }
    }
  }

  companion object {
    /** Speech recognition wants 16 kHz; HFP carries 8 or 16 and Android converts. */
    const val RATE = 16_000
    private const val TAG = "GlassesAudio"
    private val GLASSES = Regex("ray-?ban|meta|oakley", RegexOption.IGNORE_CASE)
    private val SPEECH =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
  }
}
