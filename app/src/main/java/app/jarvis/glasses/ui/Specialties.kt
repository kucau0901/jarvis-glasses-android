package app.jarvis.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.Way
import app.jarvis.glasses.hub.WayDraft
import app.jarvis.glasses.hub.WayWhole
import app.jarvis.glasses.hub.exportWay
import app.jarvis.glasses.hub.forgetWay
import app.jarvis.glasses.hub.putBackWay
import app.jarvis.glasses.hub.saveWay
import app.jarvis.glasses.hub.way
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A specialty to write, change, import or keep from a job, read over before it is kept, as the web's
 * form (src/app/ui/Specialties.ts there): its name, when to use it, its steps, notes at length, why,
 * and files, for whoever may use the workshop ([mayFiles]). New files are read before they are kept:
 * what is text is shown whole. One that changes a specialty kept already keeps what that holds
 * besides. Nothing is kept until Save; saved from a learned offer's card ([WayDraft.card]), that card
 * is settled. [done] once it is saved or cancelled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WayForm(hub: Hub, start: WayDraft, mayFiles: Boolean, done: (saved: Boolean) -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var d by remember(start) { mutableStateOf(start) }
  var problem by remember(start) { mutableStateOf("") }
  var busy by remember(start) { mutableStateOf(false) }
  // Changing one kept already: what it holds besides (its notes, its files) stays with it.
  LaunchedEffect(start) {
    val id = start.id ?: return@LaunchedEffect
    if (start.text.isNotEmpty() || start.kept.isNotEmpty()) return@LaunchedEffect
    runCatching { hub.way(id) }.onSuccess { w -> d = d.copy(text = w.text.orEmpty(), kept = w.files.filter { f -> d.added.none { it.name == f.name } }) }
  }
  val pick = rememberFilePicker(200_000) { files, big ->
    d = d.copy(added = d.added + files)
    if (big > 0) problem = "${if (big == 1) "One file is" else "$big files are"} over 200 KB, and not added."
  }
  fun lines(t: String) = t.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(if (d.id != null) "Change it" else "New specialty", style = MaterialTheme.typography.titleMedium)
      for (n in d.notes) Text(n, style = MaterialTheme.typography.bodySmall, color = c.dim)
      OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(d.`when`.joinToString("\n"), { d = d.copy(`when` = lines(it)) }, label = { Text("When to use it, a line each") }, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(d.steps.joinToString("\n"), { d = d.copy(steps = lines(it)) }, label = { Text("Steps, a line each") }, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(d.text, { d = d.copy(text = it) }, label = { Text("Notes: pitfalls, examples (background jobs read these)") }, modifier = Modifier.fillMaxWidth())
      OutlinedTextField(d.why, { d = d.copy(why = it) }, label = { Text("Why, if it helps") }, modifier = Modifier.fillMaxWidth())
      if (mayFiles || d.kept.isNotEmpty() || d.added.isNotEmpty()) {
        Text("Files with it", style = MaterialTheme.typography.labelLarge)
        for (f in d.kept) FileRow("${f.name} (${JobWords.sizeSaid(f.size)})") { d = d.copy(kept = d.kept - f) }
        for (f in d.added) {
          FileRow("${f.name} (${JobWords.sizeSaid(f.bytes.size.toLong())}), new") { d = d.copy(added = d.added - f) }
          // Read before it is kept, whole: background jobs may run it in the workshop.
          val text = f.bytes.takeIf { b -> b.none { it == 0.toByte() } }?.toString(Charsets.UTF_8)
          Text(text ?: "Not text: it cannot be shown here.", style = MaterialTheme.typography.bodySmall, color = c.dim)
        }
        if (mayFiles) OutlinedButton(onClick = pick) { Text("Add a file…") }
      }
      if (problem.isNotEmpty()) Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger)
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            enabled = !busy && d.name.isNotBlank(),
            onClick = {
              busy = true
              problem = ""
              scope.launch {
                runCatching {
                      hub.saveWay(d)
                      // What the card would have kept is kept, as they changed it: the card is settled.
                      d.card?.let { runCatching { hub.answer(it, "decline") } }
                    }
                    .onSuccess { done(true) }
                    .onFailure { problem = it.message ?: "Not saved" }
                busy = false
              }
            },
        ) {
          Text("Save")
        }
        OutlinedButton(onClick = { done(false) }) { Text("Cancel") }
      }
    }
  }
}

