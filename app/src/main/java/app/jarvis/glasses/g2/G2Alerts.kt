package app.jarvis.glasses.g2

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.jarvis.glasses.conversation.Brain
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.conversation.Memory
import app.jarvis.glasses.conversation.Mouth
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jarvis on Even Realities G2 glasses: an alert shown on the display, and a conversation about it
 * when the wearer taps to reply; or Jarvis's own screen ([G2Menu]) when they ask for it ("open
 * Jarvis"). The answers are shown, not said; the glasses have no speaker. The glasses are held only
 * for this ([G2Link.open]) and given back to the Even app after.
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
    /** Chat's conversation, read again: where the latest alerts are. */
    private val said: suspend () -> List<Said>,
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
            converse(alert)
          } finally {
            link.close()
          }
        }
    return true
  }

  /** Jarvis's own screen on the glasses ([G2Menu]), unless they can't be reached ([canShow]). */
  fun openMenu(): Boolean {
    if (!canShow()) return false
    job =
        scope.launch {
          try {
            if (!link.open()) {
              Log.w(TAG, "the G2 couldn't be signed in to; its menu isn't shown")
              return@launch
            }
            val menu = G2Menu()
            while (true) {
              when (choose(menu)) {
                null, G2Choice.CLOSE -> break
                G2Choice.TALK -> if (!converse(null)) break
                G2Choice.ALERTS -> {
                  screen.show(g2AlertList(runCatching { said() }.getOrDefault(emptyList())))
                  // A tap replies about them; put away, back to the menu.
                  when (ears.awaitReply()) {
                    null -> break
                    true -> if (!converse(null)) break
                    false -> Unit
                  }
                }
              }
            }
          } finally {
            link.close()
          }
        }
    return true
  }

  /** The menu until something is chosen with a tap; null when it is closed or left alone. */
  private suspend fun choose(menu: G2Menu): G2Choice? {
    while (true) {
      screen.plain(menu.text())
      when (withTimeoutOrNull(WAIT_MS) { link.touches.first { it != G2Touch.DOUBLE_TAP } }) {
        null, G2Touch.CLOSED -> return null
        G2Touch.UP -> menu.up()
        G2Touch.DOWN -> menu.down()
        G2Touch.TAP -> return menu.chosen
        G2Touch.DOUBLE_TAP -> Unit
      }
    }
  }

  /**
   * A conversation on the glasses: Jarvis saying [opening] first, or the microphone opening at once
   * (the wearer chose to talk). Ended by Jarvis's side (no follow-ups, a closing word, a failure),
   * what it said stays up, a tap starting another turn. False when it was left unanswered.
   */
  private suspend fun converse(opening: Alert?): Boolean {
    if (opening == null) ears.arm()
    conversation.start(opening = opening)
    conversation.join()
    while (ears.ended == null) {
      conversation.note.value.takeIf { it.isNotEmpty() }?.let { Log.i(TAG, "conversation: $it") }
      when (ears.awaitReply()) {
        true -> Unit
        false -> return true
        null -> return false
      }
      ears.arm()
      conversation.start()
      conversation.join()
    }
    return ears.ended != G2Ears.End.UNANSWERED
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
    /** How long the menu waits for the wearer, as an alert does (G2Ears). */
    const val WAIT_MS = 60_000L
    /** The longest answer asked for: two of the display's pages (G2Pages). */
    const val CHARS = 2 * G2_PAGE_CHARS
  }
}
