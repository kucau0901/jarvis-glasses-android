package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Place
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One thing to tell Jarvis: [event] (`arrived:<slug>`, `left:<slug>`, or the bare word), the place's name as [text], and when it happened. */
@Serializable data class Crossing(val event: String, val text: String, val at: Long)

/**
 * What a crossed geofence tells Jarvis. For each of [slugs] still among [places]: `arrived:<slug>`
 * or `left:<slug>`, which the person's routines for that place wait for; and with [offers] (the
 * second switch, each one an answer paid for), the bare `arrived` or `left` too, for "when I arrive
 * anywhere". A fence whose place is gone from the list says nothing.
 */
fun crossings(arrived: Boolean, slugs: List<String>, places: List<Place>, offers: Boolean, now: Long): List<Crossing> {
  val word = if (arrived) "arrived" else "left"
  return slugs.distinct().mapNotNull { slug -> places.find { it.slug == slug } }.flatMap { p ->
    listOfNotNull(Crossing("$word:${p.slug}", p.name, now), if (offers) Crossing(word, p.name, now) else null)
  }
}

/**
 * Crossings on their way to Jarvis, and the rule for one that didn't get there: it is kept
 * ([read], [write]: the app's prefs, so it outlives the app being stopped) and sent at the next
 * chance, which is the next [send]: another crossing, the app starting, the alerts connection
 * coming back. Once it is [KEEP_MS] old it is dropped unsent, because a late "arrived" is worse
 * than none. With the switch off ([on]) nothing is sent and nothing is kept.
 */
class Outbox(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val now: () -> Long,
    private val on: () -> Boolean,
    private val post: suspend (Crossing) -> Boolean,
) {
  private val lock = Mutex()

  /** Sends [new] after whatever was still waiting, oldest first; what Jarvis didn't take waits for the next call. */
  suspend fun send(new: List<Crossing> = emptyList()) =
      lock.withLock {
        var waiting = due(decode(read()) + new, now())
        // Kept before it is sent, so one whose sending is cut short (the receiver's time limit) is still tried again.
        keep(waiting)
        for (c in waiting.toList()) {
          if (!on()) break
          if (post(c)) {
            waiting = waiting.filterNot { it === c }
            keep(waiting)
          }
        }
        keep(due(waiting, now()))
      }

  /** The switch went off, or the person signed out: nothing waits. */
  fun clear() = write("")

  private fun keep(waiting: List<Crossing>) = write(if (on() && waiting.isNotEmpty()) Hub.JSON_IN.encodeToString(LIST, waiting) else "")

  companion object {
    /** Ten minutes: past that, a crossing is not worth telling. */
    const val KEEP_MS = 10 * 60_000L

    /** More than this waiting is a phone long out of reach; the newest are the ones that matter. */
    const val MOST = 20

    private val LIST = ListSerializer(Crossing.serializer())

    /** The retry-or-drop rule: of [waiting], those still worth sending at [now], oldest first, at most [MOST]. */
    fun due(waiting: List<Crossing>, now: Long): List<Crossing> = waiting.filter { now - it.at < KEEP_MS }.takeLast(MOST)

    fun decode(text: String): List<Crossing> = if (text.isBlank()) emptyList() else runCatching { Hub.JSON_IN.decodeFromString(LIST, text) }.getOrDefault(emptyList())
  }
}
