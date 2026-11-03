package app.jarvis.glasses.g2

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.jarvis.glasses.audio.Lc3Decoder
import app.jarvis.glasses.audio.SpeechGate
import app.jarvis.glasses.audio.Wav
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.conversation.Ears
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The G2's microphone, opened only when the wearer taps (the touchpad or the ring): never by
 * itself, since nothing tells whether the glasses are being worn. Until then the [chat] stays for
 * [waitMs], scrolled with a swipe; two swipes down at its newest line leave it. While listening, a
 * swipe down stops, and the chat stays.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Ears(private val link: G2Link, private val chat: G2Chat, private val waitMs: Long = 60_000) : Ears {
  enum class End { DISMISSED, UNANSWERED }

  /** Why the last turn heard nothing because of the wearer, or null when it didn't. */
  @Volatile var ended: End? = null
    private set

  /** The microphone sent nothing at all last time. */
  private var silent = false

  /** Speech was still going at the cap last time, so nothing was sent. */
  private var tooLong = false

  /** The wearer stopped the last listening with a swipe down. */
  @Volatile private var stopped = false

  /** The wearer tapped already: the next [listen] opens the microphone at once. */
  @Volatile private var armed = false

  fun arm() {
    armed = true
  }

  override suspend fun listen(startTimeoutMs: Long, maxMs: Long): ByteArray? {
    ended = null
    if (!armed) {
      when (awaitReply()) {
        null -> return null.also { ended = End.UNANSWERED }
        false -> return null.also { ended = End.DISMISSED }
        true -> Unit
      }
    }
    armed = false
    chat.listening()
    // A tap opens it every time, so never a follow-up's shorter cap, which is for a microphone that opens by itself.
    val wav = record(startTimeoutMs, maxOf(maxMs, Conversation.maxMs))
    when {
      wav != null -> chat.thinking()
      ended != null || stopped -> chat.idle()
      else -> chat.idle(if (silent) NO_MIC else if (tooLong) TOO_LONG else NOTHING_HEARD)
    }
    return wav
  }

  /**
   * Waits for the wearer, scrolling the chat as they swipe: true for a tap (talk), false when they
   * leave it, null when nothing came for [waitMs]. Not a double tap: the glasses take that for
   * their own menu.
   */
  suspend fun awaitReply(): Boolean? {
    while (true) {
      when (withTimeoutOrNull(waitMs) { link.touches.first { it != G2Touch.DOUBLE_TAP } }) {
        null -> return null
        G2Touch.TAP -> return true
        G2Touch.DOWN -> if (!chat.down()) return false
        G2Touch.UP -> chat.up()
        G2Touch.CLOSED -> return false
        G2Touch.DOUBLE_TAP -> Unit
      }
    }
  }

  /**
   * One utterance as WAV, or null when nobody spoke within [startTimeoutMs], speech was still going
   * at [maxMs], or it was stopped or put away.
   */
  private suspend fun record(startTimeoutMs: Long, maxMs: Long): ByteArray? = coroutineScope {
    silent = false
    tooLong = false
    stopped = false
    val packets = AtomicReference(link.micOn())
    // A swipe down stops listening, the page closing ends it; nothing is sent either way.
    val watch = launch {
      if (link.touches.first { it == G2Touch.DOWN || it == G2Touch.CLOSED } == G2Touch.CLOSED) ended = End.DISMISSED else stopped = true
      packets.get().cancel()
    }
    var again = false
    val gate = SpeechGate(RATE, startTimeoutMs, maxMs = maxMs)
    val pcm = ByteArrayOutputStream()
    var verdict = SpeechGate.Verdict.LISTENING
    var received = 0
    var loudest = 0
    try {
      withContext(Dispatchers.Default) {
        Lc3Decoder(rate = RATE).use { lc3 ->
          // The gate counts time by the sound it is given: when the glasses send none, this ends it.
          withTimeoutOrNull(maxOf(startTimeoutMs, maxMs) + 10_000) {
            while (true) {
              val packet = withTimeoutOrNull(if (received == 0) FIRST_SOUND_MS else 2_000) { packets.get().receiveCatching().getOrNull() }
              if (packet == null) {
                // The microphone didn't start: once more, off and on, before saying so.
                if (received == 0 && !again && ended == null && !stopped) {
                  again = true
                  Log.i(TAG, "no sound from the mic: starting it again")
                  packets.set(link.micOn())
                  continue
                }
                break
              }
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
    tooLong = verdict == SpeechGate.Verdict.TOO_LONG
    if (verdict == SpeechGate.Verdict.DONE) Wav.wrap(pcm.toByteArray(), RATE) else null
  }

  companion object {
    const val RATE = 16_000
    // In the chat's top line, after "JARVIS  |  ".
    const val NOTHING_HEARD = "Nothing heard"
    const val NO_MIC = "The mic didn't start"
    const val TOO_LONG = "Too long, so not sent"
    /** How long the microphone may take to start sending. */
    const val FIRST_SOUND_MS = 3_000L
    private const val TAG = "G2"
  }
}
