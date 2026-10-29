package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.conversation.Conversation as Talk
import app.jarvis.glasses.glasses.shownGlasses
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.hub.Reading
import app.jarvis.glasses.live.LiveVoice
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private enum class HomePage { MAIN, JOBS, ROUTINES }

/** Home: the glasses' display, quick tiles, and the Home panels made in Jarvis. */
@Composable
fun HomeScreen(app: JarvisApp, hooks: Hooks) {
  var page by remember { mutableStateOf(HomePage.MAIN) }
  when (page) {
    HomePage.MAIN -> HomeMain(app, hooks, open = { page = it })
    HomePage.JOBS -> JobsScreen(app, back = { page = HomePage.MAIN })
    HomePage.ROUTINES -> RoutinesScreen(app, back = { page = HomePage.MAIN })
  }
}

/** A press on a thing: its panel, row, action, and the room member, value or alarm code it needs. */
private typealias Press = (Panel, PanelRow, String, String?, String?, String?) -> Unit

/** Near the top or bottom of the page, a held tile scrolls it, faster nearer the edge (HomeWall.ts). */
private val EDGE = 64.dp
private const val EDGE_SPEED_DP = 600f

/** After a swap, the finger moves this far before the next, so tiles don't flicker back and forth. */
private val SWAP_GATE = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeMain(app: JarvisApp, hooks: Hooks, open: (HomePage) -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val haptics = LocalHapticFeedback.current
  val density = LocalDensity.current
  var panels by remember { mutableStateOf<List<Panel>>(emptyList()) }
  var problem by remember { mutableStateOf("") }
  var flash by remember { mutableStateOf("") }
  var refreshTick by remember { mutableIntStateOf(0) }
  // Bumped by every move: a reading of the panels asked for before it is old and is dropped.
  var seq by remember { mutableIntStateOf(0) }
  var sheet by remember { mutableStateOf<String?>(null) }
  var can by remember { mutableStateOf<app.jarvis.glasses.hub.Can?>(null) }
  var loaded by remember { mutableStateOf(false) }
  val known by app.glasses.collectAsStateWithLifecycle()
  val headset by app.audio.headset.collectAsStateWithLifecycle()
  val status by app.conversation.status.collectAsStateWithLifecycle()
  val liveState by app.live.state.collectAsStateWithLifecycle()
  var live by remember { mutableStateOf(app.prefs.live) }
  val grid = rememberLazyGridState()
  val mover = remember { Mover() }
  val saving = remember { Mutex() }
  val page = remember { Place() }
  val held = mover.held
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  // Tile readings by "panel\nkey", kept across re-reads of the panels, moves and tab changes.
  val readings = KeptReadings.readingsOf(app.prefs.token)
  val pending = remember { mutableStateListOf<String>() }
  fun readingOf(panel: Panel, row: PanelRow): Reading? = if (row.read) readings["${panel.id}\n${row.key}"]?.reading else null

  /** Reads [row]'s tile if it is due (HomeReadings.kt): the kept reading, then a fresh one if that is stale. */
  fun read(panel: Panel, row: PanelRow) {
    val at = "${panel.id}\n${row.key}"
    val now = System.currentTimeMillis()
    if (!due(readings[at], now)) return
    readings[at] = Got(readings[at]?.reading, now)
    scope.launch {
      val kept = runCatching { app.hub.read(panel.id, row.key, cached = true) }.getOrElse { if (it is CancellationException) throw it else failedReading(it.message) }
      readings[at] = Got(shown(readings[at]?.reading, kept), now)
      if (!kept.stale || kept.failed) return@launch
      val fresh = runCatching { app.hub.read(panel.id, row.key, cached = false) }.getOrElse { if (it is CancellationException) throw it else failedReading(it.message) }
      readings[at] = Got(shown(readings[at]?.reading, fresh), askedAt(fresh, System.currentTimeMillis()))
    }
  }

  // Re-read every 20 s while Home is in view, and soon after a press, as the web app does; not while
  // a tile is held, so nothing changes under the finger, nor with the app out of sight (a stale
  // reading costs Jarvis a lookup).
  LaunchedEffect(refreshTick) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val asked = seq
        runCatching { app.hub.panels() }
            .onSuccess {
              if (mover.held == null && asked == seq) {
                panels = it.panels
                can = it.can
                loaded = true
                problem = ""
                val here = it.panels.flatMap { p -> p.rows.map { r -> "${p.id}\n${r.key}" } }.toSet()
                readings.keys.retainAll(here)
                for (p in it.panels) for (r in p.rows) if (r.read) read(p, r)
              }
            }
            .onFailure {
              // A read cut short (the app went out of sight, or a press asked for a fresh one) is no error.
              if (it is CancellationException) throw it
              problem = if (it is Hub.NotAPerson) "" else it.message ?: "Couldn't read the panels"
            }
        delay(20_000)
      }
    }
  }

  fun press(panel: Panel, row: PanelRow, action: String, member: String? = null, value: String? = null, code: String? = null) {
    // One press at a time per button: its pill greys until Jarvis answers, so a slow one isn't sent twice.
    val at = pressKey(panel, row, action, member)
    if (at in pending) return
    pending += at
    scope.launch {
      flash = runCatching { app.hub.act(panel.id, row.key, action, member, value, code) }.fold({ it }, { it.message ?: "That didn't work" })
      pending -= at
      delay(1_500)
      refreshTick++
    }
  }

  /** A change to a to-do list, then the panels read again to show it. */
  fun note(change: suspend () -> Unit) {
    scope.launch {
      runCatching { change() }.onFailure { flash = "Could not change the list: ${it.message}" }
      refreshTick++
    }
  }

  /**
   * Tells Jarvis the new order of the panels, or of [panelId]'s things. One move at a time, in the
   * order they were made, since each names only what changed since the one before. If Jarvis can't
   * keep it, the panels are read again to show the order it has.
   */
  fun save(before: List<Panel>, after: List<Panel>, panelId: String?) {
    seq++
    scope.launch {
      saving.withLock {
        runCatching {
              if (panelId == null) {
                app.hub.orderPanels(shortestOrder(before.map { it.id }, after.map { it.id }))
              } else {
                app.hub.orderThings(panelId, shortestOrder(keysOf(before.first { it.id == panelId }), keysOf(after.first { it.id == panelId })))
              }
            }
            .onFailure { flash = if (it is Hub.NotAPerson) "Sign in with a code to move tiles" else "Could not move it: ${it.message}" }
        // A reading asked for while this was on its way may have the old order.
        seq++
      }
      refreshTick++
    }
  }

  /** Moves what is held onto the tile [onto], if that is a place it may go: another of its panel's things, or another panel. */
  fun swap(h: Held, onto: String): Boolean {
    val next =
        when (val target = cellsOf(panels, folded = h.thing == null).firstOrNull { it.id == onto }) {
          is Cell.Header -> if (h.thing == null && target.panel.id != h.panel) movePanel(panels, h.panel, target.panel.id) else return false
          is Cell.Thing ->
              if (h.thing != null && target.panel.id == h.panel && target.thing != h.thing) panels.map { if (it.id == h.panel) moveThing(it, h.thing, target.thing) else it }
              else return false
          else -> return false
        }
    if (next == panels) return false
    panels = next
    return true
  }

  /** Lifts [cell], unless something is held already or a sheet is open; says whether it did. */
  fun lift(cell: Cell, grab: Offset, finger: Offset, size: androidx.compose.ui.unit.IntSize): Boolean {
    if (mover.held != null || sheet != null) return false
    val panelId = when (cell) { is Cell.Header -> cell.panel.id; is Cell.Thing -> cell.panel.id; is Cell.Notes -> return false }
    seq++
    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    mover.held = Held(cell.id, panelId, (cell as? Cell.Thing)?.thing, grab, size, panels, finger)
    return true
  }

  fun drop() {
    val h = mover.held ?: return
    mover.held = null
    haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
    // Readings were set aside while it was held: read again either way.
    if (panels != h.before) save(h.before, panels, if (h.thing == null) null else h.panel) else refreshTick++
  }

  /** For TalkBack: moves [cell] one place earlier ([by] -1) or later (+1), and keeps it. */
  fun nudge(cell: Cell, by: Int): Boolean {
    if (mover.held != null) return false
    val before = panels
    when (cell) {
      is Cell.Header -> {
        val i = panels.indexOfFirst { it.id == cell.panel.id }
        val onto = panels.getOrNull(i + by) ?: return false
        panels = movePanel(panels, cell.panel.id, onto.id)
        save(before, panels, null)
      }
      is Cell.Thing -> {
        val panel = panels.firstOrNull { it.id == cell.panel.id } ?: return false
        val things = keysOf(panel).filter { k -> panel.rows.none { it.key == k && it.kind == "note" } }
        val onto = things.getOrNull(things.indexOf(cell.thing) + by) ?: return false
        panels = panels.map { if (it.id == panel.id) moveThing(it, cell.thing, onto) else it }
        save(before, panels, panel.id)
      }
      is Cell.Notes -> return false
    }
    return true
  }

  // While a panel is held the panels fold to their titles, to move among.
  val cells = cellsOf(panels, folded = held != null && held.thing == null)

  // While a tile is held: scroll near the edges, and make way when it is over another's place.
  LaunchedEffect(held) {
    val h = held ?: return@LaunchedEffect
    val edge = with(density) { EDGE.toPx() }
    val gate = with(density) { SWAP_GATE.toPx() }
    val speed = with(density) { EDGE_SPEED_DP.dp.toPx() }
    var scrolled = 0f
    var lastSwap = h.finger
    var last = 0L
    if (h.thing == null) grid.scrollToItem(0)
    while (true) {
      val now = withFrameNanos { it }
      val dt = if (last == 0L) 0f else (now - last) / 1e9f
      last = now
      val box = page.at ?: continue
      val y = box.windowToLocal(h.finger).y
      val pull =
          when {
            y < edge -> -(1f - y.coerceAtLeast(0f) / edge)
            y > box.size.height - edge -> 1f - (box.size.height - y).coerceAtLeast(0f) / edge
            else -> 0f
          }
      if (pull != 0f && dt > 0f) scrolled += grid.scrollBy(pull * speed * dt)
      val at = h.finger + Offset(0f, scrolled)
      if ((at - lastSwap).getDistance() < gate) continue
      val onto = mover.under(h.finger) ?: continue
      if (swap(h, onto)) {
        lastSwap = at
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
      }
    }
  }

  // The title and the status panel stay put; the tiles scroll under them.
  Column(Modifier.fillMaxSize()) {
  Heading("Jarvis")
  StatusPanel(shownGlasses(known, headset), statusText(status, liveState), Modifier.padding(horizontal = Even.gutter))
  Spacer(Modifier.height(Even.gutter))
  Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { page.at = it }) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Even.gutter, end = Even.gutter, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(Even.gutter),
        verticalArrangement = Arrangement.spacedBy(Even.gutter),
        userScrollEnabled = held == null,
    ) {
      // As the web app's orb: Talk starts, and the same tile stops what is going.
      val talking = status != Talk.Status.IDLE || liveState != LiveVoice.State.OFF
      item(key = "talk") {
        QuickTile(Glyphs.Mic, if (talking) "Stop" else "Talk", selected = talking) {
          if (talking) {
            app.conversation.stop()
            app.live.stop()
          } else {
            hooks.talk()
          }
        }
      }
      item(key = "jobs") { QuickTile(Glyphs.Jobs, "Jobs") { open(HomePage.JOBS) } }
      item(key = "routines") { QuickTile(Glyphs.Clock, "Routines") { open(HomePage.ROUTINES) } }
      item(key = "live") {
        QuickTile(Glyphs.Glasses, if (live) "Live voice: on" else "Live voice: off", selected = live) {
          live = !live
          app.prefs.live = live
        }
      }
      if (flash.isNotEmpty()) item(key = "flash", span = { GridItemSpan(maxLineSpan) }) { Text(flash, style = MaterialTheme.typography.bodySmall, color = c.dim, modifier = Modifier.padding(horizontal = 4.dp)) }
      if (problem.isNotEmpty()) item(key = "problem", span = { GridItemSpan(maxLineSpan) }) { Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger) }
      if (!app.prefs.token.startsWith("jss1_")) {
        item(key = "sign-in", span = { GridItemSpan(maxLineSpan) }) {
          Tile(Modifier.fillMaxWidth()) {
            Text("Sign in with a code (Settings) to see your Home panels here.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
          }
        }
      }
      if (loaded && panels.isEmpty() && app.prefs.token.startsWith("jss1_")) {
        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
          Text(emptyHomeText(can), style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
        }
      }
      items(cells, key = { it.id }, span = { GridItemSpan(if (it.wide) maxLineSpan else 1) }, contentType = { it::class }) { cell ->
        val lifted = held != null && (cell.id == held.cell || (cell is Cell.Thing && cell.panel.id == held.panel && cell.thing == held.thing))
        GridCell(
            cell = cell,
            reading = (cell as? Cell.Thing)?.let { readingOf(it.panel, it.row) },
            faceBusy = (cell as? Cell.Thing)?.let { t -> faceOf(t.row).button?.let { pressKey(t.panel, t.row, it.action, null) in pending } } == true,
            mover = mover,
            lifted = lifted,
            folded = held != null && held.thing == null,
            // Only the finger that lifted a tile moves it and lets it go; a tap while one is held does nothing.
            onTap = { if (cell is Cell.Thing && mover.held == null) sheet = cell.id },
            onHold = { grab, finger, size -> lift(cell, grab, finger, size) },
            onMove = { p -> mover.held?.takeIf { it.cell == cell.id }?.finger = p },
            onDrop = { if (mover.held?.cell == cell.id) drop() },
            nudge = { nudge(cell, it) },
            more = { sheet = cell.id },
            tick = { panel, key -> note { app.hub.tickNote(panel.id, key) } },
            press = ::press,
        )
      }
    }

    // The held tile's copy, under the finger at the spot it was held.
    if (held != null) {
      val cell = cells.firstOrNull { it.id == held.cell }
      if (cell != null) {
        val w = with(density) { held.size.width.toDp() }
        val h = with(density) { held.size.height.toDp() }
        Box(
            Modifier.offset {
                  val at = page.at?.windowToLocal(held.finger) ?: Offset.Zero
                  IntOffset((at.x - held.grab.x).roundToInt(), (at.y - held.grab.y).roundToInt())
                }
                .size(w, h)
                .graphicsLayer {
                  scaleX = 1.03f
                  scaleY = 1.03f
                  alpha = 0.95f
                  shadowElevation = 8.dp.toPx()
                  shape = Even.corner
                  clip = true
                },
        ) {
          when (cell) {
            is Cell.Header -> PanelTitle(cell, folded = true, Modifier.fillMaxSize())
            is Cell.Thing -> ThingTile(cell.row, readingOf(cell.panel, cell.row), Modifier.fillMaxSize(), buttonsOn = false) {}
            is Cell.Notes -> {}
          }
        }
      }
    }
  }

  }
  val opened = sheet?.let { id -> cells.firstOrNull { it.id == id } }
  // A thing gone from its panel closes its sheet, so it can't open again by itself if it comes back.
  LaunchedEffect(opened == null) { if (opened == null) sheet = null }
  if (opened != null) {
    ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = c.tile) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (opened) {
          is Cell.Thing ->
              ThingSheet(opened.panel, opened.row, readingOf(opened.panel, opened.row), app.hub, ::press, { a, m -> pressKey(opened.panel, opened.row, a, m) in pending }) { state, value, condition ->
                app.hub.alert(opened.panel.id, opened.row.key, state, value, condition).also {
                  scope.launch {
                    delay(1_500)
                    refreshTick++
                  }
                }
              }
          is Cell.Notes ->
              NotesSheet(
                  opened.panel.title,
                  opened.rows,
                  tick = { key -> note { app.hub.tickNote(opened.panel.id, key) } },
                  add = { text -> note { app.hub.addNote(opened.panel.id, text) } },
                  clear = { note { app.hub.clearTicked(opened.panel.id) } },
              )
          is Cell.Header -> {}
        }
        if (flash.isNotEmpty()) Text(flash, style = MaterialTheme.typography.bodySmall, color = c.dim)
      }
    }
  }
}

