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
import app.jarvis.glasses.hub.Routine
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
  var name by remember { mutableStateOf("") }
  var time by remember { mutableStateOf("07:30") }
  var text by remember { mutableStateOf("") }
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
        }
      }
    }
    item {
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("New daily routine", style = MaterialTheme.typography.titleMedium)
          OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(time, { time = it }, label = { Text("Time (HH:MM)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(text, { text = it }, label = { Text("Ask Jarvis (e.g. brief me on today)") }, modifier = Modifier.fillMaxWidth())
          OutlinedButton(
              enabled = name.isNotBlank() && text.isNotBlank() && Regex("\\d{1,2}:\\d{2}").matches(time),
              onClick = {
                scope.launch {
                  runCatching { app.hub.addDaily(name, time, text, ask = true) }.onFailure { note = it.message ?: "" }.onSuccess { name = ""; text = "" }
                  tick++
                }
              },
          ) {
            Text("Add")
          }
        }
      }
    }
  }
}
