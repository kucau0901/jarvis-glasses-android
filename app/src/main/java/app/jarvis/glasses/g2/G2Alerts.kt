package app.jarvis.glasses.g2

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.jarvis.glasses.conversation.Brain
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.conversation.Memory
import app.jarvis.glasses.conversation.Mouth
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Jarvis speaking first on Even Realities G2 glasses: an alert shown on the display, and a
 * conversation about it when the wearer taps (or swipes up) to reply. The answers are shown, not
 * said; the glasses have no speaker. The glasses are held only for this ([G2Link.open]) and given
 * back to the Even app after.
 *
 * The conversation is Chat's ([memory]), as it is in Ray-Ban glasses, so "yes, do that" is
 * understood, and what is said here is in Chat after.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Alerts(
    private val scope: CoroutineScope,
    private val link: G2Link,
    client: JarvisClient,
    memory: Memory,
    followUps: () -> Boolean,
) {
  private val screen = G2Screen(link::show)
  private val ears = G2Ears(link, screen)
  private val conversation = Conversation(scope, ears, Lens(screen), { null }, ShownBrain(client), memory, followUps)
  private var job: Job? = null

  /** Whether G2 glasses are with the phone and free to show something now. */
  fun canShow(): Boolean = job?.isActive != true && link.reachable()

  /**
   * Shows [alert] on the glasses, unless they can't be reached ([canShow]); [unseen] is called with
   * it if they then can't be signed in to after all.
   */
  fun show(alert: Alert, unseen: (Alert) -> Unit): Boolean {
    if (!canShow()) return false
    job =
        scope.launch {
          try {
            if (!link.open()) {
              Log.w(TAG, "the G2 couldn't be signed in to; the alert goes to a notification")
              unseen(alert)
              return@launch
            }
            conversation.start(opening = alert)
            conversation.join()
            // Ended by Jarvis's side (no follow-ups, a closing word, a failure): what it said stays
            // up, a tap starting another turn, until the wearer puts it away or leaves it.
            while (ears.ended == null) {
              conversation.note.value.takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "conversation: $it") }
              if (ears.awaitReply() != true) break
              ears.arm()
              conversation.start()
              conversation.join()
            }
          } finally {
            link.close()
          }
        }
    return true
  }

  /** Text on the glasses for everything Jarvis says, a page at a time. */
  private class Lens(private val screen: G2Screen) : Mouth {
    // The glasses were opened for the alert (show) and are closed after it.
    override suspend fun open() = true

    override fun close() = Unit

    // Listening is shown once the wearer taps (G2Ears).
    override suspend fun cue() = Unit

    // No speaker: answers are asked for as text (ShownBrain), so there is nothing to play.
    override suspend fun play(audio: ByteArray) = Unit

    override suspend fun say(text: String) = screen.show(text)
  }

  /** Jarvis's answers written for the display, as text. */
  private class ShownBrain(private val client: JarvisClient) : Brain {
    override suspend fun transcribe(wav: ByteArray) = client.transcribe(wav).also { Log.i(TAG, "heard: \"$it\"") }

    override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit) =
        client.ask(text, context, photoJpeg, CHARS, onEvent).also { Log.i(TAG, "answer: ${it.text.length} characters, ok ${it.ok}") }

    override suspend fun speech(text: String): ByteArray? = null
  }

  companion object {
    private const val TAG = "G2"
    /** The longest answer asked for: two of the display's pages (G2Pages). */
    const val CHARS = 2 * G2_PAGE_CHARS
  }
}