/** One panel cell: its title, its to-dos, or a thing; each knows where it is, for moving onto. */
@Composable
private fun LazyGridItemScope.GridCell(
    cell: Cell,
    reading: Reading?,
    faceBusy: Boolean,
    mover: Mover,
    lifted: Boolean,
    folded: Boolean,
    onTap: () -> Unit,
    onHold: (Offset, Offset, androidx.compose.ui.unit.IntSize) -> Boolean,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    nudge: (Int) -> Boolean,
    more: () -> Unit,
    tick: (Panel, String) -> Unit,
    press: Press,
) {
  val c = Even.colors
  val place = remember { Place() }
  DisposableEffect(cell.id) { onDispose { mover.places.remove(cell.id) } }
  // The held tile stays put in the grid while it scrolls away, or its gesture would end.
  val pins = LocalPinnableContainer.current
  val holding = mover.held?.cell == cell.id
  DisposableEffect(holding) {
    val pin = if (holding) pins?.pin() else null
    onDispose { pin?.release() }
  }
  val where =
      Modifier.animateItem().onGloballyPositioned {
        place.at = it
        mover.places[cell.id] = it.boundsInWindow()
      }
  val moving =
      Modifier.pointerInput(cell.id) { holdToMove(place, onTap, onHold, onMove, onDrop) }
          .semantics(mergeDescendants = true) {
            customActions = listOf(CustomAccessibilityAction("Move earlier") { nudge(-1) }, CustomAccessibilityAction("Move later") { nudge(1) })
          }
  // The hold is watched on this box, which stays while the tile is held and shows its gap: were it
  // to go, Compose would end the gesture the moment the tile lifted.
  when (cell) {
    is Cell.Header ->
        Box(where.then(moving)) {
          if (lifted) Box(Modifier.fillMaxWidth().height(48.dp).dashedGap(c.line)) else PanelTitle(cell, folded, Modifier)
        }
    is Cell.Thing ->
        Box(where.then(moving).semantics { onClick("Open") { onTap(); true } }) {
          if (lifted) Box(Modifier.fillMaxWidth().aspectRatio(Even.tileAspect).dashedGap(c.line))
          else ThingTile(cell.row, reading, Modifier, buttonsOn = mover.held == null && !faceBusy) { press(cell.panel, cell.row, it.action, null, null, null) }
        }
    is Cell.Notes -> NotesTile(cell, where, enabled = mover.held == null, more = more) { tick(cell.panel, it) }
  }
}

