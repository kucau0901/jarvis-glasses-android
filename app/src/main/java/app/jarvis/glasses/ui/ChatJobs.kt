package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.chat.Asks
import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobAsk
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.hub.jobFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A background job this question started, as the web app's pill (src/app/fromRoutine.ts there):
 * "On it" and its title, then what it is doing and how far down its list, looked at again every few
 * seconds while Chat is open; pressed, it opens in Jobs. Once its result is in the conversation the
 * result's own row stands in its place ([gone]).
 */
@Composable
fun JobPill(app: JarvisApp, started: Named, gone: Boolean, open: (String) -> Unit) {
  if (gone) return
  val h = Hark.colors
  var j by remember(started.id) { mutableStateOf<Job?>(null) }
  var misses by remember(started.id) { mutableStateOf(0) }
  Every(started.id, ms = { if (j?.waiting != null) 20_000L else 4_000L }) {
    runCatching { app.hub.job(started.id) }.onSuccess { j = it; misses = 0; app.asks.seen(it) }.onFailure { misses++ }
    // A screen that may not see the job (a device's sign-in) stops looking.
    misses < 3 && j?.status != "done" && j?.status != "failed" && j?.status != "cancelled"
  }
  val now = j
  val words =
      when {
        now == null -> "working"
        now.status != "running" -> JobWords.status(now)
        now.engine == "jarvis" -> JobWords.nowDoing(now) + (JobWords.progress(now.todo)?.let { " · $it" } ?: "")
        else -> "working"
      }
  Row(
      Modifier.clip(CircleShape).background(h.solid).clickable { open(started.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(Glyphs.Jobs, contentDescription = null, tint = h.onSolid, modifier = Modifier.size(16.dp))
    Column(Modifier.padding(start = 8.dp)) {
      Text((if (now == null || now.status == "running") "On it — " else "") + started.name, style = MaterialTheme.typography.bodyMedium, color = h.onSolid, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(words, style = MaterialTheme.typography.labelSmall, color = h.onSolid, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

/**
 * What goes under a row Jarvis said about a job: a question's card, to answer it there, which looks
 * at its job now and then until it is settled; a result's files, to save; and what it proposed, as
 * cards. Nothing for any other row. Signed in as a person only: the routes are a person's.
 */
@Composable
fun SaidExtras(app: JarvisApp, s: Said, person: Boolean) {
  if (!person || s.role != "assistant") return
  val ask = s.ask
  if (ask != null) {
    val (question, choices) = JobWords.askOf(s.text)
    val settled = app.asks.settled.value[Asks.key(ask.job, ask.id)]
    // Answered elsewhere meanwhile (another screen, in words): looked at, soon while it is new, then less often.
    Every(ask.job, ask.id, ms = { if (System.currentTimeMillis() - s.at < 10 * 60_000) 8_000L else 60_000L }) {
      runCatching { app.hub.job(ask.job) }.onSuccess { app.asks.seen(it) }
      app.asks.said(ask.job, ask.id) == null && settled == null
    }
    Column(Modifier.padding(top = 6.dp)) { AskCard(app.hub, ask.job, JobAsk(ask.id, s.at, question, choices), asks = app.asks) }
  }
  val job = s.job
  if (job != null && s.made.isNotEmpty()) MadeFiles(app, job.id, s)
  if (s.proposals.isNotEmpty()) Proposed(app, s.proposals)
}

/** A result's files, each to save. */
@Composable
private fun MadeFiles(app: JarvisApp, job: String, s: Said) {
  val c = Even.colors
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var note by remember(s.id) { mutableStateOf("") }
  Tile(Modifier.fillMaxWidth().padding(top = 6.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (f in s.made) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text("${f.name} (${JobWords.sizeSaid(f.size)})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
          HarkButton("Save", solid = false) {
            scope.launch {
              note =
                  runCatching {
                        val bytes = app.hub.jobFile(job, f.n)
                        withContext(Dispatchers.IO) { saveDownload(context, f.name, bytes) }
                      }
                      .getOrElse { it.message ?: "Not saved" }
            }
          }
        }
      }
      if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = c.dim)
    }
  }
}

/** What a job proposed, by id, as its cards stand now: one read of what waits and was decided. */
@Composable
private fun Proposed(app: JarvisApp, ids: List<String>) {
  var cards by remember(ids) { mutableStateOf<List<Proposal>>(emptyList()) }
  var allowed by remember(ids) { mutableStateOf<List<String>>(emptyList()) }
  var tick by remember(ids) { mutableStateOf(0) }
  androidx.compose.runtime.LaunchedEffect(ids, tick) {
    runCatching { app.hub.proposals() }.onSuccess { r ->
      val all = (r.open + r.recent).associateBy { it.id }
      cards = ids.mapNotNull { all[it] }
      allowed = r.allowed
    }
  }
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (p in cards) ProposalCard(app.hub, p, allowed) { tick++ }
  }
}

/**
 * Home's Now strip, as the web's (src/app/ui/Now.ts there): what jobs ask, then what waits for an
 * OK, five at most, above the panels; hidden when nothing waits. Read again every half minute while
 * Home is in front. A person's only.
 */
@Composable
fun rememberNow(app: JarvisApp): Now {
  val now = remember { Now() }
  Every(now.tick, ms = { 30_000L }) {
    runCatching { app.hub.proposals() }.onSuccess { r -> now.asking = r.asking; now.open = r.open; now.allowed = r.allowed }
    true
  }
  return now
}

/** What Home's Now strip shows: read by [rememberNow], drawn by [NowStrip] only when anything waits, so an empty one takes no room. */
class Now {
  var asking by mutableStateOf<List<app.jarvis.glasses.hub.Asking>>(emptyList())
  var open by mutableStateOf<List<Proposal>>(emptyList())
  var allowed by mutableStateOf<List<String>>(emptyList())
  var tick by mutableStateOf(0)

  val total: Int
    get() = asking.size + open.size
}

@Composable
fun NowStrip(app: JarvisApp, now: Now) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Now", style = MaterialTheme.typography.titleMedium)
    now.asking.take(MOST).forEach { a -> AskCard(app.hub, a.job, JobAsk(a.id, a.at, a.text, a.choices), tag = "${a.title} asks you", asks = app.asks) { now.tick++ } }
    now.open.take((MOST - now.asking.size).coerceAtLeast(0)).forEach { p -> ProposalCard(app.hub, p, now.allowed) { now.tick++ } }
    if (now.total > MOST) Text("${now.total - MOST} more in the Inbox", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
  }
}

/** As many as Now shows. */
private const val MOST = 5
