package app.jarvis.glasses.g2

/** What Jarvis's own screen on the G2 offers. */
enum class G2Choice(val label: String) {
  CHAT("Chat with Jarvis"),
  ALERTS("Latest alerts"),
  CLOSE("Close"),
}

/**
 * Jarvis's own screen on the G2, opened by asking for it ("open Jarvis"): a choice moved with a
 * swipe on the ring or touchpad, opened with a tap. Alerts arriving meanwhile are counted on it.
 */
class G2Menu(private val clock: () -> String = ::g2Clock) {
  private val choices = G2Choice.entries
  private var at = 0

  /** Alerts that arrived while the menu was up, not looked at yet. */
  var unseen = 0

  val chosen: G2Choice
    get() = choices[at]

  fun up() {
    if (at > 0) at--
  }

  fun down() {
    if (at < choices.size - 1) at++
  }

  /** The choices as the display shows them, the chosen one marked; plain ASCII, which the glasses' font has. */
  fun lines(): List<String> =
      choices.mapIndexed { i, c ->
        val label = if (c == G2Choice.ALERTS && unseen > 0) "${c.label} ($unseen new)" else c.label
        (if (i == at) ">  " else "    ") + label
      }

  fun page(): List<G2Box> = g2Page("JARVIS  |  ${clock()}", listOf("") + lines(), "swipe: choose  |  tap: open")
}
