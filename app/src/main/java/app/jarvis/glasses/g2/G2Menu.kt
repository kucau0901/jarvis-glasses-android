package app.jarvis.glasses.g2

/** What Jarvis's own screen on the G2 offers, each with its symbol filled ([on], chosen) and hollow ([off]). */
enum class G2Choice(val label: String, val on: String, val off: String) {
  CHAT("Chat", "▶", "▷"),
  ALERTS("Alerts", "★", "☆"),
  CLOSE("Close", "■", "□"),
}

/**
 * Jarvis's own screen on the G2, opened by asking for it ("open Jarvis"): three tiles side by side
 * under a top line with the battery and the time, the chosen one framed thick and bright, moved
 * with a swipe on the ring or touchpad and opened with a tap. Alerts arriving meanwhile are
 * counted on theirs.
 *
 * Only frames and the symbols the glasses' font has (▶ ★ ■ ●, as Even's guidelines list them),
 * drawn by the glasses themselves: a move redraws at once, where pictures would take most of a
 * second each.
 */
class G2Menu(private val clock: () -> String = ::g2Clock) {
  private val choices = G2Choice.entries
  private var at = 0

  /** Alerts that arrived while the menu was up, not looked at yet. */
  var unseen = 0

  /** The glasses' battery, 0–100, once they have said it. */
  var battery: Int? = null

  val chosen: G2Choice
    get() = choices[at]

  fun up() {
    if (at > 0) at--
  }

  fun down() {
    if (at < choices.size - 1) at++
  }

  /** What a tile says under its name. */
  fun note(c: G2Choice): String =
      when (c) {
        G2Choice.CHAT -> "Talk to Jarvis"
        G2Choice.ALERTS -> if (unseen > 0) "$unseen new" else "Latest"
        G2Choice.CLOSE -> "Back to Even"
      }

  fun page(): List<G2Box> {
    val top = listOf(
        G2Box(1, 8, 0, 300, 40, "●  JARVIS"),
        G2Box(2, EvenHub.WIDTH - 156, 0, 148, 40, listOfNotNull(battery?.let { "$it%" }, clock()).joinToString("    ")),
    )
    val tiles =
        choices.mapIndexed { i, c ->
          val chosen = i == at
          G2Box(3 + i, i * (TILE_W + GAP), 46, TILE_W, 190, "${if (chosen) c.on else c.off}  ${c.label}\n\n${note(c)}",
              border = if (chosen) 3 else 1, shade = if (chosen) 15 else 5, padding = 14)
        }
    val bottom = G2Box(6, 8, 248, EvenHub.WIDTH - 16, 40, "▲▼  swipe: choose        tap: open")
    return top + tiles + bottom
  }

  private companion object {
    /** Three tiles and the two gaps between them fill the display's width. */
    const val GAP = 12
    const val TILE_W = (EvenHub.WIDTH - 2 * GAP) / 3
  }
}
