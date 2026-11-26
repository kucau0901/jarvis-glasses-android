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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import app.jarvis.glasses.chat.Asks
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.JobAsk
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.TELL_MAX
import app.jarvis.glasses.hub.answerJob
import app.jarvis.glasses.hub.skipQuestion
import kotlinx.coroutines.launch

/**
 * A question a background job put to its person, as the web app's card (src/app/ui/JobAsk.ts there):
 * its choices to tap, a box for other words, and Go on without me, each sent to the job as it is
 * (`/api/v1/jobs/tell`). Refused (answered elsewhere meanwhile, or its job over), the card says what
 * came of it. [tag] goes above the question where it is not plain whose it is. Text only: the
 * question may come from a page the job read. [answered] once it has been.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskCard(hub: Hub, job: String, q: JobAsk, tag: String? = null, asks: Asks? = null, answered: () -> Unit = {}) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var typed by rememberSaveable(job, q.id) { mutableStateOf("") }
  var said by remember(job, q.id) { mutableStateOf<String?>(null) }
  // Answered anywhere this phone has seen (another card, its notification, the G2): said here too.
  val elsewhere = asks?.settled?.collectAsState()?.value?.get(Asks.key(job, q.id))
  var problem by remember(job, q.id) { mutableStateOf("") }
  var busy by remember(job, q.id) { mutableStateOf(false) }

  fun send(text: String?) {
    busy = true
    problem = ""
    scope.launch {
      runCatching { if (text == null) hub.skipQuestion(job, q.id) else hub.answerJob(job, q.id, text) }
          .onSuccess {
            val now = if (text == null) "You chose to go on without an answer." else "You answered: $text"
            said = now
            asks?.settle(job, q.id, now)
            typed = ""
            answered()
          }
          .onFailure { e ->
            // Answered elsewhere meanwhile, or its job has ended: as it stands now. Else why, to try again.
            val came = runCatching { hub.job(job) }.getOrNull()?.let { j -> asks?.seen(j); j.asked.firstOrNull { it.id == q.id }?.let { JobWords.cameOf(it, j) } }
            if (came != null) said = came else problem = e.message ?: "Not sent"
          }
      busy = false
    }
  }

  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      tag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.dim) }
      Text(q.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
      val done = said ?: elsewhere
      if (done != null) {
        Text(done, style = MaterialTheme.typography.bodySmall, color = c.dim)
      } else {
        if (q.choices.isNotEmpty()) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (choice in q.choices) SafePill(choice, enabled = !busy) { send(choice) }
          }
        }
        OutlinedTextField(typed, { typed = it.take(TELL_MAX) }, label = { Text(if (q.choices.isEmpty()) "Your answer" else "Or in your own words") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(enabled = typed.isNotBlank() && !busy, onClick = { send(typed.trim()) }) { Text("Send") }
          OutlinedButton(enabled = !busy, onClick = { send(null) }) { Text("Go on without me") }
        }
      }
      if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
    }
  }
}
