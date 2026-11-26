package app.jarvis.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobCard
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.NewJob
import app.jarvis.glasses.hub.TELL_MAX
import app.jarvis.glasses.hub.Todo
import app.jarvis.glasses.hub.changeCard
import app.jarvis.glasses.hub.jobCsv
import app.jarvis.glasses.hub.jobFile
import app.jarvis.glasses.hub.removeJob
import app.jarvis.glasses.hub.stopHelper
import app.jarvis.glasses.hub.tellJob
import app.jarvis.glasses.hub.useKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One background job, whole, as the web's Jobs and JobLive show it: what it is and reads, its card
 * (changed while it works), its checklist and what it is doing this moment, what you told it and
 * whether it reached it, Tell it, its questions, its helpers with Stop, what it ran, its files to
 * save, its result with what it could not do and took for granted first, and what can be done with
 * it. Looked at again every few seconds while it works and the screen is in front. Text only: a
 * job's words may come from pages and mail. [again], its brief for a thorough job to read over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobPage(app: JarvisApp, first: Job, back: () -> Unit, again: (NewJob) -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var j by remember(first.id) { mutableStateOf(first) }
  var note by remember(first.id) { mutableStateOf("") }
  var bad by remember(first.id) { mutableStateOf(false) }
  var telling by rememberSaveable(first.id) { mutableStateOf("") }
  var changing by remember(first.id) { mutableStateOf<JobCard?>(null) }

  fun say(text: String, wrong: Boolean = false) {
    note = text
    bad = wrong
  }

  fun press(done: String, f: suspend () -> Unit) {
    say("")
    scope.launch {
      runCatching { f() }.onSuccess { if (done.isNotEmpty()) say(done) }.onFailure { say(it.message ?: "Jarvis said no", true) }
      runCatching { app.hub.job(j.id) }.onSuccess { j = it }
    }
  }

  Every(first.id, ms = { if (j.waiting != null || j.asks.isNotEmpty()) 20_000L else 4_000L }) {
    runCatching { app.hub.job(j.id) }.onSuccess { j = it }
    j.status == "running"
  }

  val running = j.status == "running"
  val hears = j.engine == "jarvis" && running && j.wrapping == null
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead(j.title.ifEmpty { "Job" }, back) }
    item {
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(JobWords.status(j), style = MaterialTheme.typography.labelLarge, color = if (j.asks.isNotEmpty()) c.ink else c.dim)
          Text(JobWords.facts(j, System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall, color = c.dim)
          j.using?.let { Text("Following “${it.name}”", style = MaterialTheme.typography.bodySmall, color = c.dim) }
          j.project?.let { Text("In “${it.name}”", style = MaterialTheme.typography.bodySmall, color = c.dim) }
          JobWords.waiting(j)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          if (running && j.engine == "jarvis" && j.waiting == null) Text(JobWords.nowDoing(j), style = MaterialTheme.typography.bodyMedium)
          j.team?.takeIf { running }?.let { t -> Text(TEAM[t.phase] ?: "${t.reported} of ${t.of} teams done", style = MaterialTheme.typography.bodySmall, color = c.dim) }
        }
      }
    }
    // Its card: what it will come to, what it keeps to, what it assumes, and what it may do, which code says.
    j.card?.let { card ->
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val typed = changing
            if (typed == null) {
              Text(card.outcome, style = MaterialTheme.typography.bodyLarge)
              if (card.limits.isNotEmpty()) Text("Keeping to: " + card.limits.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
              if (card.assumptions.isNotEmpty()) Text("Assuming: " + card.assumptions.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
              if (running) Text(JobWords.powers(j), style = MaterialTheme.typography.bodySmall, color = c.dim)
              if (hears) OutlinedButton(onClick = { changing = card }) { Text("Change") }
            } else {
              OutlinedTextField(typed.outcome, { changing = typed.copy(outcome = it) }, label = { Text("What it comes to") }, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(typed.limits.joinToString("\n"), { changing = typed.copy(limits = lines(it)) }, label = { Text("What it keeps to, a line each") }, modifier = Modifier.fillMaxWidth())
              OutlinedTextField(typed.assumptions.joinToString("\n"), { changing = typed.copy(assumptions = lines(it)) }, label = { Text("What it assumes, a line each") }, modifier = Modifier.fillMaxWidth())
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                  changing = null
                  press("Told: it goes on from there at its next step.") { app.hub.changeCard(j.id, typed) }
                }) { Text("Save") }
                OutlinedButton(onClick = { changing = null }) { Text("Cancel") }
              }
            }
          }
        }
      }
    }
    if (j.todo.isNotEmpty()) item { Checklist(j.todo, over = !running, progress = JobWords.progress(j.todo)) }
    if (running) items(j.asks, key = { "ask:${it.id}" }) { q -> AskCard(app.hub, j.id, q, tag = "It asks you") { press("") {} } }
    // What you told it, and whether a step has had it.
    val heard = JobWords.heard(j)
    if (heard.isNotEmpty() || hears) {
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((said, state) in heard) Text("$said · $state", style = MaterialTheme.typography.bodySmall)
            if (hears) {
              OutlinedTextField(telling, { telling = it.take(TELL_MAX) }, label = { Text("Tell it something") }, modifier = Modifier.fillMaxWidth())
              OutlinedButton(enabled = telling.isNotBlank(), onClick = {
                val t = telling.trim()
                telling = ""
                press("Told: it takes it in at its next step.") { app.hub.tellJob(j.id, t) }
              }) { Text("Tell it") }
            }
          }
        }
      }
    }
    if (j.helpers.isNotEmpty()) {
      items(j.helpers, key = { "helper:${it.n}" }) { h ->
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.bodyMedium)
                Text(JobWords.helperNow(h, j) + (JobWords.progress(h.todo)?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = c.dim)
              }
              if (h.state == "working" && running) OutlinedButton(onClick = { press("Stopped: the job is told.") { app.hub.stopHelper(j.id, h.n) } }) { Text("Stop") }
            }
            if (h.todo.isNotEmpty()) Checklist(h.todo, over = !(h.state == "working" && running), progress = null, inTile = false)
          }
        }
      }
    }
    if (j.ran.isNotEmpty()) {
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("What it ran", style = MaterialTheme.typography.labelLarge)
            for (r in j.ran) Text("${r.by?.let { "helper $it: " }.orEmpty()}${r.cmd} · ${JobWords.ended(r, over = !running)}", style = MaterialTheme.typography.bodySmall, color = c.dim)
          }
        }
      }
    }
    if (j.files.isNotEmpty()) {
      item {
        Tile(Modifier.fillMaxWidth()) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What it made", style = MaterialTheme.typography.labelLarge)
            for (f in j.files) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.name} (${JobWords.sizeSaid(f.size)})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { press("") { val bytes = app.hub.jobFile(j.id, f.n); say(withContext(Dispatchers.IO) { saveDownload(context, f.name, bytes) }) } }) { Text("Save") }
              }
            }
          }
        }
      }
    }
    item {
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          j.summary?.takeIf { j.result == null }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
          j.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger) }
          // Before the result, not after it: what it does not have, and what it rests on.
          if (j.missing.isNotEmpty()) Lines("Not done", j.missing)
          if (j.assumed.isNotEmpty()) Lines("Assumed", j.assumed)
          j.result?.let { MarkdownText(it, c.ink, c.dim, c.chip) }
          Text("What was asked", style = MaterialTheme.typography.labelLarge)
          Text(j.task, style = MaterialTheme.typography.bodySmall, color = c.dim)
          if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = if (bad) c.danger else c.dim)
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            j.waiting?.key?.let { k -> Confirm("Use the API key now", JobWords.keyCost(k.soFar)) { press("Moved to the API key: it goes on now.") { app.hub.useKey(j.id) } } }
            j.table?.takeIf { it.rows > 0 }?.let { t -> OutlinedButton(onClick = { press("") { val bytes = app.hub.jobCsv(j.id); say(withContext(Dispatchers.IO) { saveDownload(context, "${j.title.ifEmpty { "table" }}.csv", bytes) }) } }) { Text("Save as CSV (${t.rows} rows)") } }
            // Never by itself: the same brief, with what they told it, on the stronger model, for them to read over and start.
            if (j.engine == "jarvis" && j.mode == null && (j.status == "done" || j.status == "failed")) {
              OutlinedButton(onClick = {
                val told = j.told.filter { it.to == null && !it.card }.map { it.text }
                again(NewJob((listOf(j.task) + told).joinToString("\n\n"), reach = j.reach ?: "private", kind = "thorough", specialty = j.using?.id, project = j.project?.id))
              }) { Text("Try again harder") }
            }
            if (running) OutlinedButton(onClick = { press("Stopped.") { app.hub.cancelJob(j.id) } }) { Text("Stop") }
            else Confirm("Remove", "Remove it and what it made?") {
              scope.launch { runCatching { app.hub.removeJob(j.id) }.onSuccess { back() }.onFailure { say(it.message ?: "Not removed", true) } }
            }
          }
        }
      }
    }
  }
}

