package app.jarvis.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.Doubts
import app.jarvis.glasses.hub.Fact
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Me
import app.jarvis.glasses.hub.Way
import app.jarvis.glasses.hub.WayDraft
import app.jarvis.glasses.hub.doubts
import app.jarvis.glasses.hub.importWay
import app.jarvis.glasses.hub.keepOfPair
import app.jarvis.glasses.hub.keepPassed
import app.jarvis.glasses.hub.ways
import kotlinx.coroutines.launch

/**
 * Memory, as the web's sections of it the agent work added: Specialties (yours, each opened whole;
 * New; Import a file for an admin) and Which is true now? (looked for when pressed). [start], a
 * draft to read over at once (Keep for next time, from a job).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(app: JarvisApp, back: () -> Unit, start: WayDraft? = null) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var me by remember { mutableStateOf<Me?>(null) }
  var ways by remember { mutableStateOf<List<Way>>(emptyList()) }
  var opened by remember { mutableStateOf<String?>(null) }
  var draft by remember { mutableStateOf(start) }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(Unit) { me = runCatching { app.hub.me() }.getOrNull() }
  LaunchedEffect(tick) { runCatching { app.hub.ways() }.onSuccess { ways = it }.onFailure { note = it.message ?: "Couldn't read them" } }
  val admin = me?.role == "admin" || me?.scopes?.contains("*") == true
  val writes = me?.can("memory.write") == true
  val mayFiles = me?.workshop == true
  val pick = rememberFilePicker(5_000_000) { files, big ->
    if (big > 0) note = "That file is over 5 MB."
    files.firstOrNull()?.let { f ->
      scope.launch {
        runCatching { app.hub.importWay(f.name, f.bytes) }
            .onSuccess { draft = it.copy(notes = listOf("Written elsewhere. Read all of it before you save it: background jobs follow it, and may run its files in the workshop.") + it.notes) }
            .onFailure { note = "Not imported: ${it.message}" }
      }
    }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Memory", back) }
    item {
      Text("Specialties", style = MaterialTheme.typography.titleMedium)
      Text("How you like something done, kept to be done the same way again. Asked for by name, its steps are followed; background jobs read those that fit.", style = MaterialTheme.typography.bodySmall, color = c.dim)
    }
    val d = draft
    if (d != null) item { WayForm(app.hub, d, mayFiles) { saved -> draft = null; if (saved) { note = "Saved."; tick++ } } }
    else if (writes) {
      item {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { draft = WayDraft(source = "written") }) { Text("New") }
          if (admin) OutlinedButton(onClick = pick) { Text("Import a file…") }
        }
      }
    }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim) }
    items(ways, key = { it.id }) { w ->
      if (opened == w.id) WayPage(app.hub, w, mayFiles) { opened = null; tick++ }
      else
          Tile(Modifier.fillMaxWidth(), onClick = { opened = w.id }) {
            Column {
              Text(w.name, style = MaterialTheme.typography.bodyMedium)
              Text("${w.steps.size} steps" + (if (w.files.isNotEmpty()) " · ${w.files.size} files" else "") + (if (w.before == 1) " · changed once" else if (w.before > 1) " · changed ${w.before} times" else ""), style = MaterialTheme.typography.bodySmall, color = c.dim)
            }
          }
    }
    item { TrueNow(app, family = admin) }
  }
}

/**
 * Which is true now? (src/app/ui/TrueNow.ts there): saved facts that may say one thing twice, or
 * disagree, and ones whose day has passed, found by Jarvis's code when pressed, with a choice for
 * each and their dates. Nothing here writes a fact: what is not kept goes to Recently forgotten.
 * [family]: the family's book too, for an admin.
 */
@Composable
private fun TrueNow(app: JarvisApp, family: Boolean) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var found by remember { mutableStateOf<List<Pair<Boolean, Doubts>>?>(null) }
  var gone by remember { mutableStateOf(setOf<String>()) }
  var note by remember { mutableStateOf("") }
  fun choose(f: suspend () -> List<String>, row: String) {
    note = ""
    scope.launch { runCatching { f() }.onSuccess { gone = gone + row + it }.onFailure { note = it.message ?: "Not changed" } }
  }
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Which is true now?", style = MaterialTheme.typography.titleMedium)
      OutlinedButton(onClick = {
        note = "Looking…"
        scope.launch {
          runCatching { listOf(false to app.hub.doubts(false)) + if (family) listOf(true to app.hub.doubts(true)) else emptyList() }
              .onSuccess { found = it; gone = emptySet(); note = if (it.all { (_, d) -> d.pairs.isEmpty() && d.passed.isEmpty() }) "Nothing to ask about." else "" }
              .onFailure { note = "Could not look: ${it.message}" }
        }
      }) { Text("Look for them") }
      if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim)
      for ((book, d) in found.orEmpty()) {
        for (p in d.pairs) {
          val row = "${p.older.id} ${p.newer.id}"
          if (row in gone || p.older.id in gone || p.newer.id in gone) continue
          Said(p.older, "Older: saved", book)
          Said(p.newer, "Newer: saved", book)
          androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choose({ app.hub.keepOfPair(book, p.older, p.newer, listOf(p.older.id)) }, row) }) { Text("Keep the older") }
            OutlinedButton(onClick = { choose({ app.hub.keepOfPair(book, p.older, p.newer, listOf(p.newer.id)) }, row) }) { Text("Keep the newer") }
            OutlinedButton(onClick = { choose({ app.hub.keepOfPair(book, p.older, p.newer, listOf(p.older.id, p.newer.id)) }, row) }) { Text("Both are true") }
            OutlinedButton(onClick = { choose({ app.hub.keepOfPair(book, p.older, p.newer, emptyList()) }, row) }) { Text("Neither") }
          }
        }
        for (p in d.passed) {
          if (p.fact.id in gone) continue
          Said(p.fact, "Saved", book)
          Text("Its day has passed: ${p.day}.", style = MaterialTheme.typography.bodySmall, color = c.dim)
          androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choose({ app.hub.keepPassed(book, p.fact, true) }, p.fact.id) }) { Text("Still true") }
            OutlinedButton(onClick = { choose({ app.hub.keepPassed(book, p.fact, false) }, p.fact.id) }) { Text("Forget it") }
          }
        }
      }
    }
  }
}

/** A saved fact, with when it was saved and whose book it is in. */
@Composable
private fun Said(f: Fact, saved: String, family: Boolean) {
  val c = Even.colors
  Text(f.text, style = MaterialTheme.typography.bodyMedium)
  f.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.dim) }
  Text("$saved ${JobWords.daySaid(f.updatedAt)}" + (if (f.createdAt != f.updatedAt) ", first saved ${JobWords.daySaid(f.createdAt)}" else "") + if (family) " · the family's" else "", style = MaterialTheme.typography.labelSmall, color = c.dim)
}
