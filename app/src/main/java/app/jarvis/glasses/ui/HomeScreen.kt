package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.conversation.Conversation as Talk
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.live.LiveVoice
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

@Composable
private fun HomeMain(app: JarvisApp, hooks: Hooks, open: (HomePage) -> Unit) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var panels by remember { mutableStateOf<List<Panel>>(emptyList()) }
  var problem by remember { mutableStateOf("") }
  var flash by remember { mutableStateOf("") }
  var refreshTick by remember { mutableStateOf(0) }
  val connected by app.audio.connected.collectAsStateWithLifecycle()
  val status by app.conversation.status.collectAsStateWithLifecycle()
  val liveState by app.live.state.collectAsStateWithLifecycle()
  var live by remember { mutableStateOf(app.prefs.live) }
  var opened by remember { mutableStateOf(emptySet<String>()) }

  // Re-read every 20 s while Home is in view, and soon after a press, as the web app does.
  LaunchedEffect(refreshTick) {
    while (true) {
      runCatching { app.hub.panels() }
          .onSuccess { panels = it.panels; problem = "" }
          .onFailure { problem = if (it is Hub.NotAPerson) "" else it.message ?: "Couldn't read the panels" }
      delay(20_000)
    }
  }

  fun press(panel: Panel, row: PanelRow, action: String, member: String? = null, value: String? = null, code: String? = null) {
    scope.launch {
      flash = runCatching { app.hub.act(panel.id, row.key, action, member, value, code) }.fold({ it }, { it.message ?: "That didn't work" })
      delay(1_500)
      refreshTick++
    }
  }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Heading("Jarvis") }
    item { GlassesDisplay(connected = connected, now = statusText(status, liveState)) }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickTile(Glyphs.Mic, "Talk", Modifier.weight(1f)) { hooks.talk() }
        QuickTile(Glyphs.Jobs, "Jobs", Modifier.weight(1f)) { open(HomePage.JOBS) }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickTile(Glyphs.Clock, "Routines", Modifier.weight(1f)) { open(HomePage.ROUTINES) }
        QuickTile(Glyphs.Glasses, if (live) "Live voice: on" else "Live voice: off", Modifier.weight(1f), selected = live) {
          live = !live
          app.prefs.live = live
        }
      }
    }
    if (flash.isNotEmpty()) item { Text(flash, style = MaterialTheme.typography.bodySmall, color = c.dim, modifier = Modifier.padding(horizontal = 4.dp)) }
    if (problem.isNotEmpty()) item { Text(problem, style = MaterialTheme.typography.bodySmall, color = c.danger) }
    if (!app.prefs.token.startsWith("jss1_")) {
      item {
        Tile(Modifier.fillMaxWidth()) {
          Text("Sign in with a code (Settings) to see your Home panels here.", style = MaterialTheme.typography.bodyMedium, color = c.dim)
        }
      }
    }
    for (panel in panels) {
      item { Text(panel.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 10.dp)) }
      val notes = panel.rows.filter { it.kind == "note" }
      if (notes.isNotEmpty()) {
        item {
          Tile(Modifier.fillMaxWidth()) {
            Column {
              for (n in notes) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Checkbox(checked = n.done == true, onCheckedChange = { scope.launch { runCatching { app.hub.tickNote(panel.id, n.key) }; refreshTick++ } })
                  Text(n.label, style = MaterialTheme.typography.bodyMedium)
                }
              }
            }
          }
        }
      }
      items(tileRows(panel.rows.filter { it.kind != "note" }) { "${panel.id}/${it.key}" in opened }) { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          for (row in pair) {
            val id = "${panel.id}/${row.key}"
            PanelTile(panel, row, id in opened, { opened = if (id in opened) opened - id else opened + id }, Modifier.weight(1f), ::press)
          }
          if (pair.size == 1 && "${panel.id}/${pair[0].key}" !in opened) Spacer(Modifier.weight(1f))
        }
      }
    }
  }
}

/** Tiles two to a row, in order, except an opened one, which takes the whole row for its controls. */
internal fun tileRows(rows: List<PanelRow>, isOpen: (PanelRow) -> Boolean): List<List<PanelRow>> {
  val out = mutableListOf<List<PanelRow>>()
  val closed = mutableListOf<PanelRow>()
  for (row in rows) {
    if (!isOpen(row)) {
      closed += row
      continue
    }
    out += closed.chunked(2)
    closed.clear()
    out += listOf(row)
  }
  return out + closed.chunked(2)
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

/** The glasses' display, as the Even app previews it: a dot-matrix clock and a line of status. */
@Composable
private fun GlassesDisplay(connected: Boolean, now: String) {
  val c = Even.colors
  var time by remember { mutableStateOf(LocalDateTime.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      time = LocalDateTime.now()
      delay(15_000)
    }
  }
  Box(Modifier.fillMaxWidth().clip(Even.corner).background(c.hud).padding(18.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(time.format(DateTimeFormatter.ofPattern("EEE d")), style = MaterialTheme.typography.labelSmall, color = c.hudInk)
        Spacer(Modifier.height(6.dp))
        DotMatrix(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = c.hudInk, dot = 2.5.dp)
      }
      Column(horizontalAlignment = Alignment.End) {
        Text(if (connected) "Glasses connected" else "Glasses away", style = MaterialTheme.typography.labelSmall, color = c.hudInk)
        Spacer(Modifier.height(4.dp))
        Text("Jarvis: $now", style = MaterialTheme.typography.labelSmall, color = c.hudInk)
      }
    }
  }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
  Tile(modifier.aspectRatio(1.6f), onClick = onClick, selected = selected) {
    Icon(icon, contentDescription = null, tint = Even.colors.ink, modifier = Modifier.size(26.dp).align(Alignment.TopStart))
    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.align(Alignment.BottomStart))
  }
}

