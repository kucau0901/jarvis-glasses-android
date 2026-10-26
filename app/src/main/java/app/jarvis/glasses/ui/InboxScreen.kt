package app.jarvis.glasses.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.ChatMessage
import app.jarvis.glasses.hub.Convo
import app.jarvis.glasses.hub.Proposals
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What waits for the person: Jarvis's proposals needing an OK, and family messages. */
@Composable
fun InboxScreen(app: JarvisApp) {
  var convo by remember { mutableStateOf<Convo?>(null) }
  val open = convo
  if (open != null) ConversationPage(app, open) { convo = null } else InboxMain(app) { convo = it }
}

@Composable
private fun InboxMain(app: JarvisApp, open: (Convo) -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var proposals by remember { mutableStateOf(Proposals()) }
  var convos by remember { mutableStateOf<List<Convo>>(emptyList()) }
  var note by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) {
    while (true) {
      runCatching { app.hub.proposals() }.onSuccess { proposals = it; note = "" }.onFailure { note = it.message ?: "" }
      runCatching { app.hub.chats() }.onSuccess { convos = it.convos }
      delay(30_000)
    }
  }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Heading("Inbox") }
    if (note.isNotEmpty()) item { Tile(Modifier.fillMaxWidth()) { Text(note, style = MaterialTheme.typography.bodyMedium, color = c.dim) } }
    if (proposals.open.isNotEmpty()) item { Text("Waiting for your OK", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
    items(proposals.open, key = { it.id }) { p ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          if (p.risk == "irreversible") Text("Can't be undone", style = MaterialTheme.typography.labelSmall, color = c.danger)
          Text(p.says, style = MaterialTheme.typography.bodyLarge)
          p.body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 8, overflow = TextOverflow.Ellipsis) }
          if (p.why.isNotEmpty()) Text(p.why, style = MaterialTheme.typography.bodySmall, color = c.dim)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scope.launch { runCatching { app.hub.answer(p.id, "approve") }; tick++ } }) { Text(p.verb ?: "Approve") }
            OutlinedButton(onClick = { scope.launch { runCatching { app.hub.answer(p.id, "decline") }; tick++ } }) {
              Text(if (p.about != null) "Don't suggest this again" else "Not now")
            }
          }
        }
      }
    }
    if (proposals.later.isNotEmpty()) item { Text("Later", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
    items(proposals.later, key = { it.id }) { l ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(l.`when`, style = MaterialTheme.typography.labelSmall, color = c.dim)
          Text(l.says, style = MaterialTheme.typography.bodyMedium)
          OutlinedButton(onClick = { scope.launch { runCatching { app.hub.answer(l.id, "decline") }; tick++ } }) { Text("Don't do this") }
        }
      }
    }
    if (convos.isNotEmpty()) item { Text("Messages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
    items(convos, key = { it.id }) { cv ->
      Tile(Modifier.fillMaxWidth(), onClick = { open(cv) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(cv.title, style = MaterialTheme.typography.bodyLarge)
            cv.last?.let { Text("${it.name}: ${it.text}", style = MaterialTheme.typography.bodySmall, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
          }
          if (cv.unread > 0) Text("${cv.unread}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(Even.corner).background(c.chip).padding(horizontal = 8.dp, vertical = 2.dp))
        }
      }
    }
    if (note.isEmpty() && proposals.open.isEmpty() && proposals.later.isEmpty() && convos.isEmpty()) {
      item { Text("Nothing waiting.", style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(4.dp)) }
    }
  }
}

/** One family conversation: polled every 4 s while open, as the web app does. */
@Composable
private fun ConversationPage(app: JarvisApp, convo: Convo, back: () -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val messages = remember { mutableStateListOf<ChatMessage>() }
  var you by remember { mutableStateOf("") }
  var text by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }
  val list = rememberLazyListState()
  LaunchedEffect(convo.id) {
    runCatching { app.hub.chats() }.onSuccess { you = it.you }
    while (true) {
      val since = messages.lastOrNull()?.at ?: 0L
      runCatching { app.hub.messages(convo.id, since) }.onSuccess { new -> messages.addAll(new.filter { m -> messages.none { it.id == m.id } }) }.onFailure { note = it.message ?: "" }
      delay(4_000)
    }
  }
  LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size) }
  SkyBehind()
  val h = Hark.colors
  Box(Modifier.fillMaxSize()) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = list,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 84.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
          IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = h.ink) }
          Text(convo.title, style = MaterialTheme.typography.headlineSmall, color = h.ink)
        }
      }
      items(messages, key = { it.id }) { m ->
        val mine = m.from == you
        HarkBubble(mine) {
          if (!mine) Text(m.name, style = MaterialTheme.typography.labelSmall, color = h.dim)
          Text(m.text, style = MaterialTheme.typography.bodyLarge, color = h.ink)
        }
      }
      if (note.isNotEmpty()) item { Text(note, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    }
    MessagePill(
        text,
        { text = it },
        placeholder = "Message",
        canSend = true,
        onSend = {
          val t = text.trim()
          text = ""
          scope.launch { runCatching { app.hub.send(convo.id, t) }.onSuccess { m -> if (messages.none { it.id == m.id }) messages.add(m) }.onFailure { note = it.message ?: "" } }
        },
        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

