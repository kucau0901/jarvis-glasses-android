package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.hub.Reading

// Home as the Even app's grid: every tile one size, two to a row, and moved as on Jarvis's web app
// (src/app/ui/HomeWall.ts): a panel by its title, a thing within its panel, never from one panel to
// another. Rows sharing a key (a car's) are one thing and move together. Plain Kotlin, to be tested.

/** One place in the grid. [wide] ones take the whole row. */
internal sealed interface Cell {
  val id: String
  val wide: Boolean

  /** A panel's title, held to move the panel. [things] counts its tiles, shown while panels move. */
  data class Header(val panel: Panel, val things: Int) : Cell {
    override val id = "h/${panel.id}"
    override val wide = true
  }

  /** A panel's to-do list, as one tile across the row: one tile tall for up to [SHORT_NOTES], else two. */
  data class Notes(val panel: Panel, val rows: List<PanelRow>) : Cell {
    override val id = "n/${panel.id}"
    override val wide = true
    val tall = rows.size > SHORT_NOTES
  }

  /**
   * A thing's row as a tile. [thing] is its key, the same for all of a car's rows; its name is in the
   * id too, so when one of a car's rows goes (no lock state, say) the next can't take its place.
   */
  data class Thing(val panel: Panel, val row: PanelRow, val n: Int) : Cell {
    override val id = "t/${panel.id}/${row.key}/${row.label}#$n"
    override val wide = false
    val thing: String get() = row.key
  }
}

internal const val SHORT_NOTES = 3

/** The panels as cells. [folded] (while a panel is held) leaves only their titles to move among. */
internal fun cellsOf(panels: List<Panel>, folded: Boolean = false): List<Cell> = buildList {
  for (panel in panels) {
    val (notes, things) = panel.rows.partition { it.kind == "note" }
    add(Cell.Header(panel, things.size))
    if (folded) continue
    if (notes.isNotEmpty()) add(Cell.Notes(panel, notes))
    val seen = HashMap<Pair<String, String>, Int>()
    for (row in things) {
      val n = seen.getOrDefault(row.key to row.label, 0)
      seen[row.key to row.label] = n + 1
      add(Cell.Thing(panel, row, n))
    }
  }
}

/** The web's move (HomeWall.ts): out of its place, into the place of the one it is over. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
  if (from == to || from !in indices || to !in indices) return this
  return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Jarvis's rule for an order it is sent (lib/panels.ts): the named ones first, as named, then the
 * rest as they were. All items with a key come together, in their own order.
 */
internal fun <T> ordered(items: List<T>, keys: List<String>, keyOf: (T) -> String): List<T> {
  val taken = BooleanArray(items.size)
  val out = ArrayList<T>(items.size)
  for (k in keys.distinct()) items.forEachIndexed { i, x -> if (!taken[i] && keyOf(x) == k) { taken[i] = true; out += x } }
  items.forEachIndexed { i, x -> if (!taken[i]) out += x }
  return out
}

/** The panels with [id] moved to where [onto] is. */
internal fun movePanel(panels: List<Panel>, id: String, onto: String): List<Panel> =
    panels.moved(panels.indexOfFirst { it.id == id }, panels.indexOfFirst { it.id == onto })

/** A panel's keys in order, each once: what Jarvis orders by. */
internal fun keysOf(panel: Panel): List<String> = panel.rows.map { it.key }.distinct()

/**
 * [panel] with thing [key] moved to where [onto] is. Only things move; the to-dos keep their places
 * among the keys, so Jarvis keeps them where they were on the web too.
 */
internal fun moveThing(panel: Panel, key: String, onto: String): Panel {
  val keys = keysOf(panel)
  val notes = panel.rows.filter { it.kind == "note" }.map { it.key }.toSet()
  val things = keys.filter { it !in notes }
  val moved = things.moved(things.indexOf(key), things.indexOf(onto)).iterator()
  return panel.copy(rows = ordered(panel.rows, keys.map { if (it in notes) it else moved.next() }) { it.key })
}

/**
 * The shortest order to send that Jarvis turns into [new], by [ordered]'s rule: an unmoved tail
 * needn't be named, which keeps a move in a big panel under what Jarvis reads.
 */
internal fun shortestOrder(old: List<String>, new: List<String>): List<String> {
  for (n in 0..new.size) {
    val named = new.take(n)
    if (ordered(old, named) { it } == new) return named
  }
  return new
}

/**
 * What a tile shows, so every tile fits one size: a name, its state, at most one button. The state
 * is one line, or up to three for a reading with no button (the web shows four). [badge] and [bar]
 * are a car's battery.
 */
internal data class Face(
    val labelLines: Int,
    val status: String,
    val statusLines: Int,
    val warning: Boolean,
    val faint: Boolean,
    val button: PanelAction?,
    val more: Boolean,
    val badge: String? = null,
    val bar: Float? = null,
    val low: Boolean = false,
)

/** Under this a battery is low: its badge and bar turn red, as on the web. */
internal const val LOW_BATTERY = 20.0

internal fun faceOf(row: PanelRow, reading: Reading? = null): Face {
  val buttons = row.actions.filter { it.input == null }
  val button = buttons.firstOrNull()
  val figures = row.tiles.joinToString(" · ") { "${it.label} ${it.value}" }
  val read = row.read && row.note == null && row.value.isEmpty()
  val status =
      when {
        row.note != null -> row.note
        row.value.isNotEmpty() -> row.value
        read && reading == null -> "…"
        read -> reading!!.text.ifBlank { reading.figures.joinToString(" · ") { "${it.label} ${it.value}" } }.ifBlank { "…" }
        else -> figures
      }
  val low = row.level != null && row.level < LOW_BATTERY
  val m = row.more
  val controls = m != null && (m.members != null || m.climate != null || m.actions != null || m.level != null || m.history || m.alert != null || m.map != null)
  val more =
      controls || buttons.size > 1 || row.tiles.isNotEmpty() || (row.note != null && row.value.isNotEmpty()) || row.read ||
          row.actions.any { it.input != null } || row.alerts.isNotEmpty()
  return Face(
      labelLines = if (button != null || read) 1 else 2,
      status = status,
      statusLines = if (read && button == null) 3 else 1,
      warning = row.note != null,
      faint = read && reading?.failed == true,
      button = button,
      more = more,
      badge = row.level?.let { "${it.toInt()}%" },
      bar = row.level?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
      low = low,
  )
}