/** One panel row as an Even tile: icon, name, state, and its buttons; tap to open its controls. */
@Composable
private fun PanelTile(
    panel: Panel,
    row: PanelRow,
    open: Boolean,
    toggle: () -> Unit,
    modifier: Modifier,
    press: (Panel, PanelRow, String, String?, String?, String?) -> Unit,
) {
  val c = Even.colors
  Tile(modifier, onClick = if (row.more != null) toggle else null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(iconFor(row.icon), contentDescription = null, tint = if (row.active) c.ink else c.dim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.weight(1f))
        if (row.active) Box(Modifier.size(7.dp).clip(CircleShape).background(c.on))
      }
      Text(row.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text(row.note ?: row.value, style = MaterialTheme.typography.bodySmall, color = if (row.note != null) c.danger else c.dim, maxLines = 2)
      for (f in row.tiles) Text("${f.label} ${f.value}", style = MaterialTheme.typography.labelSmall, color = c.dim)
      Buttons(row.actions.filter { it.input == null }) { press(panel, row, it.action, null, null, null) }
      if (open) More(panel, row, press)
    }
  }
}

@Composable
private fun Buttons(actions: List<PanelAction>, onPress: (PanelAction) -> Unit) {
  if (actions.isEmpty()) return
  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    for (a in actions.take(3)) {
      Text(
          a.label,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).outlinePill(Even.colors.line).clickable { onPress(a) }.padding(horizontal = 10.dp, vertical = 5.dp),
      )
    }
  }
}

/** What opens under a tile: a room's lights, a slider, the thermostat, the alarm. */
@Composable
private fun More(panel: Panel, row: PanelRow, press: (Panel, PanelRow, String, String?, String?, String?) -> Unit) {
  val more = row.more ?: return
  val c = Even.colors
  more.members?.forEach { m ->
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(m.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
      Buttons(m.actions) { press(panel, row, it.action, m.key, null, null) }
    }
  }
  more.level?.let { lv ->
    var v by remember(lv.value) { mutableFloatStateOf(lv.value.toFloat()) }
    Text("${lv.name}: ${v.toInt()}${lv.unit}", style = MaterialTheme.typography.bodySmall, color = c.dim)
    Slider(value = v, onValueChange = { v = it }, valueRange = lv.min.toFloat()..lv.max.toFloat(), onValueChangeFinished = { press(panel, row, "level:${v.toInt()}", null, null, null) })
  }
  more.climate?.let { cl ->
    val target = cl.target ?: cl.now ?: cl.min
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Buttons(listOf(PanelAction("temp:${fmt(target - cl.step)}", "−"))) { press(panel, row, it.action, null, null, null) }
      Text("${fmt(target)}°", style = MaterialTheme.typography.titleMedium)
      Buttons(listOf(PanelAction("temp:${fmt(target + cl.step)}", "+"))) { press(panel, row, it.action, null, null, null) }
    }
    if (cl.modes.isNotEmpty()) Buttons(cl.modes.map { PanelAction("mode:$it", it) }) { press(panel, row, it.action, null, null, null) }
  }
  more.actions?.let { acts ->
    var code by remember { mutableStateOf("") }
    if (more.code != null) OutlinedTextField(code, { code = it }, label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Buttons(acts) { a -> press(panel, row, a.action, null, null, code.takeIf { more.code?.forActions?.contains(a.action) == true }) }
  }
}

private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(d)

private fun iconFor(name: String): ImageVector =
    when (name) {
      "light", "power", "plug", "fan", "scene", "press" -> Glyphs.Bulb
      "lock", "unlock" -> Icons.Outlined.Lock
      "gate", "garage", "door", "window", "blind" -> Glyphs.Gate
      "temp", "climate", "drop", "weather", "sensor" -> Glyphs.Thermo
      "camera" -> Glyphs.Camera
      "car", "route" -> Icons.Outlined.Place
      "music", "play" -> Icons.Outlined.PlayArrow
      "alarm", "alert", "motion" -> Icons.Outlined.Notifications
      "calendar", "clock" -> Icons.Outlined.DateRange
      "mail" -> Icons.Outlined.Email
      "chat" -> Glyphs.Chat
      else -> Glyphs.Tiles
    }
