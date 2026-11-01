package app.jarvis.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.Even
import app.jarvis.glasses.ui.HomeIcons
import app.jarvis.glasses.ui.outlinePill
import app.jarvis.tv.jarvis.Figure
import app.jarvis.tv.jarvis.Panel
import app.jarvis.tv.jarvis.PanelAction
import app.jarvis.tv.jarvis.PanelRow
import app.jarvis.tv.jarvis.Reading

/**
 * Jarvis's Home panels under the TV's apps, as the phone shows them: each panel's title, its to-dos
 * as one tile across the row, its things as Even tiles six to a row, and an Add tile to put another
 * thing on it; after the last panel, a tile to start a new panel. OK on a thing is [onPress] (its
 * button, or its page when it has none); holding OK is [onMore]. The to-do tile's [onMore] gets the
 * panel's first to-do, whose kind is "note". [readings] and [busy] are by [thingKey].
 */
fun LazyListScope.jarvisPanels(
    panels: List<Panel>,
    readings: Map<String, Reading>,
    busy: Set<String>,
    armed: String?,
    onPress: (Panel, PanelRow) -> Unit,
    onMore: (Panel, PanelRow) -> Unit,
    onAdd: (Panel) -> Unit,
    onNewPanel: () -> Unit,
) {
  // Keyed by place as well as id, so two panels with one id can't crash the list.
  for ((p, panel) in panels.withIndex()) {
    val id = "jarvis/$p/${panel.id}"
    item(key = "$id/title") { Title(panel.title) }
    val notes = notesOf(panel)
    if (notes.isNotEmpty()) item(key = "$id/notes") { NotesTile(notes) { onMore(panel, notes.first()) } }
    for ((r, slots) in panelSlots(panel).withIndex()) {
      item(key = "$id/$r") {
        TileRow(slots.size) {
          for (slot in slots) {
            when (slot) {
              is PanelSlot.Thing ->
                  ThingTile(slot.row, readings[thingKey(panel, slot.row)], thingKey(panel, slot.row) in busy, thingKey(panel, slot.row) == armed, Modifier.weight(1f), { onPress(panel, slot.row) }) {
                    onMore(panel, slot.row)
                  }
              PanelSlot.Add -> IconTile(AppIcons.Add, "Add", Modifier.weight(1f)) { onAdd(panel) }
            }
          }
        }
      }
    }
  }
  item(key = "jarvis/new") {
    Column(Modifier.padding(top = 14.dp)) { TileRow(1) { IconTile(AppIcons.Add, "New panel", Modifier.weight(1f), onClick = onNewPanel) } }
  }
}

/** The key of a thing's reading and of its press in flight: its panel, its own key and its name, a line each (a car's tiles share a key). */
fun thingKey(panel: Panel, row: PanelRow): String = panel.id + "\n" + row.key + "\n" + row.label

/** The button OK on a thing's tile works: its first one that needs nothing typed or chosen. */
fun firstButton(row: PanelRow): PanelAction? = row.actions.firstOrNull { it.input == null }

/**
 * Whether OK on [row]'s tile has to be pressed twice to work [button]: the house's ways in (a lock,
 * the gate, a garage or door) and the alarm. A remote is easy to press by mistake while moving
 * past a tile, and an open gate is worse than a light left on.
 */
fun needsSecondPress(row: PanelRow, button: PanelAction): Boolean =
    row.icon in GUARDED_ICONS || GUARDED_WORDS.containsMatchIn(button.label) || GUARDED_WORDS.containsMatchIn(button.action)

private val GUARDED_ICONS = setOf("lock", "unlock", "gate", "garage", "door", "alarm", "shield")
private val GUARDED_WORDS = Regex("\\b(unlock|open|disarm|arm)\\b", RegexOption.IGNORE_CASE)

/** Home's rows: six tiles across, as the TV's apps are. */
internal const val TILES_PER_ROW = 6

/** A place in a panel's rows of tiles. */
internal sealed interface PanelSlot {
  data class Thing(val row: PanelRow) : PanelSlot

  data object Add : PanelSlot
}

/** A panel's things, then its Add tile, six to a row. */
internal fun panelSlots(panel: Panel): List<List<PanelSlot>> =
    (panel.rows.filter { it.kind != "note" }.map<PanelRow, PanelSlot> { PanelSlot.Thing(it) } + PanelSlot.Add).chunked(TILES_PER_ROW)