/** A panel's title across the row, held to move the panel; while panels move, a tile of its own. */
@Composable
private fun PanelTitle(cell: Cell.Header, folded: Boolean, modifier: Modifier) {
  val c = Even.colors
  Row(
      modifier.fillMaxWidth().height(48.dp).let { if (folded) it.clip(Even.corner).background(c.tile).padding(horizontal = 16.dp) else it.padding(start = 4.dp, top = 10.dp) },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(cell.panel.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    if (folded) Text(if (cell.things == 1) "1 tile" else "${cell.things} tiles", style = MaterialTheme.typography.bodySmall, color = c.dim)
  }
}

/**
 * A thing as an Even tile, all one size: its icon (the web app's, in its colour when lit), name,
 * state or reading, a car's battery, at most one button; the rest on a tap.
 */
@Composable
private fun ThingTile(row: PanelRow, reading: Reading?, modifier: Modifier, buttonsOn: Boolean, onButton: (PanelAction) -> Unit) {
  val c = Even.colors
  val face = faceOf(row, reading)
  Tile(modifier.aspectRatio(Even.tileAspect)) {
    Row(Modifier.fillMaxWidth().align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
      Icon(HomeIcons.of(row.icon), contentDescription = null, tint = iconTint(row), modifier = Modifier.size(24.dp))
      Spacer(Modifier.weight(1f))
      face.badge?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = if (face.low) c.danger else c.dim, modifier = Modifier.padding(end = 6.dp)) }
      if (row.active) Box(Modifier.size(7.dp).clip(CircleShape).background(c.on))
      if (face.more) Text("›", style = MaterialTheme.typography.titleMedium, color = c.dim, modifier = Modifier.padding(start = 8.dp))
    }
    Column(Modifier.align(Alignment.BottomStart)) {
      Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = face.labelLines, overflow = TextOverflow.Ellipsis)
      if (face.status.isNotEmpty()) {
        Text(
            face.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (face.warning) c.danger else c.dim.copy(alpha = if (face.faint) 0.7f else 1f),
            maxLines = face.statusLines,
            overflow = TextOverflow.Ellipsis,
        )
      }
      face.bar?.let { Battery(it, face.low, Modifier.padding(top = 8.dp)) }
      face.button?.let { b ->
        Spacer(Modifier.height(8.dp))
        key(b.action) { SafePill(b.label, enabled = buttonsOn) { onButton(b) } }
      }
    }
  }
}

