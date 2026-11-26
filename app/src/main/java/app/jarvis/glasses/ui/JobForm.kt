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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.Me
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.NewJob
import app.jarvis.glasses.hub.startJob
import kotlinx.coroutines.launch

/**
 * The New job form, as the web's (src/app/ui/Jobs.ts there): the brief, where it may look, and
 * what kind of job, each choice offered only to whoever may use it ([me]): thorough and helpers to
 * the family (`routines`), files and building where the workshop is theirs, a repository to an
 * admin, a specialty or a project to whoever has any. Research is the web's and ordinary, and
 * building the web's ([NewJob.body] keeps it so). [again], an ended job's brief to read over and
 * start on the stronger model ("Try again harder"), fills it once; nothing starts until Start.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobForm(app: JarvisApp, me: Me?, specialties: List<Named>, projects: List<Named>, again: NewJob?, started: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var job by remember { mutableStateOf(NewJob("")) }
  var problem by remember { mutableStateOf("") }
  var said by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  LaunchedEffect(again) {
    if (again != null) {
      job = again
      said = "Read it over, then Start: the stronger model, several times the cost."
    }
  }
  val family = me?.can("routines") == true
  val shop = me?.workshop == true
  val admin = me?.role == "admin" || me?.scopes?.contains("*") == true
  val repos = me?.repos.orEmpty()
  val research = job.research != "no"
  val fixedWeb = research || job.kind == "build"

  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("New job", style = MaterialTheme.typography.titleMedium)
      OutlinedTextField(job.task, { job = job.copy(task = it) }, label = { Text("Ask Jarvis to work on…") }, modifier = Modifier.fillMaxWidth())
      if (job.task.length > NewJob.MAX_BRIEF) Text("${job.task.length} of ${NewJob.MAX_BRIEF} characters", style = MaterialTheme.typography.bodySmall, color = c.danger)
      if (job.repo == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SafePill("On the web", selected = fixedWeb || job.reach == "web") { job = job.copy(reach = "web") }
          SafePill("My own things", enabled = !fixedWeb, selected = !fixedWeb && job.reach == "private") { job = job.copy(reach = "private") }
        }
        Text(
            if (fixedWeb || job.reach == "web") "Searches the web, and is told nothing of yours but what you write here."
            else "Reads your own mail, calendar, memory, house, car and cameras, as far as you may. Does not search the web.",
            style = MaterialTheme.typography.bodySmall,
            color = c.dim,
        )
      }
      // What kind: one at a time, as the web's boxes keep each other right.
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SafePill("Ordinary", enabled = !research, selected = !research && job.kind == "ordinary") { job = job.copy(kind = "ordinary", repo = null) }
        if (family) SafePill("Thorough", enabled = !research, selected = !research && job.kind == "thorough") { job = job.copy(kind = "thorough", repo = null) }
        if (shop) SafePill("Files", enabled = !research, selected = !research && job.kind == "files") { job = job.copy(kind = "files", repo = null) }
        if (shop) SafePill("Build", enabled = !research, selected = !research && job.kind == "build") { job = job.copy(kind = "build") }
        if (family) SafePill("With helpers", enabled = !research, selected = !research && job.helpers) { job = job.copy(helpers = !job.helpers) }
      }
      Text(KIND_SAID[if (research) "research" else job.kind].orEmpty() + if (job.helpers && !research) " Splits the work between up to three helpers, who work at the same time; several times the cost." else "", style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (job.kind == "build" && !research && admin && repos.isNotEmpty()) {
        Text("A repository to change, or none for a site", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SafePill("None", selected = job.repo == null) { job = job.copy(repo = null) }
          for (r in repos) SafePill(r, selected = job.repo == r) { job = job.copy(repo = r) }
        }
        if (job.repo != null) Text("Works on ${job.repo}, without the web: a pull request for you to open with a tap.", style = MaterialTheme.typography.bodySmall, color = c.dim)
      }
      if (family) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          SafePill("Research in depth", selected = research) { job = job.copy(research = if (research) "no" else "deep") }
          if (research) SafePill("As a team", selected = job.research == "team" || job.research == "table") { job = job.copy(research = if (job.research == "deep") "team" else "deep") }
          if (research) SafePill("As a table", selected = job.research == "table") { job = job.copy(research = if (job.research == "table") "team" else "table") }
        }
      }
      if (!research && specialties.isNotEmpty()) Choice("Following", specialties, job.specialty) { job = job.copy(specialty = it) }
      if (!research && projects.isNotEmpty()) Choice("In the project", projects, job.project) { job = job.copy(project = it) }
      OutlinedButton(
          enabled = job.task.isNotBlank() && !busy,
          onClick = {
            busy = true
            problem = ""
            said = ""
            scope.launch {
              // Refused (too many at once, the day's allowance spent): why stays, and so do their words.
              runCatching { app.hub.startJob(job) }
                  .onSuccess {
                    job = NewJob("")
                    said = "Started: it comes to you as an alert when it is done."
                    started()
                  }
                  .onFailure { problem = it.message ?: "Jarvis didn't start it" }
              busy = false
            }
          },
      ) {
        Text("Start")
      }
      if (said.isNotEmpty()) Text(said, style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
    }
  }
}

/** One of [items] or none, under [label]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(label: String, items: List<Named>, chosen: String?, choose: (String?) -> Unit) {
  Text(label, style = MaterialTheme.typography.labelLarge)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SafePill("None", selected = chosen == null) { choose(null) }
    for (i in items) SafePill(i.name, selected = chosen == i.id) { choose(i.id) }
  }
}

/** What each kind does, and costs, as the web's form says. */
private val KIND_SAID =
    mapOf(
        "ordinary" to "Most things: a few minutes.",
        "thorough" to "A stronger model, for work with many things to weigh; several times the cost, five a day.",
        "files" to "Makes them with programs, on a machine with no network, for you to save: a spreadsheet, a document, a chart.",
        "build" to "A site, or a change to code, put to you to publish with a tap. Always on the web.",
        "research" to "A stronger model and sources, 10 to 40 minutes, roughly \$1 to 2; as a team, three angles sharing what they find, 20 to 55 minutes, roughly \$4 to 7; as a table, rows to download as CSV.",
    )
