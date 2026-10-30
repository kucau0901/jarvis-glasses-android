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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.jarvis.glasses.chat.Work
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.Link
import app.jarvis.glasses.hub.Said
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** An email Jarvis wrote and holds for a tap (a `draft` event). */
private data class Draft(val id: String, val to: String, val subject: String, val body: String)

/**
 * Typing to Jarvis, as the web app's Chat tab: the one conversation ([app.jarvis.glasses.chat.ChatLog]),
 * which Talk and Live add to as well, and the same as the web app's when signed in as a person.
 */
@Composable
fun ChatScreen(app: JarvisApp, hooks: Hooks) {
  val scope = rememberCoroutineScope()
  val said by app.chat.said.collectAsStateWithLifecycle()
  // Answers that are notices (a limit reached) or failures, by id: drawn apart, never kept.
  val kinds by app.chat.kinds.collectAsStateWithLifecycle()
  val drafts = remember { mutableStateListOf<Draft>() }
  var text by remember { mutableStateOf("") }
  var photo by remember { mutableStateOf<ByteArray?>(null) }
  var note by remember { mutableStateOf("") }
  // The question being answered: Jarvis's work on it, as pills, and whether it has been read.
  var work by remember { mutableStateOf<Work?>(null) }
  var workRev by remember { mutableIntStateOf(0) }
  var read by remember { mutableStateOf<String?>(null) }
  val jobs = remember { mutableStateListOf<Named>() }
  val busy = work != null
  val list = rememberLazyListState()
  val person = app.prefs.token.startsWith("jss1_")

  // Asking twice clears it: once to arm, once to clear (it goes on every screen of the person's).
  var clearing by remember { mutableStateOf(false) }

  LaunchedEffect(person) {
    runCatching { app.chat.refresh() }.onFailure { note = it.message ?: "" }
  }
  LaunchedEffect(said.size, busy, workRev) { if (said.isNotEmpty()) list.animateScrollToItem(said.size + 1) } // + the heading
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
    if (q.isEmpty() || busy) return
    val shot = photo
    text = ""
    photo = null
    val mine = Said(Said.newId(), System.currentTimeMillis(), "user", q)
    app.chat.show(mine)
    jobs.clear()
    val w = Work()
    work = w
    scope.launch {
      var answer: Said? = null
      var kind = ""
      var notice = ""
      runCatching {
            app.client.chat(app.chat.recent(), shot) { ev ->
              // The stream opened: Jarvis has the question ("Read", as Hark's chat says).
              read = mine.id
              when (ev.str("type")) {
                "tool" -> {
                  w.tool(ev.str("name").orEmpty(), ev.str("id"), ev.str("phase").orEmpty())
                  RESEARCH_CAP[ev.str("limit")]?.let { notice = it }
                  workRev++
                }
                "progress" -> {
                  w.note(ev.str("text").orEmpty(), ev.str("id") ?: ev.str("name"))
                  workRev++
                }
                "job" -> ev.str("id")?.let { jobs.add(Named(it, ev.str("title").orEmpty())) }
                "draft" -> drafts.add(Draft(ev.str("draft").orEmpty(), ev.str("to").orEmpty(), ev.str("subject").orEmpty(), ev.str("body").orEmpty()))
                "result" -> answer = Said(Said.newId(), System.currentTimeMillis(), "assistant", ev.str("text").orEmpty(), links(ev))
                "error" -> {
                  answer = Said(Said.newId(), System.currentTimeMillis(), "assistant", ev.str("text").orEmpty())
                  kind = if (ev["limit"] != null) "limit" else "bad"
                }
              }
            }
          }
          .onFailure { answer = Said(Said.newId(), System.currentTimeMillis(), "assistant", "Could not reach Jarvis: ${it.message}"); kind = "bad" }
      work = null
      val a = answer ?: Said(Said.newId(), System.currentTimeMillis(), "assistant", "That ended without an answer. Try again.").also { kind = "bad" }
      // What went wrong is not kept, as the web app does.
      if (kind.isEmpty()) app.chat.keep(listOf(mine, a)) else app.chat.show(a, kind)
      if (notice.isNotEmpty()) app.chat.show(Said(Said.newId(), System.currentTimeMillis(), "assistant", notice), "limit")
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
      item {
        Heading("Chat") {
          if (said.isNotEmpty() && !busy) {
            HarkButton(if (clearing) "Clear everywhere?" else "Clear", solid = clearing) {
              if (!clearing) clearing = true
              else scope.launch {
                clearing = false
                runCatching { app.chat.clear() }.onSuccess { note = ""; drafts.clear(); jobs.clear() }.onFailure { note = it.message ?: "Not cleared" }
              }
            }
          }
        }
      }
      if (said.isEmpty()) item { Text("Ask Jarvis anything. Answers spoken in the glasses show up here too when you're signed in.", style = MaterialTheme.typography.bodyMedium, color = h.dim) }
      items(said, key = { it.id }) { s -> SaidRow(s, kinds[s.id], read = s.id == read) }
      items(jobs, key = { it.id }) { j -> JobStarted(j) }
      items(drafts, key = { it.id }) { d ->
        DraftCard(d, onSend = { scope.launch { note = runCatching { app.client.sendDraft(d.id) }.fold({ "Sent." }, { it.message ?: "Not sent" }); drafts.remove(d) } }, onDrop = { drafts.remove(d) })
      }
      // A new list each time workRev moves, so the row redraws: the same Work object would be skipped.
      work?.let { w -> item(key = "working") { workRev.let { WorkingRow(w.steps, w.thinking) } } }
      if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = h.dim) }
    }
    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (photo != null) HarkButton("Photo added · remove", solid = false) { photo = null }
      MessagePill(
          text,
          { text = it },
          placeholder = "Message Jarvis",
          canSend = !busy,
          onSend = ::ask,
          lead = Icons.Outlined.Add to { hooks.pickPhoto { photo = it } },
          leadOn = photo != null,
          mic = hooks::talk,
      )
    }
  }
}