/** A lit thing's icon in its kind's colour, as on the web; otherwise grey. */
@Composable
private fun iconTint(row: PanelRow): androidx.compose.ui.graphics.Color {
  val c = Even.colors
  if (!row.active) return c.dim
  return when (HomeIcons.tintOf(row.icon)) {
    HomeIcons.Tint.OPEN -> c.tintOpen
    HomeIcons.Tint.GOOD -> c.tintGood
    HomeIcons.Tint.ALERT -> c.danger
    HomeIcons.Tint.WARM -> c.tintWarm
  }
}

/** A car's battery as a bar, green, or red when low. */
@Composable
private fun Battery(level: Float, low: Boolean, modifier: Modifier = Modifier) {
  val c = Even.colors
  Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.chip)) {
    Box(Modifier.fillMaxWidth(level).height(6.dp).clip(CircleShape).background(if (low) c.danger else c.tintGood))
  }
}

/**
 * A panel's to-dos as one tile across the row, one or two tiles tall, showing as many as fit; a tap
 * anywhere but a to-do opens them all.
 */
@Composable
private fun NotesTile(cell: Cell.Notes, modifier: Modifier, enabled: Boolean, more: () -> Unit, tick: (String) -> Unit) {
  val c = Even.colors
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val tile = (maxWidth - Even.gutter) / 2 / Even.tileAspect
    val height = if (cell.tall) tile * 2 + Even.gutter else tile
    val room = height - 32.dp
    val moreLine = 24.dp * fontScale
    val shown = if (cell.rows.size <= (room / NOTE_LINE).toInt()) cell.rows.size else ((room - moreLine) / NOTE_LINE).toInt().coerceAtLeast(0)
    Tile(Modifier.fillMaxWidth().height(height), onClick = if (enabled) more else null) {
      Column {
        for (n in cell.rows.take(shown)) NoteLine(n, enabled) { tick(n.key) }
        if (cell.rows.size > shown) Text("${cell.rows.size - shown} more", style = MaterialTheme.typography.bodySmall, color = c.dim, modifier = Modifier.padding(top = 4.dp))
      }
    }
  }
}

