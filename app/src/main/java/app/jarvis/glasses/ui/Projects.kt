package app.jarvis.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Project
import app.jarvis.glasses.hub.Site
import app.jarvis.glasses.hub.addProjectFile
import app.jarvis.glasses.hub.dropProjectFile
import app.jarvis.glasses.hub.forgetProject
import app.jarvis.glasses.hub.projectFile
import app.jarvis.glasses.hub.projects
import app.jarvis.glasses.hub.saveProject
import app.jarvis.glasses.hub.siteOp
import app.jarvis.glasses.hub.sites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Jobs → Projects, as the web's (src/app/ui/Projects.ts there): a name, up to ten rules and files
 * for jobs that belong together. A job in one is given those and what its last jobs came back with,
 * and nothing of any other project's. Files come from the phone's own picker, 1 MB each.
 */
@Composable
fun ProjectsScreen(app: JarvisApp, back: () -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var all by remember { mutableStateOf<List<Project>>(emptyList()) }
  var editing by remember { mutableStateOf<Project?>(null) }
  var into by remember { mutableStateOf<String?>(null) }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) { runCatching { app.hub.projects() }.onSuccess { all = it }.onFailure { note = it.message ?: "Couldn't read them" } }
  fun act(done: String = "", f: suspend () -> Unit) {
    note = ""
    scope.launch { runCatching { f() }.onSuccess { if (done.isNotEmpty()) note = done }.onFailure { note = it.message ?: "Jarvis said no" }; tick++ }
  }
  val pick = rememberFilePicker(1_000_000) { files, big ->
    val id = into ?: return@rememberFilePicker
    if (big > 0) note = "${if (big == 1) "One file is" else "$big files are"} over 1 MB, and not added."
    act { for (f in files) app.hub.addProjectFile(id, f) }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Projects", back) }
    item { Text("A name, a few rules and files for jobs that belong together. A job in one is given those and what its last jobs came back with, and nothing of any other project's.", style = MaterialTheme.typography.bodySmall, color = c.dim) }
    val e = editing
    if (e != null) item { ProjectForm(e) { name, rules -> if (name == null) editing = null else act { app.hub.saveProject(e.id.ifEmpty { null }, name, rules); editing = null } } }
    else item { OutlinedButton(onClick = { editing = Project("") }) { Text("New project") } }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim) }
    items(all, key = { it.id }) { p ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(p.name, style = MaterialTheme.typography.titleMedium)
          p.rules.forEachIndexed { i, r -> Text("${i + 1}. $r", style = MaterialTheme.typography.bodySmall) }
          for (f in p.files) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("${f.name} (${JobWords.sizeSaid(f.size)})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
              OutlinedButton(onClick = { act { val b = app.hub.projectFile(p.id, f.n); note = withContext(Dispatchers.IO) { saveDownload(context, f.name, b) } } }) { Text("Save") }
              Confirm("Remove", "Remove it?") { act { app.hub.dropProjectFile(p.id, f.n) } }
            }
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = p }) { Text("Change") }
            OutlinedButton(onClick = { into = p.id; pick() }) { Text("Add files…") }
            Confirm("Remove", "Remove it and its files?") { act("Removed: its jobs stay.") { app.hub.forgetProject(p.id) } }
          }
        }
      }
    }
  }
}

/** A project's name and rules, a line each; [done] with null to cancel. */
@Composable
private fun ProjectForm(p: Project, done: (name: String?, rules: List<String>) -> Unit) {
  var name by remember(p.id) { mutableStateOf(p.name) }
  var rules by remember(p.id) { mutableStateOf(p.rules.joinToString("\n")) }
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(rules, { rules = it }, label = { Text("Rules for its jobs, a line each (ten at most)") }, modifier = Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = name.isNotBlank(), onClick = { done(name.trim(), rules.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) }) { Text("Save") }
        OutlinedButton(onClick = { done(null, emptyList()) }) { Text("Cancel") }
      }
    }
  }
}

/**
 * Jobs → Sites, as the web's (src/app/ui/Sites.ts there): what jobs built and a tap published, each
 * with its address, files, size and when; Put back the version before, or Take down, each confirmed.
 */
@Composable
fun SitesScreen(app: JarvisApp, back: () -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var address by remember { mutableStateOf<String?>(null) }
  var sites by remember { mutableStateOf<List<Site>?>(null) }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) { runCatching { app.hub.sites() }.onSuccess { address = it.address; sites = it.sites; if (it.sites == null) note = "There is no sites folder: a site that is built can be saved as a zip instead." }.onFailure { note = it.message ?: "Couldn't read them" } }
  fun act(done: String, f: suspend () -> Unit) {
    note = ""
    scope.launch { runCatching { f() }.onSuccess { note = done }.onFailure { note = it.message ?: "Jarvis said no" }; tick++ }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Sites", back) }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim) }
    items(sites.orEmpty(), key = { it.name }) { s ->
      val at = address?.trimEnd('/')?.let { "$it/${s.name}/" }
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(s.name + if (s.down) " (taken down)" else "", style = MaterialTheme.typography.titleMedium)
          Text("${s.files} files · ${JobWords.sizeSaid(s.bytes)} · ${JobWords.whenSaid(s.at)}", style = MaterialTheme.typography.bodySmall, color = c.dim)
          if (at != null && !s.down) Text(at, style = MaterialTheme.typography.bodySmall, color = c.tintOpen, modifier = Modifier.clickable { context.view(at) })
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.before != null) Confirm("Put back", "Put the version before back?") { act("Put back.") { app.hub.siteOp(s.name, "back") } }
            if (!s.down) Confirm("Take down", "Take it down?") { act("Taken down: its last version is kept.") { app.hub.siteOp(s.name, "down") } }
          }
        }
      }
    }
  }
}