internal fun notesOf(panel: Panel): List<PanelRow> = panel.rows.filter { it.kind == "note" }

/** The to-dos the to-do tile has room for: three columns of three lines. */
internal const val NOTE_SLOTS = 9

private const val NOTE_COLUMNS = 3

/** How many of [count] to-dos the tile shows; when they don't all fit, the last place says how many more. */
internal fun notesShown(count: Int, slots: Int = NOTE_SLOTS): Int = if (count <= slots) count else slots - 1

/** Under this a battery is low: its badge and bar turn red, as on the phone and the web. */
internal const val BATTERY_LOW = 20.0

/**
 * What a thing's tile shows, to fit Home's small TV tile: its name, one line of state (two for a
 * reading with no button), a car's battery, and its button's name.
 */
internal data class TileFace(
    val status: String,
    val labelLines: Int,
    val statusLines: Int,
    val warning: Boolean,
    val faint: Boolean,
    val button: PanelAction?,
    val badge: String?,
    val low: Boolean,
    val bar: Float?,
)

internal fun tileFace(row: PanelRow, reading: Reading?): TileFace {
  val status = thingStatus(row, reading)
  val button = firstButton(row)
  val bar = row.level?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) }
  val reads = row.read && row.note == null && row.value.isEmpty()
  return TileFace(
      status = status,
      // A second line for the name only when nothing else wants the room.
      labelLines = if (status.isEmpty() && button == null && bar == null) 2 else 1,
      statusLines = if (reads && button == null && bar == null) 2 else 1,
      warning = row.note != null,
      faint = reads && reading?.failed == true,
      button = button,
      badge = row.level?.let { "${it.toInt()}%" },
      low = row.level != null && row.level < BATTERY_LOW,
      bar = bar,
  )
}

/** A thing's state in a line: a warning first, then its value, then its reading, then its figures. */
internal fun thingStatus(row: PanelRow, reading: Reading?): String =
    when {
      row.note != null -> row.note
      row.value.isNotEmpty() -> row.value
      row.read -> reading?.let { it.text.ifBlank { figuresLine(it.figures.take(3)) } }.orEmpty().ifBlank { "…" }
      else -> figuresLine(row.tiles)
    }

internal fun figuresLine(figures: List<Figure>): String = figures.joinToString(" · ") { "${it.label} ${it.value}".trim() }

/** A row of Home's tiles, the last one filled out with gaps so its tiles keep their size. */
@Composable
internal fun TileRow(filled: Int, content: @Composable RowScope.() -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(Even.gutter * 2)) {
    content()
    repeat(TILES_PER_ROW - filled) { Spacer(Modifier.weight(1f)) }
  }
}

/**
 * A thing as the phone's tile has it: its icon (in its kind's colour when on), a car's battery and a
 * green dot when on along the top; its name, state and button's name along the bottom.
 */
@Composable
private fun ThingTile(row: PanelRow, reading: Reading?, busy: Boolean, armed: Boolean, modifier: Modifier, onPress: () -> Unit, onMore: () -> Unit) {
  val c = Even.colors
  val face = tileFace(row, reading)
  FocusTile(onPress, modifier, onLongClick = onMore) {
    Column(Modifier.fillMaxSize()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(HomeIcons.of(row.icon), null, tint = thingTint(row), modifier = Modifier.size(26.dp))
        Spacer(Modifier.weight(1f))
        face.badge?.let { Text(it, style = TextStyle(fontSize = 13.sp, color = if (face.low) c.danger else c.dim), modifier = Modifier.padding(end = 6.dp)) }
        if (row.active) Box(Modifier.size(7.dp).clip(CircleShape).background(c.on))
      }
      Spacer(Modifier.weight(1f))
      Text(row.label, style = TextStyle(fontSize = 16.sp, lineHeight = 18.sp, color = c.ink), maxLines = face.labelLines, overflow = TextOverflow.Ellipsis)
      if (face.status.isNotEmpty() || face.button != null) {
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
          if (face.status.isNotEmpty()) {
            Text(
                face.status,
                style = TextStyle(fontSize = 13.sp, lineHeight = 14.sp, color = if (face.warning) c.danger else c.dim.copy(alpha = if (face.faint) 0.7f else 1f)),
                maxLines = face.statusLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
          }
          face.button?.let { Pill(if (armed) "Press again" else it.label, busy, Modifier.padding(start = if (face.status.isEmpty()) 0.dp else 6.dp), armed) }
        }
      }
      face.bar?.let { BatteryBar(it, face.low, Modifier.padding(top = 5.dp)) }
    }
  }
}

