package app.jarvis.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.NewRoutine
import app.jarvis.glasses.hub.addRoutine
import kotlinx.coroutines.launch

/**
 * A new routine, as the web's form makes one: when (every day or some days at a time, once, on a
 * schedule, or when something happens) and what (say something, ask Jarvis, or start a background
 * job on the web or your own things, thorough for the family, with a cost limit and a last day).
 * Jarvis's refusals are said under it, with what was typed kept. [family]: may have a thorough job.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineForm(app: JarvisApp, family: Boolean, made: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var r by remember { mutableStateOf(NewRoutine("")) }
  var cap by remember { mutableStateOf("") }
  var problem by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("New routine", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, label) in KINDS) SafePill(label, enabled = !(r.does == "job" && k == "event"), selected = r.kind == k) { r = r.copy(kind = k) }
      }
      when (r.kind) {
        "daily" -> {
          OutlinedTextField(r.time, { r = r.copy(time = it) }, label = { Text("Time (HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DAYS.forEachIndexed { i, d -> SafePill(d, selected = i in r.days) { r = r.copy(days = if (i in r.days) r.days - i else r.days + i) } }
          }
          Text(if (r.days.isEmpty()) "Every day." else "Only on the days chosen.", style = MaterialTheme.typography.bodySmall, color = c.dim)
        }
        "once" -> OutlinedTextField(r.localTime, { r = r.copy(localTime = it) }, label = { Text("When (2026-12-01T07:30)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "cron" -> OutlinedTextField(r.cron, { r = r.copy(cron = it) }, label = { Text("Schedule: minute hour day month weekday (0 8 * * 1-5)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        "event" -> OutlinedTextField(r.event, { r = r.copy(event = it) }, label = { Text("The event (arrived_home)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SafePill("Say it", selected = r.does == "say") { r = r.copy(does = "say") }
        SafePill("Ask Jarvis", selected = r.does == "ask") { r = r.copy(does = "ask") }
        SafePill("Start a background job", enabled = r.kind != "event", selected = r.does == "job") { r = r.copy(does = "job") }
      }
      OutlinedTextField(
          r.text,
          { r = r.copy(text = it) },
          label = { Text(when (r.does) { "say" -> "What it says"; "job" -> "The job's brief, complete on its own"; else -> "Ask Jarvis (brief me on today)" }) },
          modifier = Modifier.fillMaxWidth(),
      )
      if (r.does == "job") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SafePill("On the web", selected = r.reach == "web") { r = r.copy(reach = "web") }
          SafePill("My own things", selected = r.reach == "private") { r = r.copy(reach = "private") }
          if (family) SafePill("Thorough", selected = r.thorough) { r = r.copy(thorough = !r.thorough) }
        }
        Text(
            "It starts as you, with no more than you may reach by then. " + if (r.reach == "web") "On the web it is told nothing of who you are." else "It reads your own things, without the web.",
            style = MaterialTheme.typography.bodySmall,
            color = c.dim,
        )
        OutlinedTextField(cap, { cap = it }, label = { Text("Stop at about this many dollars of the API key (0.05 to 2), or blank") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      }
      if (r.kind == "daily" || r.kind == "cron") OutlinedTextField(r.until, { r = r.copy(until = it) }, label = { Text("Last day, or blank (2026-12-14)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
      OutlinedButton(
          enabled = !busy,
          onClick = {
            val made2 = r.copy(cap = if (r.does == "job") cap.trim().toDoubleOrNull() else null)
            if (r.does == "job" && cap.isNotBlank() && made2.cap == null) return@OutlinedButton run { problem = "The cost limit is a number of dollars." }
            busy = true
            problem = ""
            scope.launch {
              runCatching { app.hub.addRoutine(made2) }
                  .onSuccess {
                    r = NewRoutine("")
                    cap = ""
                    made()
                  }
                  .onFailure { problem = it.message ?: "Jarvis didn't make it" }
              busy = false
            }
          },
      ) {
        Text("Add")
      }
    }
  }
}

private val KINDS = listOf("daily" to "At a time", "once" to "Once", "cron" to "On a schedule", "event" to "When something happens")

/** Sunday first, as Jarvis counts them. */
private val DAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