/**
 * One turn: the bubble, then any links as cards, and the job or routine it came from. Jarvis's
 * answers are drawn as the web app draws them; a limit is a quiet notice, a failure says so.
 */
@Composable
private fun SaidRow(s: Said, kind: String?, read: Boolean) {
  val h = Hark.colors
  val mine = s.role == "user"
  if (kind == "limit") {
    Text(s.text, style = MaterialTheme.typography.bodySmall, color = h.dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
    return
  }
  HarkBubble(mine) {
    when {
      mine -> Text(s.text, style = MaterialTheme.typography.bodyLarge, color = h.ink)
      kind == "bad" -> Text(s.text, style = MaterialTheme.typography.bodyLarge, color = Even.colors.danger)
      else -> MarkdownText(s.text, h.ink, h.dim, h.bubbleYou)
    }
    // A bot's answer or message under its name, with what it was asked, as the web app's pill has it; its question as
    // "Nova asked"; Jarvis's answer to that is Jarvis's own.
    val about = s.skill?.about?.takeIf { it.isNotBlank() }?.let { if (it.length > 40) " · ${it.take(40)}…" else " · $it" }.orEmpty()
    s.from?.let { Text(it + about, style = MaterialTheme.typography.labelSmall, color = h.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
  }
  if (read) Text("Read", style = MaterialTheme.typography.labelSmall, color = h.dim, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 3.dp, end = 6.dp))
  s.links?.forEach { l -> Box(Modifier.padding(top = 6.dp)) { LinkCard(l) } }
}

/** A background job this question started, as the web app shows one: its result comes later, in Jobs. */
@Composable
private fun JobStarted(j: Named) {
  val h = Hark.colors
  Row(
      Modifier.clip(CircleShape).background(h.solid).padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Glyphs.Jobs, contentDescription = null, tint = h.onSolid, modifier = Modifier.size(16.dp))
    Text("  Job started: ${j.name}", style = MaterialTheme.typography.bodyMedium, color = h.onSolid, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** What typed chat says when a research job met the month's cap, or research is off (lib/jobs.ts isResearchCap). */
private val RESEARCH_CAP =
    mapOf(
        "research" to "This month's research jobs are used up: the limit is in Settings → OpenAI.",
        "research-off" to "Research jobs are switched off: Settings → OpenAI → Research jobs a month.",
    )

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


private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

private fun links(ev: JsonObject): List<Link>? =
    (ev["links"] as? JsonArray)?.mapNotNull { e ->
      val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
      val url = o.str("url") ?: return@mapNotNull null
      Link(url, o.str("title").orEmpty())
    }?.takeIf { it.isNotEmpty() }