/** Research as a team, while it runs: which part it is in. */
private val TEAM = mapOf("planning" to "Choosing its angles", "merging" to "Putting the teams' reports together", "checking" to "Checking the report against its sources")

/** A list typed a line each. */
private fun lines(typed: String): List<String> = typed.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

/** A checklist, its state said as well as shown; [over], whose it is has ended. */
@Composable
private fun Checklist(todo: List<Todo>, over: Boolean, progress: String?, inTile: Boolean = true) {
  val c = Even.colors
  val body: @Composable () -> Unit = {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
      progress?.let { Text("Its list: $it", style = MaterialTheme.typography.labelLarge) }
      for (t in todo) {
        val word = JobWords.todoWord(t, over)
        Text("$word: ${t.text}", style = MaterialTheme.typography.bodySmall, color = if (word == "done" || word == "not done") c.dim else c.ink)
        t.why?.let { Text("   $it", style = MaterialTheme.typography.labelSmall, color = c.dim) }
      }
    }
  }
  if (inTile) Tile(Modifier.fillMaxWidth()) { body() } else body()
}

/** What a job's answer is missing, or rests on, a line each. */
@Composable
internal fun Lines(head: String, lines: List<String>) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(head, style = MaterialTheme.typography.labelLarge)
    for (l in lines) Text("• $l", style = MaterialTheme.typography.bodySmall)
  }
}