@Composable
private fun FileRow(label: String, remove: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    OutlinedButton(onClick = remove) { Text("Remove") }
  }
}

/** A learned offer's card ([Proposal] of `specialty_keep`), as a draft to change before it is kept. */
fun draftOfCard(p: Proposal): WayDraft {
  val a = p.action?.args ?: kotlinx.serialization.json.JsonObject(emptyMap())
  fun list(k: String) = (a[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
  fun str(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull
  val id = str("id")
  // Of one kept already: its steps with what the card would add.
  val steps = if (id != null) list("steps").ifEmpty { list("add") } else list("steps")
  return WayDraft(id = id, name = str("name").orEmpty(), `when` = list("when"), steps = steps, why = str("why").orEmpty(), source = "learned", card = p.id)
}

/**
 * One specialty, whole: when it is used, its steps, notes and files, then what it was before each
 * change, with Put back; Change, Export (a zip other agents take) and Forget.
 */
@Composable
fun WayPage(hub: Hub, w: Way, mayFiles: Boolean, changed: () -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  var whole by remember(w.id, w.updatedAt) { mutableStateOf<WayWhole?>(null) }
  var editing by remember(w.id) { mutableStateOf(false) }
  var note by remember(w.id) { mutableStateOf("") }
  LaunchedEffect(w.id, w.updatedAt) { runCatching { hub.way(w.id) }.onSuccess { whole = it }.onFailure { note = it.message ?: "" } }
  if (editing) {
    WayForm(hub, WayDraft(w.id, w.name, w.`when`, w.steps, w.why.orEmpty(), whole?.text.orEmpty(), kept = whole?.files ?: w.files), mayFiles) { saved ->
      editing = false
      if (saved) changed()
    }
    return
  }
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(w.name, style = MaterialTheme.typography.titleMedium)
      Text(SOURCE[w.source] ?: w.source, style = MaterialTheme.typography.labelSmall, color = c.dim)
      if (w.`when`.isNotEmpty()) Text("When: " + w.`when`.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
      w.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall) }
      whole?.text?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.dim) }
      w.why?.takeIf { it.isNotBlank() }?.let { Text("Why: $it", style = MaterialTheme.typography.bodySmall, color = c.dim) }
      for (f in whole?.files ?: w.files) Text("${f.name} (${JobWords.sizeSaid(f.size)})", style = MaterialTheme.typography.bodySmall, color = c.dim)
      whole?.before?.forEach { b ->
        Text("Until ${JobWords.daySaid(b.at)}: ${b.name}", style = MaterialTheme.typography.labelLarge)
        b.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, color = c.dim) }
        OutlinedButton(onClick = { scope.launch { runCatching { hub.putBackWay(w.id, b.at) }.onSuccess { note = "Put back."; changed() }.onFailure { note = it.message ?: "Not put back" } } }) { Text("Put this back") }
      }
      if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editing = true }) { Text("Change") }
        OutlinedButton(onClick = {
          scope.launch {
            note = runCatching {
                  val bytes = hub.exportWay(w.id)
                  withContext(Dispatchers.IO) { saveDownload(context, "${w.name.replace(Regex("[^\\p{L}\\p{N} -]"), "").trim().ifEmpty { "specialty" }}.zip", bytes) }
                }
                .getOrElse { it.message ?: "Not saved" }
          }
        }) { Text("Export") }
        Confirm("Forget", "Forget it?") { scope.launch { runCatching { hub.forgetWay(w.id) }.onSuccess { changed() }.onFailure { note = it.message ?: "Not forgotten" } } }
      }
    }
  }
}

/** Where one came from, as Memory says it. */
private val SOURCE = mapOf("answer" to "Saved from an answer", "learned" to "Learned from a job", "written" to "Written here", "imported" to "Imported from a file")
