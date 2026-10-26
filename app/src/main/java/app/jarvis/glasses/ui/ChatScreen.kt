package app.jarvis.glasses.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Link
import app.jarvis.glasses.hub.Said
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** An email Jarvis wrote and holds for a tap (a `draft` event). */
private data class Draft(val id: String, val to: String, val subject: String, val body: String)

/** Typing to Jarvis, as the web app's Chat tab: the same history when signed in as a person. */
@Composable
fun ChatScreen(app: JarvisApp, hooks: Hooks) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val said = remember { mutableStateListOf<Said>() }
  val drafts = remember { mutableStateListOf<Draft>() }
  var text by remember { mutableStateOf("") }
  var photo by remember { mutableStateOf<ByteArray?>(null) }
  var busy by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }
  val list = rememberLazyListState()
  val person = app.prefs.token.startsWith("jss1_")

  LaunchedEffect(person) {
    if (person) runCatching { app.hub.conversation() }.onSuccess { said.clear(); said.addAll(it) }.onFailure { note = it.message ?: "" }
  }
  LaunchedEffect(said.size, busy) { if (said.isNotEmpty()) list.animateScrollToItem(said.size) }
  // The keyboard shortens the list: keep its bottom where it was, as chat apps do.
  LaunchedEffect(list) {
    var was = 0
    snapshotFlow { list.layoutInfo.viewportSize.height }.collect { h ->
      if (h < was) list.scrollBy((was - h).toFloat())
      was = h
    }
  }

  fun ask() {
    val q = text.trim()
    if (q.isEmpty() || busy.isNotEmpty()) return
    val shot = photo
    text = ""
    photo = null
    val mine = Said(newId(), System.currentTimeMillis(), "user", q)
    said.add(mine)
    busy = "…"
    scope.launch {
      var answer: Said? = null
      runCatching {
            app.client.chat(Hub.transcript(said, System.currentTimeMillis()), shot) { ev ->
              when (ev.str("type")) {
                "progress" -> busy = ev.str("text")?.let { "$it…" } ?: busy
                "draft" -> drafts.add(Draft(ev.str("draft").orEmpty(), ev.str("to").orEmpty(), ev.str("subject").orEmpty(), ev.str("body").orEmpty()))
                "result", "error" -> answer = Said(newId(), System.currentTimeMillis(), "assistant", ev.str("text").orEmpty(), links(ev))
              }
            }
          }
          .onFailure { answer = Said(newId(), System.currentTimeMillis(), "assistant", it.message ?: "Jarvis couldn't be reached.") }
      busy = ""
      answer?.let { a ->
        said.add(a)
        if (person) runCatching { app.hub.keep(listOf(mine, a)) }
      }
    }
  }

  Column(Modifier.fillMaxSize()) {
    Box(Modifier.padding(horizontal = 12.dp)) { Heading("Chat") }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (said.isEmpty()) item { Text("Ask Jarvis anything. Answers spoken in the glasses show up here too when you're signed in.", style = MaterialTheme.typography.bodyMedium, color = c.dim) }
      items(said, key = { it.id }) { s -> Bubble(s) }
      items(drafts, key = { it.id }) { d ->
        DraftCard(d, onSend = { scope.launch { note = runCatching { app.client.sendDraft(d.id) }.fold({ "Sent." }, { it.message ?: "Not sent" }); drafts.remove(d) } }, onDrop = { drafts.remove(d) })
      }
      if (busy.isNotEmpty()) item { Text(busy, style = MaterialTheme.typography.bodySmall, color = c.dim) }
      if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim) }
    }
    Row(Modifier.fillMaxWidth().background(c.tile).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = { hooks.pickPhoto { photo = it } }) {
        Icon(Glyphs.Camera, contentDescription = "Add a photo", tint = if (photo != null) c.on else c.dim)
      }
      OutlinedTextField(text, { text = it }, placeholder = { Text("Message Jarvis") }, modifier = Modifier.weight(1f), maxLines = 4)
      IconButton(onClick = ::ask, enabled = text.isNotBlank() && busy.isEmpty()) { Icon(Glyphs.Send, contentDescription = "Send", tint = c.ink) }
    }
  }
}

@Composable
private fun Bubble(s: Said) {
  val c = Even.colors
  val ctx = LocalContext.current
  val mine = s.role == "user"
  Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
    Column(
        Modifier.widthIn(max = 320.dp).clip(Even.corner).background(if (mine) c.chip else c.tile).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(s.text, style = MaterialTheme.typography.bodyMedium)
      s.links?.forEach { l ->
        Text(
            l.title.ifEmpty { l.url },
            style = MaterialTheme.typography.bodySmall,
            color = c.dim,
            modifier = Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url))) },
        )
      }
      (s.job ?: s.routine)?.let { Text(it.name, style = MaterialTheme.typography.labelSmall, color = c.dim) }
    }
  }
}

@Composable
private fun DraftCard(d: Draft, onSend: () -> Unit, onDrop: () -> Unit) {
  val c = Even.colors
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Email to ${d.to}", style = MaterialTheme.typography.labelLarge)
      Text(d.subject, style = MaterialTheme.typography.bodyMedium)
      Text(d.body, style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 6)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onSend) { Text("Send") }
        OutlinedButton(onClick = onDrop) { Text("Not now") }
      }
    }
  }
}

private fun newId() = "a" + UUID.randomUUID().toString().replace("-", "").take(20)

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

private fun links(ev: JsonObject): List<Link>? =
    (ev["links"] as? JsonArray)?.mapNotNull { e ->
      val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
      val url = o.str("url") ?: return@mapNotNull null
      Link(url, o.str("title").orEmpty())
    }?.takeIf { it.isNotEmpty() }

