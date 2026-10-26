package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.MaterialTheme
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
  LaunchedEffect(said.size, busy) { if (said.isNotEmpty()) list.animateScrollToItem(said.size + 1) } // + the heading
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

  SkyBehind()
  val h = Hark.colors
  Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = if (photo != null) 128.dp else 84.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item { Heading("Chat") }
      if (said.isEmpty()) item { Text("Ask Jarvis anything. Answers spoken in the glasses show up here too when you're signed in.", style = MaterialTheme.typography.bodyMedium, color = h.dim) }
      items(said, key = { it.id }) { s -> SaidRow(s) }
      items(drafts, key = { it.id }) { d ->
        DraftCard(d, onSend = { scope.launch { note = runCatching { app.client.sendDraft(d.id) }.fold({ "Sent." }, { it.message ?: "Not sent" }); drafts.remove(d) } }, onDrop = { drafts.remove(d) })
      }
      if (busy.isNotEmpty()) item { WorkingPill(busy) }
      if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = h.dim) }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (photo != null) HarkButton("Photo added · remove", solid = false) { photo = null }
      MessagePill(
          text,
          { text = it },
          placeholder = "Message Jarvis",
          canSend = busy.isEmpty(),
          onSend = ::ask,
          lead = Icons.Outlined.Add to { hooks.pickPhoto { photo = it } },
          leadOn = photo != null,
          mic = hooks::talk,
      )
    }
  }
}

/** One turn: the bubble, then any links as cards, and the job or routine it came from. */
@Composable
private fun SaidRow(s: Said) {
  val h = Hark.colors
  val mine = s.role == "user"
  HarkBubble(mine) {
    Text(s.text, style = MaterialTheme.typography.bodyLarge, color = h.ink)
    (s.job ?: s.routine)?.let { Text(it.name, style = MaterialTheme.typography.labelSmall, color = h.dim) }
  }
  s.links?.forEach { l -> Box(Modifier.padding(top = 6.dp)) { LinkCard(l) } }
}

@Composable
private fun DraftCard(d: Draft, onSend: () -> Unit, onDrop: () -> Unit) {
  val h = Hark.colors
  Column(Modifier.fillMaxWidth().clip(Hark.bubble).background(h.bubble).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Email to ${d.to}", style = MaterialTheme.typography.labelLarge, color = h.dim)
    Text(d.subject, style = MaterialTheme.typography.bodyLarge, color = h.ink)
    Text(d.body, style = MaterialTheme.typography.bodyMedium, color = h.dim, maxLines = 6)
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      HarkButton("Send", solid = true, onClick = onSend)
      HarkButton("Not now", solid = false, onClick = onDrop)
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

