package app.jarvis.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Routine
import app.jarvis.glasses.hub.routineRuns
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Routines: what Jarvis does on its own, at a time or when something happens. */
@Composable
fun RoutinesScreen(app: JarvisApp, back: () -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var routines by remember { mutableStateOf<List<Routine>>(emptyList()) }
  var note by remember { mutableStateOf("") }
  // Why Jarvis refused a switch or a run, by routine: a last day past, say.
  var refused by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var tick by remember { mutableStateOf(0) }
  var me by remember { mutableStateOf<app.jarvis.glasses.hub.Me?>(null) }
  // Whose past runs are open, and what they were.
  var runsOf by remember { mutableStateOf<String?>(null) }
  var runs by remember { mutableStateOf<List<app.jarvis.glasses.hub.RoutineRun>>(emptyList()) }
  LaunchedEffect(Unit) { me = runCatching { app.hub.me() }.getOrNull() }
  LaunchedEffect(tick) {
    runCatching { app.hub.routines() }.onSuccess { routines = it; note = "" }.onFailure { note = it.message ?: "Couldn't read routines" }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Routines", back) }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    items(routines) { r ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(r.name, style = MaterialTheme.typography.bodyMedium)
              Text(r.`when`, style = MaterialTheme.typography.bodySmall, color = c.dim)
            }
            Switch(r.enabled, { on ->
              scope.launch {
                runCatching { app.hub.setRoutine(r.id, on) }.onSuccess { refused = refused - r.id }.onFailure { refused = refused + (r.id to (it.message ?: "Not changed")) }
                tick++
              }
            })
          }
          Text(r.does, style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
          r.lastRun?.let { Text((if (it.ok) "Last run: " else "Last run failed: ") + it.detail, style = MaterialTheme.typography.labelSmall, color = c.dim, maxLines = 2) }
          refused[r.id]?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
          OutlinedButton(onClick = {
            scope.launch {
              runCatching { app.hub.runRoutine(r.id) }.onSuccess { refused = refused - r.id }.onFailure { refused = refused + (r.id to (it.message ?: "Not run")) }
              delay(1_500)
              tick++
            }
          }) { Text("Run now") }
          OutlinedButton(onClick = {
            if (runsOf == r.id) runsOf = null
            else scope.launch { runs = runCatching { app.hub.routineRuns(r.id) }.getOrDefault(emptyList()); runsOf = r.id }
          }) { Text(if (runsOf == r.id) "Hide past runs" else "Past runs") }
          if (runsOf == r.id) {
            if (runs.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = c.dim)
            for (run in runs) Text("${JobWords.atSaid(run.at)} · ${if (run.ok) "" else "failed: "}${run.detail}" + (run.said?.let { ": $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (run.ok) c.dim else c.danger, maxLines = 4)
          }
        }
      }
    }
    item { RoutineForm(app, family = me?.can("routines") == true) { tick++ } }
  }
}
