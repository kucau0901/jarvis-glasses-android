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
import app.jarvis.glasses.chat.Markdown
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.jarvis
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Jarvis speaking first, on the phone: an alert the glasses don't say becomes a notification, a
 * conversation with Reply. A reply goes to Jarvis as a typed question with the alert before it, and
 * the answer joins the same notification, which can be answered again.
 *
 * [show] tells the caller whether the notification can be seen at all: when Android blocks this
 * app's notifications, the alert is not acknowledged and Jarvis tries its other ways instead.
 */
class AlertNotifier(
    private val context: Context,
    private val client: JarvisClient,
    private val hub: Hub,
    private val person: () -> Boolean,
    private val scope: CoroutineScope,
) {
  private val threads = Threads()
  private val nm = context.getSystemService(NotificationManager::class.java)
  private val you = Person.Builder().setName("You").build()
  private val jarvis = Person.Builder().setName("Jarvis").setBot(true).build()

  init {
    nm.createNotificationChannel(
        NotificationChannel(CHANNEL, "Jarvis's alerts", NotificationManager.IMPORTANCE_HIGH).apply {
          description = "What Jarvis tells you first: reminders, routines, home events, job results. Reply to answer Jarvis."
        },
    )
  }

  /** Whether Android shows this app's alerts: notifications allowed, and the channel not switched off. */
  fun canShow(): Boolean = nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

  fun show(alert: Alert): Boolean {
    if (!canShow()) return false
    post(alert.id, alert.title, threads.open(alert), working = false, quiet = false)
    return true
  }

  /** The person replied under [id]'s notification: ask Jarvis, then show the answer there. */
  fun reply(id: String, title: String, alertText: String, text: String) {
    val asked = threads.add(id, Turn.user(text), alertText)
    post(id, title, asked, working = true, quiet = true)
    scope.launch {
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
      post(id, title, threads.add(id, Turn.jarvis(answer), alertText), working = false, quiet = true)
      // In the shared history too, so Chat and the web app show it; what went wrong is not kept.
      if (ok && person()) {
        val now = System.currentTimeMillis()
        runCatching { hub.keep(listOf(Said(Said.newId(), now, "user", text), Said(Said.newId(), now, "assistant", answer))) }
      }
    }
  }

  private fun post(id: String, title: String, turns: List<Turn>, working: Boolean, quiet: Boolean) {
    val style = Notification.MessagingStyle(you).setConversationTitle(title)
    val now = System.currentTimeMillis()
    for (t in turns) style.addMessage(Markdown.plain(t.text), now, if (t.role == "user") null else jarvis)
    if (working) style.addMessage("Thinking…", now, jarvis)

    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val back =
        Intent(context, ReplyReceiver::class.java)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_ALERT, turns.first().text)
    // Mutable, as a reply's words are filled in by the system; the intent names its receiver.
    val replyTo = PendingIntent.getBroadcast(context, id.hashCode(), back, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val reply =
        Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), "Reply", replyTo)
            .addRemoteInput(RemoteInput.Builder(KEY).setLabel("Reply to Jarvis").build())
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()

    val n =
        Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setStyle(style)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(quiet)
            .setAutoCancel(true)
            .apply { if (!working) addAction(reply) }
            .build()
    nm.notify(id.hashCode(), n)
  }

  companion object {
    private const val CHANNEL = "alerts"
    const val KEY = "reply"
    const val EXTRA_ID = "alert"
    const val EXTRA_TITLE = "title"
    const val EXTRA_ALERT = "alertText"
  }
}

/** Reply, under an alert's notification. */
class ReplyReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AlertNotifier.KEY)?.toString()?.trim().orEmpty()
    val id = intent.getStringExtra(AlertNotifier.EXTRA_ID) ?: return
    if (text.isEmpty()) return
    context.jarvis.notifier.reply(id, intent.getStringExtra(AlertNotifier.EXTRA_TITLE) ?: "Jarvis", intent.getStringExtra(AlertNotifier.EXTRA_ALERT).orEmpty(), text)
  }
}
