package app.jarvis.glasses.g2

import app.jarvis.glasses.hub.Said

/** What Jarvis's own screen on the G2 offers. */
enum class G2Choice(val label: String) {
  TALK("Talk to Jarvis"),
  ALERTS("Latest alerts"),
  CLOSE("Close"),
}

/**
 * Jarvis's own screen on the G2, opened by asking for it ("open Jarvis"): a choice moved with a
 * swipe on the ring or touchpad, opened with a tap.
 */
class G2Menu {
  private val choices = G2Choice.entries
  private var at = 0

  val chosen: G2Choice
    get() = choices[at]

  fun up() {
    if (at > 0) at--
  }

  fun down() {
    if (at < choices.size - 1) at++
  }

  /** As the display shows it; plain ASCII, which the glasses' font has. */
  fun text(): String =
      "Jarvis\n\n" + choices.mapIndexed { i, c -> (if (i == at) "> " else "   ") + c.label }.joinToString("\n") + "\n\n[swipe: choose | tap: open]"
}

/** The latest [most] of what alerts said in Chat, newest first, each as Chat reads it ("Title: text"). */
fun g2AlertList(said: List<Said>, most: Int = 5): String {
  val alerts = said.filter { it.role == "assistant" && (it.alert != null || it.routine != null || it.job != null) }.takeLast(most).reversed()
  return if (alerts.isEmpty()) "No alerts yet." else alerts.joinToString("\n\n") { it.read }
}
