package app.jarvis.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.jarvis.glasses.ui.DarkEven
import app.jarvis.glasses.ui.DotMatrix
import app.jarvis.link.Approval
import app.jarvis.link.Button
import app.jarvis.link.Member
import app.jarvis.link.Dial
import app.jarvis.link.Thing
import kotlin.math.abs
import kotlinx.coroutines.launch

// The wrist's screens beyond talking: Home's things, one thing with the bezel as its dial, and the
// approvals waiting. Each fetches through the phone as it opens, showing what the watch kept
// meanwhile; the phone's word on what went wrong shows in place.

/** Home's things, as last fetched: a tap opens one. */
@Composable
fun HomeScreen(wrist: Wrist, onThing: (String) -> Unit) {
  val things by wrist.things.collectAsState()
  val pinned by wrist.pinned.collectAsState()
  val (done, trouble) = fetched { wrist.fetchHome() }
  Page("HOME") {
    if (things.isEmpty() && trouble.value == null) item { Line(if (done.value) "Nothing on Home the watch can show" else "Asking the phone…", c.dim, 13) }
    items(things, key = { it.id }) { t -> ThingRow(t, t.id in pinned) { onThing(t.id) } }
    trouble.value?.let { item { Line(it, c.danger, 12) } }
  }
}

/**
 * One thing: its state, the bezel turning its [Dial] (a level, a set point) and Set sending it, its
 * buttons, a room's things each with theirs, and whether it is on the Tile. What Jarvis says of a
 * press shows under it.
 */
@Composable
fun ThingScreen(wrist: Wrist, id: String) {
  val things by wrist.things.collectAsState()
  val pinned by wrist.pinned.collectAsState()
  val thing = things.firstOrNull { it.id == id }
  // As it is now: opened from the Tile or the watch face, what was kept may be hours old.
  fetched { wrist.fetchHome() }
  val scope = rememberCoroutineScope()
  var said by remember { mutableStateOf<Wrist.Outcome?>(null) }
  var busy by remember { mutableStateOf(false) }
  fun act(t: Thing, action: String, member: String? = null) {
    if (busy) return
    busy = true
    said = null
    scope.launch {
      said = wrist.act(t, action, member)
      busy = false
    }
  }
  val dial = thing?.dial
  Page(thing?.label?.uppercase()?.take(10) ?: "THING", rotary = dial == null) {
    if (thing == null) {
      item { Line("Not on Home any more", c.dim, 13) }
      return@Page
    }
    if (thing.status.isNotBlank()) item { Line(thing.status, if (thing.active) c.on else c.dim, 14) }
    if (dial != null) item { DialControl(dial, busy) { v -> act(thing, dial.actionFor(v)) } }
    items(thing.buttons) { b -> Chip(if (busy) "${b.label}…" else b.label, c.ink, c.tile) { act(thing, b.action) } }
    items(thing.members, key = { "m:" + it.key + "/" + it.label }) { m -> MemberRow(m, busy) { b -> act(thing, b.action, m.key) } }
    if (thing.moreMembers > 0) item { Line("${thing.moreMembers} more in the phone app", c.dim, 11) }
    said?.let { o -> if (o.text.isNotBlank()) item { Line(o.text, if (o.ok) c.dim else c.danger, 12) } }
    val on = thing.id in pinned
    item { Chip(if (on) "On the Tile · remove" else "Put on the Tile", c.dim, c.tile) { wrist.pin(thing, !on) } }
  }
}

/** The approvals waiting: what Jarvis would do and why, its word for doing it, and No. */
@Composable
fun ApprovalsScreen(wrist: Wrist) {
  val open by wrist.approvals.collectAsState()
  val (done, trouble) = fetched { wrist.fetchApprovals() }
  val scope = rememberCoroutineScope()
  var answering by remember { mutableStateOf<String?>(null) }
  var failed by remember { mutableStateOf<String?>(null) }
  fun answer(a: Approval, yes: Boolean) {
    if (answering != null) return
    answering = a.id
    failed = null
    scope.launch {
      failed = wrist.approve(a, yes)
      answering = null
    }
  }
  Page("APPROVALS") {
    if (open.isEmpty() && trouble.value == null) item { Line(if (done.value) "Nothing waiting" else "Asking the phone…", c.dim, 13) }
    items(open, key = { it.id }) { a -> ApprovalCard(a, busy = answering == a.id, onYes = { answer(a, true) }, onNo = { answer(a, false) }) }
    (failed ?: trouble.value)?.let { item { Line(it, c.danger, 12) } }
  }
}

private val c = DarkEven

/** [fetch] run once as the screen opens: whether it has finished, and its trouble, or null. */
@Composable
private fun fetched(fetch: suspend () -> String?): Pair<androidx.compose.runtime.State<Boolean>, androidx.compose.runtime.State<String?>> {
  val done = remember { mutableStateOf(false) }
  val trouble = remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    trouble.value = fetch()
    done.value = true
  }
  return done to trouble
}

