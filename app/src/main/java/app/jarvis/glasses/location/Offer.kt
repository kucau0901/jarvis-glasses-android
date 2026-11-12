package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Routine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What "Offer something useful when I arrive" has Jarvis do. The switch makes the phone send the
 * bare `arrived` beside each place's own event, and a bare event with no routine listening is
 * dropped in silence: so the switch also keeps the routine that listens, made when it goes on and
 * removed when it goes off. It is an ordinary routine, shown with the person's others, and theirs
 * to reword by asking Jarvis; one of their own for the same event is left alone and counts.
 */
class Offer(
    private val routines: suspend () -> List<Routine>,
    private val add: suspend (name: String, event: String, ask: String) -> Unit,
    private val remove: suspend (id: String) -> Unit,
) {
  /** The switch is on: make the routine unless something already listens for an arrival. */
  suspend fun ensure() {
    if (routines().none { it.listensFor(EVENT) }) add(NAME, EVENT, ASK)
  }

  /** The switch went off: take away the one made here. One the person made themselves stays. */
  suspend fun stop() {
    for (r in routines()) if (r.name == NAME && r.listensFor(EVENT)) remove(r.id)
  }

  companion object {
    const val EVENT = "arrived"
    const val NAME = "Something useful where I arrive"

    /** Jarvis adds the place's name after this, and sends nothing when the answer is not worth an alert. */
    const val ASK =
        "I have just arrived at the place named below. Tell me the one thing most useful to me here and now, in a sentence or two: " +
            "something on my calendar soon, a reminder, note or list of mine that fits this place, or what I usually need here. " +
            "If nothing is worth interrupting me for, say nothing."
  }
}

/** Whether this routine is set off by [event] (`trigger: {kind: "event", event}`). */
internal fun Routine.listensFor(event: String): Boolean {
  val t = trigger as? JsonObject ?: return false
  return (t["kind"] as? JsonPrimitive)?.contentOrNull == "event" && (t["event"] as? JsonPrimitive)?.contentOrNull == event
}
