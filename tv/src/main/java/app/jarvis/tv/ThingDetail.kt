package app.jarvis.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.Even
import app.jarvis.glasses.ui.HomeIcons
import app.jarvis.tv.jarvis.ButtonInput
import app.jarvis.tv.jarvis.Climate
import app.jarvis.tv.jarvis.Figure
import app.jarvis.tv.jarvis.Level
import app.jarvis.tv.jarvis.Panel
import app.jarvis.tv.jarvis.PanelAction
import app.jarvis.tv.jarvis.PanelRow
import app.jarvis.tv.jarvis.Reading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * All of a thing, in a sheet that rises over Home as the phone's does: its name, state and reading in full, then every button and
 * control as a row of Even tiles the remote moves along, six to a row as on Home, and at the end
 * the ways to take it off Home. A number is chosen with − and + (the remote has no slider); words
 * can't be typed here, so those are left to asking Jarvis. While [busy] the controls are grey and
 * do nothing. Back is the caller's ([onClose]); Close at the bottom does the same.
 */
@Composable
fun ThingDetail(
    panel: Panel,
    row: PanelRow,
    reading: Reading?,
    busy: Boolean,
    onAct: (action: String, member: String?, value: String?) -> Unit,
    onRemoveTile: () -> Unit,
    onRemovePanel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val c = Even.colors
  val start = remember { FocusRequester() }
  // The page starts on its first control, whichever that turns out to be: the first asked for it.
  var claimed = false
  fun first(): Modifier =
      if (claimed) Modifier
      else {
        claimed = true
        Modifier.focusRequester(start)
      }
  LaunchedEffect(panel.id, row.key) { start.requestFocus() }
  val act: (String, String?, String?) -> Unit = { a, m, v -> if (!busy) onAct(a, m, v) }
  Column(
      modifier.fillMaxWidth().heightIn(max = 430.dp).clip(SHEET).background(c.page).verticalScroll(rememberScrollState()).padding(horizontal = 48.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Header(panel, row)
    row.level?.let { BatteryBar((it / 100.0).toFloat().coerceIn(0f, 1f), it < BATTERY_LOW, Modifier.widthIn(max = 280.dp)) }
    Figures(row.tiles)
    if (row.read) ReadingView(reading)
    if (row.alerts.isNotEmpty()) Small("Alerts: " + row.alerts.joinToString(" · "), c.tintOpen)

    val buttons = row.actions.filter { it.input == null }
    if (buttons.isNotEmpty()) {
      Caption("Buttons")
      for ((i, chunk) in buttons.chunked(TILES_PER_ROW).withIndex()) {
        TileRow(chunk.size) { for ((j, b) in chunk.withIndex()) Bar(b.label, if (i == 0 && j == 0) first() else Modifier, busy) { act(b.action, null, null) } }
      }
    }
    for (a in row.actions.filter { it.input != null }) {
      key(a.action) {
        val input = a.input!!
        if (input.kind == "slider") NumberControl(a, input, first(), busy) { act(a.action, null, it) }
        else {
          Caption(a.label)
          Small("Ask Jarvis to set this")
        }
      }
    }
    row.more?.members?.let { members ->
      for (m in members) {
        val acts = m.actions.filter { it.input == null }.take(TILES_PER_ROW - 2)
        TileRow(2 + acts.size) {
          Column(Modifier.weight(2f)) {
            Text(m.label, style = TextStyle(fontSize = 17.sp, color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.value.isNotEmpty()) Small(m.value)
          }
          for ((j, b) in acts.withIndex()) Bar(b.label, if (j == 0) first() else Modifier, busy) { act(b.action, m.key, null) }
        }
      }
    }
    row.more?.level?.let { LevelControl(it, first(), busy) { n -> act("level:$n", null, null) } }
    row.more?.climate?.let { ClimateControl(it, { first() }, busy) { a -> act(a, null, null) } }
    row.more?.actions?.let { acts ->
      val needCode = row.more?.code?.forActions.orEmpty().toSet()
      val free = acts.filter { it.action !in needCode && it.input == null }
      if (free.isNotEmpty()) {
        Caption("More")
        for ((i, chunk) in free.chunked(TILES_PER_ROW).withIndex()) {
          TileRow(chunk.size) { for ((j, b) in chunk.withIndex()) Bar(b.label, if (i == 0 && j == 0) first() else Modifier, busy) { act(b.action, null, null) } }
        }
      }
      for (b in acts.filter { it.action in needCode }) Small("${b.label} needs the code: ask Jarvis")
    }

    Spacer(Modifier.height(18.dp))
    var armed by remember(panel.id) { mutableStateOf(false) }
    var tileArmed by remember(panel.id, row.key) { mutableStateOf(false) }
    // A thing with no controls starts on Close, never on a removal: both removals need a second press.
    val close = first()
    TileRow(TILES_PER_ROW) {
      Bar(
          if (tileArmed) "Press again to remove" else "Remove from Home",
          Modifier.onFocusChanged { if (!it.hasFocus) tileArmed = false },
          weight = 2f,
          color = if (tileArmed) c.danger else null,
      ) {
        if (tileArmed) onRemoveTile() else tileArmed = true
      }
      Bar(removePanelWords(panel.title, armed), Modifier.onFocusChanged { if (!it.hasFocus) armed = false }, weight = 3f, color = if (armed) c.danger else null) {
        if (armed) onRemovePanel() else armed = true
      }
      Bar("Close", close, onClick = onClose)
    }
  }
}

/** Its panel, then its icon large, its name, and its state; a car's battery and a green dot when on at the right. */
@Composable
private fun Header(panel: Panel, row: PanelRow) {
  val c = Even.colors
  Small(panel.title)
  Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Icon(HomeIcons.of(row.icon), null, tint = thingTint(row), modifier = Modifier.size(48.dp))
    Spacer(Modifier.width(18.dp))
    Column(Modifier.weight(1f)) {
      Text(row.label, style = TextStyle(fontSize = 26.sp, color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (row.value.isNotEmpty()) Text(row.value, style = TextStyle(fontSize = 17.sp, color = c.dim))
      row.note?.let { Text(it, style = TextStyle(fontSize = 17.sp, color = c.danger)) }
    }
    row.level?.let { Text("${it.toInt()}%", style = TextStyle(fontSize = 19.sp, color = if (it < BATTERY_LOW) c.danger else c.dim), modifier = Modifier.padding(horizontal = 10.dp)) }
    if (row.active) Box(Modifier.size(10.dp).clip(CircleShape).background(c.on))
  }
}

/** A tile's reading in full, as the phone has it: its line, figures and list, and when it was looked up. */
@Composable
private fun ReadingView(reading: Reading?) {
  val c = Even.colors
  if (reading == null) {
    Small("…")
    return
  }
  if (reading.text.isNotBlank()) Text(reading.text, style = TextStyle(fontSize = 19.sp, color = if (reading.failed) c.dim else c.ink))
  Figures(reading.figures)
  for (line in reading.list.take(READING_LINES)) Text("•  $line", style = TextStyle(fontSize = 17.sp, color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
  if (reading.list.size > READING_LINES) Small("${reading.list.size - READING_LINES} more")
  when {
    reading.pending -> Small("Looking it up…")
    reading.at != null -> Small("Checked " + DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(reading.at).atZone(ZoneId.systemDefault())))
  }
}

private const val READING_LINES = 10

/** Figures side by side in tiles, as the web's stat boxes: a small label, the value large. */
@Composable
private fun Figures(figures: List<Figure>) {
  if (figures.isEmpty()) return
  val c = Even.colors
  FlowRow(horizontalArrangement = Arrangement.spacedBy(Even.gutter * 2), verticalArrangement = Arrangement.spacedBy(Even.gutter * 2)) {
    for (f in figures) {
      Column(Modifier.widthIn(min = 134.dp).clip(Even.corner).background(c.tile).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(f.label, style = TextStyle(fontSize = 13.sp, color = c.dim), maxLines = 1)
        Text(f.value, style = TextStyle(fontSize = 22.sp, color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

/**
 * A button that takes a number: − and + walk it along its steps from the middle of its range, and
 * Set sends it. Only Set works the thing, so walking past a number doesn't set it on the way.
 */
@Composable
private fun NumberControl(action: PanelAction, input: ButtonInput, first: Modifier, busy: Boolean, send: (String) -> Unit) {
  val min = input.min ?: 0.0
  val max = input.max ?: 100.0
  val step = input.step?.takeIf { it > 0 } ?: 1.0
  var v by remember(min, max, step) { mutableDoubleStateOf(stepFrom((min + max) / 2, 0, min, max, step)) }
  val words = numberWords(v, step)
  Caption(action.label)
  TileRow(4) {
    Bar("−", first) { v = stepFrom(v, -1, min, max, step) }
    Shown(words)
    Bar("+") { v = stepFrom(v, 1, min, max, step) }
    Bar("Set $words", busy = busy) { send(words) }
  }
}

/** A thing's level, as its name and number between − and +; each press sets it, as the web's slider does when let go. */
@Composable
private fun LevelControl(level: Level, first: Modifier, busy: Boolean, send: (String) -> Unit) {
  val step = level.step.takeIf { it > 0 } ?: 1.0
  var v by remember(level.value) { mutableDoubleStateOf(level.value) }
  Caption(level.name)
  TileRow(3) {
    for ((label, by) in listOf("−" to -1, "+" to 1)) {
      if (by > 0) Shown(numberWords(v, step) + level.unit)
      Bar(label, if (by < 0) first else Modifier, busy) {
        val next = stepFrom(v, by, level.min, level.max, step)
        if (next != v) {
          v = next
          send(numberWords(next, step))
        }
      }
    }
  }
}

/**
 * An aircond as the web has it: its set point between − and + (none when it is off or has none),
 * how warm it is now, and its modes and fan speeds as tiles, the one it is on marked with a dot.
 */
@Composable
private fun ClimateControl(climate: Climate, first: () -> Modifier, busy: Boolean, send: (String) -> Unit) {
  val c = Even.colors
  var target by remember(climate.target, climate.mode) { mutableStateOf(climate.target?.takeIf { climate.mode != "off" }) }
  val t = target
  if (t != null) {
    Caption(climate.now?.let { "Temperature, now ${tempWords(it)}°" } ?: "Temperature")
    TileRow(3) {
      for ((label, by) in listOf("−" to -climate.step, "+" to climate.step)) {
        if (by > 0) Shown("${tempWords(t)}°")
        Bar(label, if (by < 0) first() else Modifier, busy) {
          val next = tempAfter(t, by, climate.min, climate.max)
          if (next != t) {
            target = next
            send("temp:${tempWords(next)}")
          }
        }
      }
    }
  } else {
    climate.now?.let { Small("Now ${tempWords(it)}°", c.dim) }
  }
  for ((name, options, chosen) in listOf(Triple("Mode", climate.modes, climate.mode), Triple("Fan", climate.fans, climate.fan))) {
    if (options.isNotEmpty()) {
      Caption(name)
      for (chunk in options.chunked(TILES_PER_ROW)) {
        TileRow(chunk.size) {
          for (o in chunk) Bar(prettyWord(o), if (name == "Mode") first() else Modifier, busy, chosen = o == chosen) { send("${name.lowercase()}:$o") }
        }
      }
    }
  }
}

/** A number between − and +, not a button. */
@Composable
private fun RowScope.Shown(text: String) {
  Box(Modifier.weight(1f).height(56.dp), contentAlignment = Alignment.Center) {
    Text(text, style = TextStyle(fontSize = 24.sp, color = Even.colors.ink), maxLines = 1)
  }
}

/** One of the page's buttons: an Even tile with its name, grey while it can't be pressed; [chosen] gets a green dot. */
@Composable
private fun RowScope.Bar(
    label: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enabled: Boolean = !busy,
    weight: Float = 1f,
    chosen: Boolean = false,
    color: Color? = null,
    onClick: () -> Unit,
) {
  val c = Even.colors
  FocusBar({ if (enabled) onClick() }, modifier.weight(weight)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = TextStyle(fontSize = 17.sp, color = if (!enabled) c.dim else color ?: c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
      if (chosen) {
        Spacer(Modifier.width(8.dp))
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.on))
      }
    }
  }
}

@Composable
private fun Caption(text: String) {
  Text(text, style = TextStyle(fontSize = 15.sp, color = Even.colors.dim), modifier = Modifier.padding(start = 4.dp, top = 12.dp))
}

@Composable
private fun Small(text: String, color: Color = Even.colors.dim) {
  Text(text, style = TextStyle(fontSize = 15.sp, color = color), modifier = Modifier.padding(start = 4.dp))
}

/** The last button's words: pressed once, it asks to be pressed again, so one slip of the remote can't remove a panel. */
internal fun removePanelWords(title: String, armed: Boolean): String = if (armed) "Press again to remove $title" else "Remove the $title panel"

/**
 * [v] moved [by] steps along a control's steps, which start at [min] and go up by [step] no higher
 * than [max]; 0 puts it on the nearest step.
 */
internal fun stepFrom(v: Double, by: Int, min: Double, max: Double, step: Double): Double {
  val top = min + floor((max - min) / step + 1e-9) * step
  return (min + (((v - min) / step).roundToInt() + by) * step).coerceIn(min, top)
}

/**
 * A number to send or show, with as many decimals as its step has and no more, and a point whatever
 * the TV's language: Jarvis reads "23,5" as no number at all.
 */
internal fun numberWords(v: Double, step: Double): String {
  val decimals = java.math.BigDecimal(step.toString()).stripTrailingZeros().scale().coerceAtLeast(0)
  return java.math.BigDecimal(v.toString()).setScale(decimals, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** An aircond's next set point, [by] from [target] within its range, to a tenth, as the web works it out. */
internal fun tempAfter(target: Double, by: Double, min: Double, max: Double): Double = ((target + by).coerceIn(min, max) * 10).roundToLong() / 10.0

private fun tempWords(t: Double) = numberWords(t, 0.1)

/** "heat_cool" as "Heat cool". */
internal fun prettyWord(word: String): String = word.replace('_', ' ').replaceFirstChar { it.uppercase() }

/** The phone's bottom sheet: round at the top, square where it meets the screen's edge. */
private val SHEET = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
