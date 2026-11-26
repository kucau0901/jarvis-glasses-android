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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Me
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.NewJob
import app.jarvis.glasses.hub.projectNames
import app.jarvis.glasses.hub.specialtyNames
import kotlinx.coroutines.launch

/**
 * Background jobs: the New job form, then what Jarvis is working on and what came back, each
 * opened whole ([JobPage]). Looked at again every few seconds while one works and the screen is in
 * front, less often while they all wait. [open], a job to open at once (a pill in Chat, a question's
 * notification).
 */
@Composable
fun JobsScreen(app: JarvisApp, back: () -> Unit, open: String? = null) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
  var opened by remember { mutableStateOf<Job?>(null) }
  var again by remember { mutableStateOf<NewJob?>(null) }
  var note by remember { mutableStateOf("") }
  var me by remember { mutableStateOf<Me?>(null) }
  var specialties by remember { mutableStateOf<List<Named>>(emptyList()) }
  var projects by remember { mutableStateOf<List<Named>>(emptyList()) }
  var tick by remember { mutableStateOf(0) }
  // Projects and Sites, each a page of Jobs.
  var page by remember { mutableStateOf("") }
  // Read again each time the list is back in front: a project or specialty made in Projects or Memory is offered at once.
  LaunchedEffect(page) {
    if (page.isNotEmpty()) return@LaunchedEffect
    val m = me ?: runCatching { app.hub.me() }.getOrNull()
    me = m
    if (m?.can("memory.read") == true) {
      specialties = runCatching { app.hub.specialtyNames() }.getOrDefault(emptyList())
      projects = runCatching { app.hub.projectNames() }.getOrDefault(emptyList())
    }
  }
  LaunchedEffect(open) { if (open != null) runCatching { app.hub.job(open) }.onSuccess { opened = it } }
  Every(tick, ms = { if (jobs.any { it.status == "running" && it.waiting == null }) 5_000L else 20_000L }) {
    runCatching { app.hub.jobs() }.onSuccess { jobs = it; note = "" }.onFailure { note = it.message ?: "Couldn't read jobs" }
    true
  }
  when (page) {
    "projects" -> return ProjectsScreen(app, back = { page = ""; tick++ })
    "sites" -> return SitesScreen(app, back = { page = "" })
    "memory" -> return MemoryScreen(app, back = { page = ""; tick++ })
  }
  val o = opened
  if (o != null) {
    JobPage(app, o, back = { opened = null; tick++ }, again = { again = it; opened = null }, me = me, projects = projects)
    return
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Jobs", back) }
    if (me?.can("memory.read") == true || me?.workshop == true) {
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (me?.can("memory.read") == true) SafePill("Projects") { page = "projects" }
          // Specialties, which jobs follow, and Which is true now?, of what is saved.
          if (me?.can("memory.read") == true) SafePill("Memory") { page = "memory" }
          if (me?.workshop == true) SafePill("Sites") { page = "sites" }
        }
      }
    }
    item { JobForm(app, me, specialties, projects, again) { again = null; tick++ } }
    if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    items(jobs, key = { it.id }) { j ->
      Tile(Modifier.fillMaxWidth(), onClick = { scope.launch { opened = runCatching { app.hub.job(j.id) }.getOrDefault(j) } }) {
        Column {
          Text(j.title.ifEmpty { j.task }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
          val now = if (j.status == "running" && j.engine == "jarvis" && j.waiting == null) " · ${JobWords.nowDoing(j)}" else ""
          Text(JobWords.status(j) + now + (j.summary?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (j.asks.isNotEmpty()) c.ink else c.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
          JobWords.progress(j.todo)?.takeIf { j.status == "running" }?.let { Text("Its list: $it", style = MaterialTheme.typography.labelSmall, color = c.dim) }
        }
      }
    }
  }
}
