package app.jarvis.glasses.alerts

import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn

/**
 * What was said under each alert's notification: Jarvis's alert, then each reply and answer, so a
 * reply goes to Jarvis with the alert as context and the notification shows the whole exchange.
 * Only the newest [keep] alerts are remembered; a reply to an older one starts from its words.
 *
 * A bot's alert (a skill's) starts its thread quoted as the bot's words ([Alert.told]), as Chat's
 * transcript has it, so nothing in them reads to the model as Jarvis's or the person's; the
 * notification still shows its words as they are ([shown]).
 */
class Threads(private val keep: Int = 20) {
  private val threads = LinkedHashMap<String, List<Turn>>()
  private val shown = HashMap<String, String>()

  @Synchronized
  fun open(alert: Alert): List<Turn> {
    val t = listOf(Turn.jarvis(if (alert.skill != null) alert.told else alert.text))
    threads[alert.id] = t
    shown[alert.id] = alert.text
    while (threads.size > keep) threads.keys.first().let { threads.remove(it); shown.remove(it) }
    return t
  }

  /** How [id]'s alert reads in its notification: its words, or [fallback] once forgotten. */
  @Synchronized fun shown(id: String, fallback: String): String = shown[id] ?: fallback

  /** Adds [turn] to [id]'s thread, starting it from [fallback] (the alert's words) if forgotten. */
  @Synchronized
  fun add(id: String, turn: Turn, fallback: String): List<Turn> {
    val t = (threads[id] ?: listOf(Turn.jarvis(fallback))) + turn
    threads[id] = t
    return t
  }
}
