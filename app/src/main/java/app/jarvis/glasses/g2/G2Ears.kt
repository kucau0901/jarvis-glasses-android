package app.jarvis.glasses.g2

import android.os.Build
import androidx.annotation.RequiresApi
import app.jarvis.glasses.audio.Lc3Decoder
import app.jarvis.glasses.audio.SpeechGate
import app.jarvis.glasses.audio.Wav
import app.jarvis.glasses.conversation.Ears
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The G2's microphone, opened only when the wearer taps (the touchpad or the ring) or swipes up:
 * never by itself, since nothing tells whether the glasses are being worn. Until then the page
 * stays as it is, an alert or an answer, for [waitMs]; a swipe down puts it away.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Ears(private val link: G2Link, private val waitMs: Long = 60_000) : Ears {
  enum class End { DISMISSED, UNANSWERED }

  /** Why the last turn heard nothing because of the wearer, or null when it didn't. */
  @Volatile var ended: End? = null
    private set

  /** The microphone sent nothing at all last time. */
  private var silent = false

  override suspend fun listen(startTimeoutMs: Long): ByteArray? {
    ended = null
    // Not a double tap: the glasses take that for their own menu.
    val touch = withTimeoutOrNull(waitMs) { link.touches.first { it != G2Touch.DOUBLE_TAP } }
    when (touch) {
      null -> return null.also { ended = End.UNANSWERED }
      G2Touch.DOWN, G2Touch.CLOSED -> return null.also { ended = End.DISMISSED }
      G2Touch.TAP, G2Touch.UP, G2Touch.DOUBLE_TAP -> Unit
    }
    link.show(LISTENING)
    val wav = record(startTimeoutMs)
    if (wav != null) link.show(THINKING) else if (ended == null) link.show(if (silent) NO_MIC else NOTHING_HEARD)
    return wav
  }

  /** One utterance as WAV, or null when nobody spoke within [startTimeoutMs] or it was put away. */
  private suspend fun record(startTimeoutMs: Long): ByteArray? = coroutineScope {
    silent = false
    val packets = link.micOn()
    // A swipe down or the page closing stops listening; nothing is sent.
    val watch = launch {
      link.touches.first { it == G2Touch.DOWN || it == G2Touch.CLOSED }
      ended = End.DISMISSED
      packets.cancel()
    }
    val gate = SpeechGate(RATE, startTimeoutMs)
    val pcm = ByteArrayOutputStream()
    var verdict = SpeechGate.Verdict.LISTENING
    var received = 0
    var loudest = 0
    try {
      withContext(Dispatchers.Default) {
        Lc3Decoder(rate = RATE).use { lc3 ->
          // The gate counts time by the sound it is given: when the glasses send none, this ends it.
          withTimeoutOrNull(startTimeoutMs + 50_000) {
            while (true) {
              val packet = withTimeoutOrNull(if (received == 0) FIRST_SOUND_MS else 2_000) { packets.receiveCatching().getOrNull() } ?: break
              received++
              val samples = lc3.decode(packet, G2_LC3_FRAME_BYTES)
              for (s in samples) {
                loudest = maxOf(loudest, abs(s.toInt()))
                pcm.write(s.toInt() and 0xff)
                pcm.write(s.toInt() shr 8 and 0xff)
              }
              verdict = gate.feed(samples)
              if (verdict != SpeechGate.Verdict.LISTENING) break
            }
          }
        }
      }
    } finally {
      link.micOff()
      watch.cancel()
      Log.i(TAG, "heard: $verdict after ${received * 50} ms of sound, loudest $loudest")
    }
    silent = received == 0
    if (verdict == SpeechGate.Verdict.DONE) Wav.wrap(pcm.toByteArray(), RATE) else null
  }

  companion object {
    const val RATE = 16_000
    // Plain ASCII: the glasses' font may not have an ellipsis.
    const val LISTENING = "Listening..."
    const val THINKING = "..."
    const val NOTHING_HEARD = "Nothing heard."
    const val NO_MIC = "The glasses' microphone didn't start."
    /** How long the microphone may take to start sending. */
    const val FIRST_SOUND_MS = 3_000L
    private const val TAG = "G2"
  }
}