private val NOTE_LINE = 40.dp

@Composable
internal fun NoteLine(note: PanelRow, enabled: Boolean = true, tick: () -> Unit) {
  Row(
      Modifier.fillMaxWidth().height(NOTE_LINE).toggleable(value = note.done == true, enabled = enabled, role = Role.Checkbox) { tick() },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = note.done == true, onCheckedChange = null)
    Spacer(Modifier.size(10.dp))
    Text(note.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** All of a thing, on a tap of its tile: its name and state or reading in full, every button, and its controls. */
@Composable
private fun ThingSheet(
    panel: Panel,
    row: PanelRow,
    reading: Reading?,
    hub: Hub,
    press: Press,
    busy: (action: String, member: String?) -> Boolean,
    alert: suspend (String?, Double?, String?) -> String,
) {
  val c = Even.colors
  Row(verticalAlignment = Alignment.CenterVertically) {
    Icon(HomeIcons.of(row.icon), contentDescription = null, tint = iconTint(row), modifier = Modifier.size(24.dp))
    Spacer(Modifier.size(12.dp))
    Text(row.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    row.level?.let { Text("${it.toInt()}%", style = MaterialTheme.typography.labelLarge, color = if (it < LOW_BATTERY) c.danger else c.dim) }
    if (row.active) Box(Modifier.padding(start = 8.dp).size(7.dp).clip(CircleShape).background(c.on))
  }
  if (row.value.isNotEmpty()) Text(row.value, style = MaterialTheme.typography.bodyMedium, color = c.dim)
  row.level?.let { Battery((it / 100.0).toFloat().coerceIn(0f, 1f), it < LOW_BATTERY) }
  row.note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.danger) }
  if (row.read) ReadingView(row, reading, hub)
  Figures(row.tiles)
  if (row.alerts.isNotEmpty()) Text("Alerts: " + row.alerts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.tintOpen)
  Buttons(row.actions.filter { it.input == null }, { busy(it.action, null) }) { press(panel, row, it.action, null, null, null) }
  for (a in row.actions.filter { it.input != null }) key(a.action) { InputAction(a, enabled = !busy(a.action, null)) { v -> press(panel, row, a.action, null, v, null) } }
  More(panel, row, press, busy)
  val more = row.more ?: return
  if (more.history) HistoryChart(hub, panel.id, row.key)
  more.map?.let { MapPicture(hub, it.lat, it.lng) }
  more.alert?.let { AlertMe(it, alert) }
}

/** A tile's reading in full, in the web's order: its line, figures, list, when it was checked, and where from. */
@Composable
private fun ReadingView(row: PanelRow, reading: Reading?, hub: Hub) {
  val c = Even.colors
  val context = androidx.compose.ui.platform.LocalContext.current
  when {
    reading == null -> Text("…", style = MaterialTheme.typography.bodyMedium, color = c.dim)
    else -> {
      if (reading.text.isNotBlank()) Text(reading.text, style = MaterialTheme.typography.bodyMedium, color = if (reading.failed) c.dim else c.ink)
      reading.image?.takeIf { it.startsWith("data:image/") }?.let { ReadingPicture(it) }
      Figures(reading.figures)
      for (line in reading.list) Text("•  $line", style = MaterialTheme.typography.bodySmall, color = c.ink)
      reading.chart?.takeIf { it.points.size >= 2 }?.let { ch -> LineChart(ch.points.map { it.y }, "", ch.title, ch.points.first().x, ch.points.last().x) }
      reading.map?.let { MapPicture(hub, it.lat, it.lng) }
      reading.at?.let { Text(checkedAt(it), style = MaterialTheme.typography.labelSmall, color = c.dim) }
      reading.source?.takeIf { it.startsWith("https://") }?.let { url ->
        Text(
            "Source: " + (android.net.Uri.parse(url).host ?: url),
            style = MaterialTheme.typography.labelSmall,
            color = c.tintOpen,
            modifier = Modifier.clickable { context.view(url) },
        )
      }
    }
  }
  infoLine(row.tool, row.every, row.refresh)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.dim) }
}

