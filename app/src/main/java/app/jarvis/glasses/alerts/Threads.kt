package app.jarvis.glasses.alerts

import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn

/**
 * What was said under each alert's notification: Jarvis's alert, then each reply and answer, so a
 * reply goes to Jarvis with the alert as context and the notification shows the whole exchange.
 * Only the newest [keep] alerts are remembered; a reply to an older one starts from its words.
 */
class Threads(private val keep: Int = 20) {
  private val threads = LinkedHashMap<String, List<Turn>>()

  @Synchronized
  fun open(alert: Alert): List<Turn> {
    val t = listOf(Turn.jarvis(alert.text))
    threads[alert.id] = t
    while (threads.size > keep) threads.remove(threads.keys.first())
    return t
  }

  /** Adds [turn] to [id]'s thread, starting it from [fallback] (the alert's words) if forgotten. */
  @Synchronized
  fun add(id: String, turn: Turn, fallback: String): List<Turn> {
    val t = (threads[id] ?: listOf(Turn.jarvis(fallback))) + turn
    threads[id] = t
    return t
  }
}
