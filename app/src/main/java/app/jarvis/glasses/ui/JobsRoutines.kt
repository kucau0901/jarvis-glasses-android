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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.Routine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
private fun SubHeading(title: String, back: () -> Unit) {
  BackHandler(onBack = back)
  Row(verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
    Text(title, style = MaterialTheme.typography.headlineSmall)
  }
}

/** Background jobs: what Jarvis is working on, and what came back. */
@Composable
fun JobsScreen(app: JarvisApp, back: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
  var opened by remember { mutableStateOf<Job?>(null) }
  var task by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) {
    while (true) {
      runCatching { app.hub.jobs() }.onSuccess { jobs = it; note = "" }.onFailure { note = it.message ?: "Couldn't read jobs" }
      delay(10_000)
    }
  }
  val o = opened
  if (o != null) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item { SubHeading(o.title.ifEmpty { "Job" }) { opened = null } }
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(o.status, style = MaterialTheme.typography.labelLarge, color = c.dim)
            Text(o.task, style = MaterialTheme.typography.bodySmall, color = c.dim)
            val result = o.result
            if (result != null) MarkdownText(result, c.ink, c.dim, c.chip)
            else Text(o.error ?: o.summary ?: "Still working…", style = MaterialTheme.typography.bodyMedium, color = if (o.error != null) c.danger else c.ink)
            if (o.status == "running") OutlinedButton(onClick = { scope.launch { runCatching { app.hub.cancelJob(o.id) }; opened = null; tick++ } }) { Text("Cancel") }
          }
        }
      }
    }
    return
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { SubHeading("Jobs", back) }
    item {
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(task, { task = it }, label = { Text("Ask Jarvis to work on…") }, modifier = Modifier.fillMaxWidth())
          OutlinedButton(enabled = task.isNotBlank(), onClick = { val t = task; task = ""; scope.launch { runCatching { app.hub.startJob(t) }.onFailure { note = it.message ?: "" }; tick++ } }) {
            Text("Start")
          }
        }
      }
    }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    items(jobs) { j ->
      Tile(Modifier.fillMaxWidth(), onClick = { scope.launch { opened = runCatching { app.hub.job(j.id) }.getOrDefault(j) } }) {
        Column {
          Text(j.title.ifEmpty { j.task }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(j.status + (j.summary?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
      }
    }
  }
}

/** Routines: what Jarvis does on its own, at a time or when something happens. */
@Composable
fun RoutinesScreen(app: JarvisApp, back: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var routines by remember { mutableStateOf<List<Routine>>(emptyList()) }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  var name by remember { mutableStateOf("") }
  var time by remember { mutableStateOf("07:30") }
  var text by remember { mutableStateOf("") }
  LaunchedEffect(tick) {
    runCatching { app.hub.routines() }.onSuccess { routines = it; note = "" }.onFailure { note = it.message ?: "Couldn't read routines" }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { SubHeading("Routines", back) }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    items(routines) { r ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text(r.name, style = MaterialTheme.typography.bodyMedium)
              Text(r.`when`, style = MaterialTheme.typography.bodySmall, color = c.dim)
            }
            Switch(r.enabled, { on -> scope.launch { runCatching { app.hub.setRoutine(r.id, on) }; tick++ } })
          }
          Text(r.does, style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 3, overflow = TextOverflow.Ellipsis)
          r.lastRun?.let { Text((if (it.ok) "Last run: " else "Last run failed: ") + it.detail, style = MaterialTheme.typography.labelSmall, color = c.dim, maxLines = 2) }
          OutlinedButton(onClick = { scope.launch { runCatching { app.hub.runRoutine(r.id) }; delay(1_500); tick++ } }) { Text("Run now") }
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