/** A thing's button's name in a thin pill, as the Even app's small buttons; grey while it is being pressed, red while it waits for its second press. */
@Composable
private fun Pill(label: String, busy: Boolean, modifier: Modifier = Modifier, armed: Boolean = false) {
  val c = Even.colors
  val ink = if (armed) c.danger else if (busy) c.dim else c.ink
  Text(
      label,
      style = TextStyle(fontSize = 12.sp, color = ink),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = modifier.widthIn(max = 84.dp).outlinePill(if (armed) c.danger else if (busy) c.line else c.dim).padding(horizontal = 8.dp, vertical = 2.dp),
  )
}

/** A battery as a thin bar, green, or red when low. */
@Composable
internal fun BatteryBar(level: Float, low: Boolean, modifier: Modifier = Modifier) {
  val c = Even.colors
  Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.chip)) {
    Box(Modifier.fillMaxWidth(level).height(4.dp).clip(CircleShape).background(if (low) c.danger else c.tintGood))
  }
}

/** A lit thing's icon in its kind's colour, as on the web; otherwise grey. */
@Composable
internal fun thingTint(row: PanelRow): Color {
  val c = Even.colors
  if (!row.active) return c.dim
  return when (HomeIcons.tintOf(row.icon)) {
    HomeIcons.Tint.OPEN -> c.tintOpen
    HomeIcons.Tint.GOOD -> c.tintGood
    HomeIcons.Tint.ALERT -> c.danger
    HomeIcons.Tint.WARM -> c.tintWarm
  }
}

/**
 * A panel's to-dos as one tile across the row, as tall as Home's tiles: three columns of them, the
 * ticked ones grey and struck through. OK opens them all.
 */
@Composable
private fun NotesTile(notes: List<PanelRow>, onOpen: () -> Unit) {
  val c = Even.colors
  BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
    val gap = Even.gutter * 2
    val tile = (maxWidth - gap * (TILES_PER_ROW - 1)) / TILES_PER_ROW
    val shown = notesShown(notes.size)
    // Null is the place that says how many more there are.
    val places: List<PanelRow?> = notes.take(shown) + if (notes.size > shown) listOf(null) else emptyList()
    val columns = places.chunked(NOTE_SLOTS / NOTE_COLUMNS)
    FocusBar(onOpen, height = tile / Even.tileAspect, grow = 1.01f) {
      Row(Modifier.fillMaxSize().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (i in 0 until NOTE_COLUMNS) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (n in columns.getOrElse(i) { emptyList() }) {
              if (n == null) Text("${notes.size - shown} more", style = TextStyle(fontSize = 15.sp, color = c.dim)) else NoteLine(n)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun NoteLine(note: PanelRow) {
  val c = Even.colors
  val done = note.done == true
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(13.dp).clip(RoundedCornerShape(3.dp)).border(BorderStroke(1.5.dp, c.dim), RoundedCornerShape(3.dp)).background(if (done) c.dim else Color.Transparent))
    Spacer(Modifier.size(10.dp))
    Text(
        note.label,
        style = TextStyle(fontSize = 15.sp, color = if (done) c.dim else c.ink, textDecoration = if (done) TextDecoration.LineThrough else null),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * A focusable Even tile of any height across its width, for what isn't one of Home's tiles: the
 * to-do list, a page's buttons. Drawn as TvHome's tiles are: flat, small corners, and the chosen one
 * a little larger with a ring in ink.
 */
@Composable
internal fun FocusBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    fill: Color = Even.colors.tile,
    grow: Float = 1.03f,
    content: @Composable BoxScope.() -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(if (focused) grow else 1f, label = "focus")
  Box(
      modifier
          .fillMaxWidth()
          .height(height)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clip(Even.corner)
          .background(fill)
          .border(BorderStroke(2.5.dp, if (focused) Even.colors.ink else Color.Transparent), Even.corner)
          .onFocusChanged { focused = it.isFocused }
          .clickable(onClick = onClick)
          .padding(horizontal = 16.dp),
      contentAlignment = Alignment.CenterStart,
      content = content,
  )
}
