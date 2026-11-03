package app.jarvis.glasses.g2

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import app.jarvis.glasses.conversation.Brain
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.conversation.Memory
import app.jarvis.glasses.conversation.Mouth
import app.jarvis.glasses.conversation.Phrases
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Jarvis on Even Realities G2 glasses: an alert shown in Jarvis's chat on the display ([G2Chat]),
 * and a conversation about it when the wearer taps to reply; or Jarvis's own screen ([G2Menu])
 * when they ask for it ("open Jarvis"). The answers are shown, not said; the glasses have no
 * speaker. The glasses are held only for this ([G2Link.open]) and given back to the Even app after.
 *
 * The conversation is Chat's ([memory], [said]), as it is in Ray-Ban glasses, so "yes, do that" is
 * understood, what was said on the person's other screens can be scrolled back to, and what is
 * said here is in Chat after.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Alerts(
    private val scope: CoroutineScope,
    private val link: G2Link,
    client: JarvisClient,
    memory: Memory,
    followUps: () -> Boolean,
    /** Chat's conversation, as it changes. */
    private val said: StateFlow<List<Said>>,
    /** Chat's conversation read again from Jarvis: what the person's other screens said. */
    private val refresh: suspend () -> Unit,
) {
  private val chat = G2Chat(link::show)
  private val ears = G2Ears(link, chat)
  private val conversation = Conversation(scope, ears, Lens(chat), { null }, ShownBrain(client, chat), memory, followUps)
  private var job: Job? = null
  /** The menu while it is on the glasses: an alert arriving is counted on it. */
  private var menu: G2Menu? = null

  /** Whether G2 glasses are with the phone and free to show something now. */
  fun canShow(): Boolean = job?.isActive != true && link.reachable()

  /**
   * Shows [alert] on the glasses, unless they can't be reached ([canShow]); [unseen] is called with
   * it if they then can't be signed in to after all.
   */
  fun show(alert: Alert, unseen: (Alert) -> Unit): Boolean =
      session({
        Log.w(TAG, "the G2 couldn't be signed in to; the alert goes to a notification")
        unseen(alert)
      }) {
        chat.open()
        converse(alert)
      }

  /** Jarvis's own screen on the glasses ([G2Menu]), unless they can't be reached ([canShow]). */
  fun openMenu(): Boolean =
      session({ Log.w(TAG, "the G2 couldn't be signed in to; its menu isn't shown") }) {
        val menu = G2Menu()
        // The battery as the glasses tell it, a moment after signing in: on the menu as it comes.
        val battery = launch {
          link.glasses.collect { g ->
            menu.battery = g.firstOrNull()?.battery
            if (this@G2Alerts.menu === menu) link.show(menu.page())
          }
        }
        while (true) {
          this@G2Alerts.menu = menu
          val choice = choose(menu)
          this@G2Alerts.menu = null
          when (choice) {
            null, G2Choice.CLOSE -> break
            G2Choice.CHAT -> {
              chat.open(leave = BACK)
              if (!converse(null)) break
            }
            G2Choice.ALERTS -> {
              menu.unseen = 0
              chat.open(only = { it.g2Alert }, leave = BACK)
              // A tap replies, in the whole chat; put away, back to the menu.
              when (ears.awaitReply()) {
                null -> break
                true -> {
                  chat.open(leave = BACK)
                  if (!converse(null, tapped = true)) break
                }
                false -> Unit
              }
            }
          }
          chat.hide()
        }
        battery.cancel()
      }

  /**
   * An alert arriving while Jarvis is on the glasses: kept there rather than sent as a notification,
   * which the Even app would put on the glasses over Jarvis's page. Chat has it already, so the chat
   * shows it as it comes; the menu counts it. False when Jarvis isn't on them.
   */
  fun take(alert: Alert): Boolean {
    if (job?.isActive != true) return false
    scope.launch {
      menu?.let {
        it.unseen++
        link.show(it.page())
      } ?: chat.notice("New: ${alert.title}")
    }
    return true
  }

  /**
   * The glasses held for [body], with the chat following Chat's conversation, then given back;
   * [failed] when they can't be signed in to. False when they can't be reached ([canShow]).
   */
  private fun session(failed: () -> Unit, body: suspend CoroutineScope.() -> Unit): Boolean {
    if (!canShow()) return false
    job =
        scope.launch {
          try {
            if (!link.open()) return@launch failed()
            // Not this session's child: it may take a while, and the glasses are free before.
            scope.launch { runCatching { refresh() } }
            val following = launch { said.collect { chat.history(it) } }
            try {
              body()
            } finally {
              following.cancel()
              chat.hide()
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
      link.show(menu.page())
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
   * A conversation in the chat: Jarvis saying [opening] first, then the microphone opening when the
   * wearer taps, or at once when they [tapped] already. Ended by Jarvis's side (no follow-ups, a
   * closing word, a failure) or a swipe down while listening, the chat stays, a tap starting another
   * turn. False when it was left unanswered.
   */
  private suspend fun converse(opening: Alert?, tapped: Boolean = false): Boolean {
    if (tapped) ears.arm()
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

  /** Text in the chat for everything Jarvis says. */
  private class Lens(private val chat: G2Chat) : Mouth {
    // The glasses were opened for the alert (show) and are closed after it.
    override suspend fun open() = true

    override fun close() = Unit

    // Listening is shown once the wearer taps (G2Ears).
    override suspend fun cue() = Unit

    // The chat stays up, and a tap talks again (converse).
    override suspend fun closingCue() = Unit

    // No speaker: answers are asked for as text (ShownBrain), so there is nothing to play.
    override suspend fun play(audio: ByteArray) = Unit

    override suspend fun say(text: String) = chat.said(text)
  }

  /** Jarvis's answers written for the display, as text; the question and what Jarvis is doing shown in the chat meanwhile. */
  private class ShownBrain(private val client: JarvisClient, private val chat: G2Chat) : Brain {
    // How much was heard in the log, never the words: the microphone hears whoever is nearby. The
    // wearer sees them, unless they only end or restart the conversation ("thanks", "okay").
    override suspend fun transcribe(wav: ByteArray) =
        client.transcribe(wav).also {
          Log.i(TAG, "heard: ${it.length} characters")
          if (it.isNotBlank() && !Phrases.isStop(it) && !Phrases.isAck(it) && !Phrases.isLaunchPhrase(it)) chat.asked(it)
        }

    // Not [openMic]: with chars Jarvis takes each question as heard by an open microphone.
    override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, openMic: Boolean, onEvent: suspend (VoiceEvent) -> Unit) =
        client
            .ask(text, context, photoJpeg, CHARS) { e ->
              if (e is VoiceEvent.Progress) chat.progress(e.text)
              onEvent(e)
            }
            .also { Log.i(TAG, "answer: ${it.text.length} characters, ok ${it.ok}, not for Jarvis ${it.notForMe}") }

    override suspend fun speech(text: String): ByteArray? = null
  }

  companion object {
    private const val TAG = "G2"
    /** How long the menu waits for the wearer, as an alert does (G2Ears). */
    const val WAIT_MS = 60_000L
    /** The longest answer asked for: about four screens of the chat, read by scrolling. */
    const val CHARS = 1_200
    /** What leaving the chat does when the menu opened it. */
    const val BACK = "go back"
  }
}
