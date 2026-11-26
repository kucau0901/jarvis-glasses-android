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
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.removeJob
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
  // On the web first, as the web app's form has it: a job reads one side, never both.
  var reach by remember { mutableStateOf("web") }
  var note by remember { mutableStateOf("") }
  // Why the last start or press was refused: kept until the next one, however often the list is read.
  var problem by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) {
    while (true) {
      runCatching { app.hub.jobs() }.onSuccess { jobs = it; note = "" }.onFailure { note = it.message ?: "Couldn't read jobs" }
      // Sooner while one works, and not waiting on anyone.
      delay(if (jobs.any { it.status == "running" && it.waiting == null }) 5_000 else 20_000)
    }
  }
  fun press(f: suspend () -> Unit) {
    problem = ""
    scope.launch {
      runCatching { f() }.onFailure { problem = it.message ?: "Jarvis said no" }
      tick++
    }
  }
  val o = opened
  if (o != null) {
    // Looked at again while it works, so its questions and state are as they are now.
    LaunchedEffect(o.id, tick) {
      while (true) {
        val now = opened?.takeIf { it.status == "running" } ?: break
        delay(if (now.waiting != null) 20_000 else 5_000)
        runCatching { app.hub.job(now.id) }.onSuccess { opened = it }
      }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      item { SubHeading(o.title.ifEmpty { "Job" }) { opened = null } }
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(JobWords.status(o) + reachSaid(o), style = MaterialTheme.typography.labelLarge, color = c.dim)
            Text(o.task, style = MaterialTheme.typography.bodySmall, color = c.dim)
            JobWords.waiting(o)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) }
          }
        }
      }
      if (o.status == "running") items(o.asks, key = { "ask:${it.id}" }) { q -> AskCard(app.hub, o.id, q, tag = "It asks you") { tick++ } }
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            o.summary?.takeIf { o.result == null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            // What ran out, for one part done or not done: above what it had.
            o.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger) }
            // Before the result, not after it: what it does not have, and what it rests on.
            if (o.missing.isNotEmpty()) Flags("Not done", o.missing)
            if (o.assumed.isNotEmpty()) Flags("Assumed", o.assumed)
            val result = o.result
            if (result != null) MarkdownText(result, c.ink, c.dim, c.chip)
            else if (o.status == "running" && o.waiting == null && o.asks.isEmpty()) Text("Still working…", style = MaterialTheme.typography.bodyMedium, color = c.ink)
            if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
            if (o.status == "running") OutlinedButton(onClick = { press { app.hub.cancelJob(o.id); opened = null } }) { Text("Stop") }
            else Confirm("Remove", "Remove it and what it made?") { press { app.hub.removeJob(o.id); opened = null } }
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
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SafePill("On the web", selected = reach == "web") { reach = "web" }
            SafePill("My own things", selected = reach == "private") { reach = "private" }
          }
          Text(
              if (reach == "web") "Searches the web, and is told nothing of yours but what you write here."
              else "Reads your own mail, calendar, memory, house, car and cameras, as far as you may. Does not search the web.",
              style = MaterialTheme.typography.bodySmall,
              color = c.dim,
          )
          OutlinedButton(
              enabled = task.isNotBlank(),
              onClick = {
                val t = task
                task = ""
                problem = ""
                scope.launch {
                  // Refused (too many at once, the day's allowance spent): why stays, and so do their words.
                  runCatching { app.hub.startJob(t, reach) }.onFailure { problem = it.message ?: "Jarvis didn't start it"; task = t }
                  tick++
                }
              },
          ) {
            Text("Start")
          }
          if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
      }
    }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    items(jobs) { j ->
      Tile(Modifier.fillMaxWidth(), onClick = { scope.launch { opened = runCatching { app.hub.job(j.id) }.getOrDefault(j) } }) {
        Column {
          Text(j.title.ifEmpty { j.task }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          Text(JobWords.status(j) + (j.summary?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (j.asks.isNotEmpty()) c.ink else c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
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

/** Which side it read, beside its state. */
private fun reachSaid(j: Job): String =
    when {
      j.engine == "hermes" -> ""
      j.engine == "research" || j.reach == "web" -> " · the web only"
      else -> " · your own things, no web"
    }

/** What a job's answer is missing, or rests on, a line each. */
@Composable
private fun Flags(head: String, lines: List<String>) {
  val c = Even.colors
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(head, style = MaterialTheme.typography.labelLarge, color = c.ink)
    for (l in lines) Text("• $l", style = MaterialTheme.typography.bodySmall, color = c.ink)
  }
}

/** A button pressed twice: first it asks [sure], then it does [act]. */
@Composable
internal fun Confirm(label: String, sure: String, act: () -> Unit) {
  var asking by remember { mutableStateOf(false) }
  LaunchedEffect(asking) {
    if (asking) {
      delay(4_000)
      asking = false
    }
  }
  OutlinedButton(onClick = { if (asking) { asking = false; act() } else asking = true }) { Text(if (asking) sure else label) }
}