/** Figures side by side, as the web's stat boxes: a small label, the value's first word large. */
@Composable
private fun Figures(figures: List<app.jarvis.glasses.hub.Figure>) {
  if (figures.isEmpty()) return
  val c = Even.colors
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (f in figures) {
      val (big, rest) = splitFigure(f.value)
      Column(Modifier.widthIn(min = 128.dp).clip(Even.corner).background(c.chip).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(f.label, style = MaterialTheme.typography.labelSmall, color = c.dim)
        Row(verticalAlignment = Alignment.Bottom) {
          Text(big, style = MaterialTheme.typography.headlineSmall)
          if (rest.isNotEmpty()) Text(" $rest", style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(bottom = 2.dp))
        }
      }
    }
  }
}

private fun statusText(s: Talk.Status, live: LiveVoice.State): String =
    when {
      live == LiveVoice.State.LIVE -> "live"
      live == LiveVoice.State.CONNECTING -> "connecting"
      s == Talk.Status.LISTENING -> "listening"
      s == Talk.Status.THINKING -> "thinking"
      s == Talk.Status.SPEAKING -> "speaking"
      else -> "ready"
    }

/** Talk, Jobs, Routines, Live voice: the same size as every other tile, as the Even app's. */
@Composable
private fun QuickTile(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
  Tile(Modifier.aspectRatio(Even.tileAspect), onClick = onClick, selected = selected) {
    Icon(icon, contentDescription = null, tint = Even.colors.ink, modifier = Modifier.size(24.dp).align(Alignment.TopStart))
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.BottomStart))
  }
}

