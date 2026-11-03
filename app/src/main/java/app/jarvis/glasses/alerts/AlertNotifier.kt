package app.jarvis.glasses.alerts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import app.jarvis.glasses.MainActivity
import app.jarvis.glasses.R
import app.jarvis.glasses.chat.Markdown
import app.jarvis.glasses.chat.ChatLog
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.jarvis
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Jarvis speaking first, on the phone, and so on the watch, which shows the phone's notifications.
 * Every alert becomes one, as the web app's are, whether or not the glasses also had it: an alert
 * already said aloud in them comes quietly, on a channel of its own ([show]'s `said`), the rest as
 * a message would.
 *
 * Jarvis's own alert is a conversation with Reply: a reply goes to Jarvis as a typed question with
 * the alert before it, and the answer joins the same notification, which can be answered again.
 * A family message ([FromFamily]) is its conversation's notification instead, under the sender's
 * name, the room's and each direct one's apart, and Reply answers them there.
 *
 * [show] tells the caller whether the notification can be seen at all: when Android blocks this
 * app's notifications, the alert is not acknowledged and Jarvis tries its other ways instead.
 */
class AlertNotifier(
    private val context: Context,
    private val client: JarvisClient,
    private val hub: Hub,
    private val chat: ChatLog,
    private val scope: CoroutineScope,
) {
  private val threads = Threads()
  private val messages = Messages()
  private val nm = context.getSystemService(NotificationManager::class.java)
  private val you = Person.Builder().setName("You").build()
  private val jarvis = Person.Builder().setName("Jarvis").setBot(true).build()

  init {
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Jarvis's alerts", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "What Jarvis tells you first: reminders, routines, home events, job results. Reply to answer Jarvis."
        },
    )
    nm.createNotificationChannel(
        NotificationChannel(FAMILY, "Family messages", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "Messages in the family room and to you alone. Reply to answer them there."
        },
    )
    nm.createNotificationChannel(
        NotificationChannel(HEARD, "Said in the glasses", NotificationManager.IMPORTANCE_LOW).apply {
          description = "Alerts and messages the glasses said aloud already, kept here without a sound."
        },
    )
  }

  /** Whether Android shows this app's alerts: notifications allowed, and Jarvis's or the family's channel not switched off. */
  fun canShow(): Boolean = canShow(CHANNEL) || canShow(FAMILY)

  private fun canShow(channel: String): Boolean = nm.areNotificationsEnabled() && nm.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE

  /** [alert] as a notification; [said]: the glasses said it already, so it comes without a sound. */
  fun show(alert: Alert, said: Boolean = false): Boolean {
    val family = FromFamily.of(alert)
    if (family != null) {
      if (!canShow(if (said) HEARD else FAMILY)) return false
      postFamily(family.convo, family.room, messages.add(family.convo, Messages.Line(family.sender, alert.text, System.currentTimeMillis())), quiet = false, heard = said)
      return true
    }
    if (!canShow(if (said) HEARD else CHANNEL)) return false
    post(alert.id, alert.title, threads.open(alert), threads.shown(alert.id, alert.text), working = false, quiet = false, heard = said)
    return true
  }

  /** The person replied under [id]'s notification: ask Jarvis, then show the answer there; [done] once it has. */
  fun reply(id: String, title: String, alertText: String, text: String, done: () -> Unit = {}) {
    val asked = threads.add(id, Turn.user(text), alertText)
    val first = threads.shown(id, alertText)
    post(id, title, asked, first, working = true, quiet = true)
    scope.launch {
      try {
        var answer = ""
        var ok = false
        runCatching {
              client.chat(asked, null) { ev ->
                val type = (ev["type"] as? JsonPrimitive)?.contentOrNull
                val said = (ev["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (type == "result") answer = said.also { ok = true } else if (type == "error") answer = said
              }
            }
            .onFailure { answer = "Couldn't reach Jarvis: ${it.message}" }
        if (answer.isEmpty()) answer = "That ended without an answer. Try again."
        post(id, title, threads.add(id, Turn.jarvis(answer), alertText), first, working = false, quiet = true)
        // In Chat's conversation too, so Chat and the web app show it; what went wrong is not kept.
        if (ok) {
          val now = System.currentTimeMillis()
          chat.keep(listOf(Said(Said.newId(), now, "user", text), Said(Said.newId(), now, "assistant", answer)))
        }
      } finally {
        done()
      }
    }
  }

  /** The person replied under [convo]'s notification: sent to that conversation, and shown there as theirs; [done] once it has. */
  fun replyTo(convo: String, room: Boolean, text: String, done: () -> Unit = {}) {
    postFamily(convo, room, messages.add(convo, Messages.Line(null, text, System.currentTimeMillis())), quiet = true, sending = true)
    scope.launch {
      try {
        val failed = runCatching { hub.send(convo, text) }.exceptionOrNull()
        val lines = if (failed == null) messages.of(convo) else messages.add(convo, Messages.Line(NOT_SENT, failed.message ?: "Try again in the app", System.currentTimeMillis()))
        // Not when it was read in the app meanwhile: its notification is gone, and stays gone.
        if (lines.isNotEmpty()) postFamily(convo, room, lines, quiet = true)
      } finally {
        done()
      }
    }
  }

  /** [convo] read in the app: its notification goes. */
  fun read(convo: String) {
    messages.clear(convo)
    nm.cancel(familyId(convo))
  }

  /** [first]: how the alert itself reads, its words as they are, where the model is given them quoted ([Threads]). */
  private fun post(id: String, title: String, turns: List<Turn>, first: String, working: Boolean, quiet: Boolean, heard: Boolean = false) {
    val style = Notification.MessagingStyle(you).setConversationTitle(title)
    val now = System.currentTimeMillis()
    turns.forEachIndexed { i, t -> style.addMessage(Markdown.plain(if (i == 0) first else t.text), now, if (t.role == "user") null else jarvis) }
    if (working) style.addMessage("Thinking…", now, jarvis)

    val back =
        Intent(context, ReplyReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_ALERT, turns.first().text)
    val n =
        builder(if (heard) HEARD else CHANNEL, style, open(MainActivity.OPEN_CHAT, null, id.hashCode()), quiet)
            .apply { if (!working) addAction(replyAction(back, id.hashCode(), "Reply to Jarvis")) }
            .build()
    nm.notify(id.hashCode(), n)
  }

  private fun postFamily(convo: String, room: Boolean, lines: List<Messages.Line>, quiet: Boolean, sending: Boolean = false, heard: Boolean = false) {
    val style = Notification.MessagingStyle(you).setConversationTitle(if (room) "Family" else null).setGroupConversation(room)
    for (l in lines) style.addMessage(l.text, l.at, l.from?.let { Person.Builder().setName(it).build() })
    val id = familyId(convo)
    val back = Intent(context, ReplyReceiver::class.java).putExtra(EXTRA_CONVO, convo).putExtra(EXTRA_ROOM, room)
    val n =
        builder(if (heard) HEARD else FAMILY, style, open(MainActivity.OPEN_INBOX, convo, id), quiet)
            .apply { if (!sending) addAction(replyAction(back, id, if (room) "Reply to the family" else "Reply")) }
            .build()
    nm.notify(id, n)
  }

  private fun builder(channel: String, style: Notification.MessagingStyle, open: PendingIntent, quiet: Boolean): Notification.Builder =
      Notification.Builder(context, channel)
          .setSmallIcon(R.drawable.ic_stat_jarvis)
          .setStyle(style)
          .setContentIntent(open)
          .setCategory(Notification.CATEGORY_MESSAGE)
          .setOnlyAlertOnce(quiet)
          .setAutoCancel(true)

  /** Opens the app at [tab], and in the Inbox, at [convo]. Each notification its own, by [code]. */
  private fun open(tab: String, convo: String?, code: Int): PendingIntent =
      PendingIntent.getActivity(
          context,
          code,
          Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.OPEN, tab).putExtra(MainActivity.OPEN_CONVO, convo),
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

  private fun replyAction(back: Intent, code: Int, label: String): Notification.Action {
    // Mutable, as a reply's words are filled in by the system; the intent names its receiver.
    val replyTo = PendingIntent.getBroadcast(context, code, back, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", replyTo)
        .addRemoteInput(RemoteInput.Builder(KEY).setLabel(label).build())
        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
        .build()
  }

  private fun familyId(convo: String) = ("convo:$convo").hashCode()

  companion object {
    private const val CHANNEL = "alerts"
    private const val FAMILY = "family"
    /** Quiet: what the glasses said aloud already. */
    private const val HEARD = "heard"
    /** Who a reply that couldn't be sent is shown as said by. */
    private const val NOT_SENT = "Not sent"
    const val KEY = "reply"
    const val EXTRA_ID = "alert"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ALERT = "alertText"
    const val EXTRA_CONVO = "convo"
    const val EXTRA_ROOM = "room"
  }
}

/**
 * Reply, under an alert's notification or a family conversation's. The app is kept up until it is
 * sent or answered (goAsync), for at most [KEEP_MS]: past that Android takes the receiver for stuck.
 */
class ReplyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AlertNotifier.KEY)?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return
    val app = context.jarvis
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    val done = { if (finished.compareAndSet(false, true)) pending.finish() }
    app.scope.launch {
      delay(KEEP_MS)
      done()
    }
    val convo = intent.getStringExtra(AlertNotifier.EXTRA_CONVO)
    if (convo != null) return app.notifier.replyTo(convo, intent.getBooleanExtra(AlertNotifier.EXTRA_ROOM, false), text, done)
    val id = intent.getStringExtra(AlertNotifier.EXTRA_ID) ?: return done()
    app.notifier.reply(id, intent.getStringExtra(AlertNotifier.EXTRA_TITLE) ?: "Jarvis", intent.getStringExtra(AlertNotifier.EXTRA_ALERT).orEmpty(), text, done)
  }

  private companion object {
    const val KEEP_MS = 50_000L
  }
}