/** A screen of the wrist's: its name in the panel's dots, then its rows, scrolled by the bezel unless [rotary] is the screen's own. */
@Composable
private fun Page(title: String, rotary: Boolean = true, content: ScalingLazyListScope.() -> Unit) {
  val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
  MaterialTheme {
    ScreenScaffold(scrollState = list) { padding ->
      ScalingLazyColumn(
          state = list,
          contentPadding = padding,
          modifier = Modifier.fillMaxSize().background(Color.Black),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp),
          rotaryScrollableBehavior = if (rotary) androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults.behavior(list) else null,
      ) {
        item { DotMatrix(title.filter { it.isLetterOrDigit() || it == ' ' }, color = c.hudInk, dot = 1.3.dp, ghost = c.hud) }
        content()
      }
    }
  }
}

@Composable
private fun Line(text: String, color: Color, size: Int) {
  Text(text, color = color, fontSize = size.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
}

/** A thing in Home's list: a green dot when on, its name, its state, and a mark when it is on the Tile. */
@Composable
private fun ThingRow(t: Thing, pinned: Boolean, onClick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(50)).background(c.tile).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(if (t.active) c.on else c.line))
    Spacer(Modifier.width(8.dp))
    Column(Modifier.weight(1f)) {
      Text(t.label, color = c.ink, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      val line = listOfNotNull(t.status.takeIf { it.isNotBlank() }, (t.members.size + t.moreMembers).takeIf { it > 0 }?.let { "$it inside" }).joinToString(" · ")
      if (line.isNotEmpty()) Text(line, color = c.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (pinned) Text("Tile", color = c.dim, fontSize = 10.sp)
  }
}

/**
 * The bezel as a dial: each click a step, − and + for a finger, and Set to send the number, so
 * turning past a few steps sends only where it stopped.
 */
@Composable
private fun DialControl(dial: Dial, busy: Boolean, onSet: (Double) -> Unit) {
  var v by remember(dial) { mutableDoubleStateOf(dial.value) }
  var turned by remember { mutableFloatStateOf(0f) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  Column(
      Modifier.fillMaxWidth()
          .onRotaryScrollEvent { e ->
            // A click of the bezel is about this far; a finger on the edge sends smaller ones.
            turned += e.verticalScrollPixels
            while (abs(turned) >= ROTARY_STEP) {
              val by = if (turned > 0) 1 else -1
              v = dial.stepped(v, by)
              turned -= by * ROTARY_STEP
            }
            true
          }
          .focusRequester(focus)
          .focusable(),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(dial.name, color = c.dim, fontSize = 11.sp)
    Row(verticalAlignment = Alignment.CenterVertically) {
      Round("−") { v = dial.stepped(v, -1) }
      Text(dial.words(v) + dial.unit, color = c.ink, fontSize = 30.sp, modifier = Modifier.padding(horizontal = 12.dp))
      Round("+") { v = dial.stepped(v, 1) }
    }
    val changed = v != dial.value
    Chip(if (busy) "Setting…" else "Set ${dial.words(v)}${dial.unit}", if (changed) c.ink else c.dim, c.tile) { if (changed) onSet(v) }
  }
}

/** One of a room's things: a green dot when on, its name and state, then its buttons, as the phone's sheet has them. */
@Composable
private fun MemberRow(m: Member, busy: Boolean, onPress: (Button) -> Unit) {
  Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.tile).padding(horizontal = 14.dp, vertical = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.size(7.dp).clip(CircleShape).background(if (m.active) c.on else c.line))
      Spacer(Modifier.width(8.dp))
      Text(m.label, color = c.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      if (m.status.isNotBlank()) Text(m.status, color = c.dim, fontSize = 11.sp, maxLines = 1)
    }
    if (m.buttons.isNotEmpty()) {
      Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in m.buttons) Pill(if (busy) "…" else b.label, c.ink) { if (!busy) onPress(b) }
      }
    }
  }
}

@Composable
private fun Round(label: String, onClick: () -> Unit) {
  Box(Modifier.size(36.dp).clip(CircleShape).background(c.tile).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Text(label, color = c.ink, fontSize = 18.sp)
  }
}

@Composable
private fun ApprovalCard(a: Approval, busy: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
  Column(
      Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.tile).padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(a.says, color = c.ink, fontSize = 14.sp, textAlign = TextAlign.Center)
    if (a.why.isNotBlank()) Text(a.why, color = c.dim, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Pill(if (busy) "…" else a.verb, c.tintGood, onYes)
      Pill("No", c.danger, onNo)
    }
  }
}

@Composable
private fun Pill(label: String, ink: Color, onClick: () -> Unit) {
  Box(Modifier.clip(RoundedCornerShape(50)).background(Color.Black).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
    Text(label, color = ink, fontSize = 13.sp, maxLines = 1)
  }
}

/** Scroll pixels to one step of a dial: about a click of the Galaxy Watch's bezel. */
private const val ROTARY_STEP = 40f
