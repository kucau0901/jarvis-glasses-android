package app.jarvis.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
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
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.allow
import app.jarvis.glasses.hub.canAllow
import app.jarvis.glasses.hub.canPutBack
import app.jarvis.glasses.hub.hoursLeft
import app.jarvis.glasses.hub.stateWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a background job or a brief proposed, as a card, the web app's (src/app/proposal.ts there):
 * the call in words, as Jarvis's code wrote them, and everything it would send or change, whole and
 * never cut, so nothing is approved unread; its verb (Approve without one) and Not now. Once tapped,
 * what came of it: a call still being made is looked at again until it is done. Then Put back, for
 * what can be undone, and letting jobs do that kind without asking. Only a tap here does any of it.
 * [changed]: what is listed may have changed, to be read again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProposalCard(hub: Hub, p: Proposal, allowed: List<String>, changed: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var shown by remember(p.id) { mutableStateOf(p) }
  var busy by remember(p.id) { mutableStateOf(false) }
  var problem by remember(p.id) { mutableStateOf("") }
  var allowedHere by remember(p.id) { mutableStateOf(false) }
  // A learned offer being changed before it is kept.
  var changing by remember(p.id) { mutableStateOf(false) }
  // The list read again: this card as it now stands.
  LaunchedEffect(p) { if (!busy) shown = p }
  // Approved, its call is made by Jarvis meanwhile (a car waking): looked at again, soon and then less often.
  LaunchedEffect(shown.id, shown.state) {
    if (shown.state != "running") return@LaunchedEffect
    var wait = 2_000L
    var misses = 0
    while (misses < 5) {
      delay(wait)
      wait = (wait * 3 / 2).coerceAtMost(15_000L)
      val r = runCatching { hub.proposals() }.getOrNull()
      if (r == null) {
        misses++
        continue
      }
      misses = 0
      val now = r.recent.firstOrNull { it.id == shown.id } ?: break
      if (now.state != "running") {
        shown = now
        changed()
        break
      }
    }
  }

  fun act(f: suspend () -> Proposal) {
    busy = true
    problem = ""
    scope.launch {
      runCatching { f() }.onSuccess { shown = it }.onFailure { problem = it.message ?: "Not done" }
      busy = false
      changed()
    }
  }

  val q = shown
  val now = System.currentTimeMillis()
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val pending = q.state == "pending"
      val undoable = q.risk != "irreversible"
      Text(
          if (pending) "Waiting for your OK" + (if (undoable) "" else " · can't be undone") else "Proposed",
          style = MaterialTheme.typography.labelSmall,
          color = if (pending && !undoable) c.danger else c.dim,
      )
      Text(q.says, style = MaterialTheme.typography.bodyLarge, color = c.ink)
      // Whole: what is approved is what was read. Text only: a job wrote it, from what it read.
      q.body?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall, color = c.ink) } }
      val why = if (pending) listOf(q.why, hoursLeft(q.expires, now)).filter { it.isNotBlank() }.joinToString(" · ") else q.why
      if (why.isNotBlank()) Text(why, style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (!pending) Text(q.stateWords(), style = MaterialTheme.typography.bodySmall, color = if (q.state == "failed") c.danger else c.dim)
      if (q.undone != null) Text("Put back: tapped.", style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (allowedHere) Text(if (q.action?.tool == "house_do") "Jarvis now does this without asking, only when the sensors show it is right." else "Jobs now do this without asking.", style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
      if (changing && pending) WayForm(hub, draftOfCard(q), mayFiles = false) { saved -> changing = false; if (saved) changed() }
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pending) {
          OutlinedButton(enabled = !busy, onClick = { act { hub.answer(q.id, "approve") } }) { Text(q.verb ?: "Approve") }
          // How a job was done, to be kept: changed first in its form, if they like; saved there, this card is settled.
          if (q.action?.tool == "specialty_keep" && !changing) OutlinedButton(enabled = !busy, onClick = { changing = true }) { Text("Change it first") }
          OutlinedButton(enabled = !busy, onClick = { act { hub.answer(q.id, "decline") } }) { Text(if (q.about != null) "Don't suggest this again" else "Not now") }
        } else {
          if (q.canPutBack(now)) {
            OutlinedButton(enabled = !busy, onClick = { act { hub.answer(q.id, "undo").let { back -> q.copy(undone = back.id) } } }) { Text("Put back") }
          }
          if (q.canAllow(allowed) && !allowedHere) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                  busy = true
                  problem = ""
                  scope.launch {
                    runCatching { hub.allow(q.id) }.onSuccess { allowedHere = true }.onFailure { problem = it.message ?: "Not done" }
                    busy = false
                    changed()
                  }
                },
            ) {
              Text(if (q.action?.tool == "house_do") "Let Jarvis do this without asking" else "Let jobs do this without asking")
            }
          }
        }
      }
    }
  }
}
