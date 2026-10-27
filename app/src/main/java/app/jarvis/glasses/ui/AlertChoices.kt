package app.jarvis.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.AlertPrefs
import kotlinx.coroutines.launch

/**
 * The person's own alert choices, the same as Jarvis's Family → You → Alerts and kept there, so
 * the same on every screen of theirs: whether Jarvis talks to them about alerts, and for those who
 * take part in family messages, whether the family room's are said too and where answering one
 * aloud goes. Jarvis applies them; this only shows and changes them.
 */
@Composable
fun AlertChoices(app: JarvisApp) {
  var prefs by remember { mutableStateOf<AlertPrefs?>(null) }
  var chat by remember { mutableStateOf(false) }
  var note by remember { mutableStateOf("") }
  val scope = rememberCoroutineScope()

  LaunchedEffect(Unit) {
    runCatching { app.hub.me() }
        .onSuccess { prefs = it.prefs; chat = it.can("chat") }
        .onFailure { note = it.message ?: "Couldn't reach Jarvis" }
  }

  // Shown at once, put back if Jarvis didn't keep it.
  fun change(name: String, value: String, next: AlertPrefs) {
    val was = prefs
    prefs = next
    note = ""
    scope.launch {
      runCatching { app.hub.setAlertPrefs(mapOf(name to value)) }
          .onSuccess { prefs = it }
          .onFailure { prefs = was; note = it.message ?: "Not saved" }
    }
  }

  Section("Your alerts") {
    Text("Yours on every screen, as in Jarvis's Family → You → Alerts.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
    val p = prefs
    if (p == null) {
      Text(note.ifEmpty { "Loading…" }, style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
      return@Section
    }
    Toggle("Talk to me about alerts", p.alertTalk != "off") { on -> change("alertTalk", if (on) "on" else "off", p.copy(alertTalk = if (on) "on" else "off")) }
    Text(
        "Jarvis says each alert in your glasses, or on a screen that can speak, and listens for your reply. Off, they are still in Chat and a notification, but not said.",
        style = MaterialTheme.typography.bodySmall,
        color = Even.colors.dim,
    )
    if (chat) {
      Toggle("Say family-room messages too", p.roomTalk != "off") { on -> change("roomTalk", if (on) "on" else "off", p.copy(roomTalk = if (on) "on" else "off")) }
      Text("Answer a family-room message", style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in listOf("sender" to "To whoever wrote it", "room" to "In the family room")) {
          FilterChip(selected = (p.roomReply == "room") == (key == "room"), onClick = { if (p.roomReply != key) change("roomReply", key, p.copy(roomReply = key)) }, label = { Text(label) })
        }
      }
      Text(
          "When you answer one aloud (\"tell them yes\"), Jarvis sends it to whoever wrote it, or into the family room for everyone. Direct messages are always answered to their sender.",
          style = MaterialTheme.typography.bodySmall,
          color = Even.colors.dim,
      )
    }
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = Even.colors.danger)
  }
}