@Composable
private fun Buttons(actions: List<PanelAction>, busy: (PanelAction) -> Boolean = { false }, onPress: (PanelAction) -> Unit) {
  if (actions.isEmpty()) return
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (a in actions) key(a.action) { SafePill(a.label, enabled = !busy(a)) { onPress(a) } }
  }
}

/** Which press is on its way: a button of a thing (and of one of its room's lights). */
private fun pressKey(panel: Panel, row: PanelRow, action: String, member: String?) = "${panel.id}\n${row.key}\n$action\n${member.orEmpty()}"

/** A thing's controls, in its sheet: a room's lights, a slider, the thermostat, the alarm. */
@Composable
private fun More(panel: Panel, row: PanelRow, press: Press, busy: (action: String, member: String?) -> Boolean) {
  val more = row.more ?: return
  val c = Even.colors
  more.members?.forEach { m ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(m.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
      Buttons(m.actions, { busy(it.action, m.key) }) { press(panel, row, it.action, m.key, null, null) }
    }
  }
  more.level?.let { lv ->
    // On the thing's own steps, from its min, with their decimals: a number from -2.5 to 2.5 by 0.5 can be 1.5.
    val step = lv.step.takeIf { it > 0 } ?: 1.0
    var v by remember(lv.value) { mutableFloatStateOf(lv.value.toFloat()) }
    val chosen = stepWords(onStep(v.toDouble(), lv.min, lv.max, step), step)
    Text("${lv.name}: $chosen${lv.unit}", style = MaterialTheme.typography.bodySmall, color = c.dim)
    // Sent when let go, as on the web.
    Slider(
        value = v,
        onValueChange = { v = it },
        valueRange = lv.min.toFloat()..sliderTop(lv.min, lv.max, step).toFloat(),
        steps = sliderSteps(lv.min, lv.max, step),
        onValueChangeFinished = { press(panel, row, "level:$chosen", null, null, null) },
    )
  }
  more.climate?.let { cl ->
    // As the web: a set point only when the unit has one (not in fan or dry mode, nor off), and only
    // a press that changes it.
    val t = cl.target?.takeIf { cl.mode != "off" }
    if (t != null) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((label, by) in listOf("−" to -cl.step, "+" to cl.step)) {
          val next = nextTemp(t, by, cl.min, cl.max)
          if (label == "+") Text("${fmt(t)}°", style = MaterialTheme.typography.titleMedium)
          if (next != t) Buttons(listOf(PanelAction("temp:${fmt(next)}", label)), { busy(it.action, null) }) { press(panel, row, it.action, null, null, null) }
        }
      }
    }
    cl.now?.let { Text("Now ${fmt(it)}°", style = MaterialTheme.typography.bodySmall, color = c.dim) }
    if (cl.modes.isNotEmpty()) Choices("Mode", cl.modes, cl.mode, { busy("mode:$it", null) }) { press(panel, row, "mode:$it", null, null, null) }
    if (cl.fans.isNotEmpty()) Choices("Fan", cl.fans, cl.fan, { busy("fan:$it", null) }) { press(panel, row, "fan:$it", null, null, null) }
  }
  more.actions?.let { acts ->
    var code by remember { mutableStateOf("") }
    if (more.code != null) OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Buttons(acts, { busy(it.action, null) }) { a -> press(panel, row, a.action, null, null, code.takeIf { more.code?.forActions?.contains(a.action) == true }) }
  }
}

/** A temperature to a tenth, with a point whatever the phone's language. */
private fun fmt(d: Double) = stepWords(d, 0.1)
