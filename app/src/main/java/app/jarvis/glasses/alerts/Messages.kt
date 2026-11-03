package app.jarvis.glasses.alerts

import app.jarvis.glasses.jarvis.Alert

/**
 * A family message, as its alert brings it (lib/chat.ts in Jarvis): `source` "chat", its
 * conversation in `convo` ("family" for the room, "dm:…" for a direct one), and its sender in the
 * title, "Aisha, to the family" in the room and just "Aisha" in a direct message.
 */
data class FromFamily(val convo: String, val sender: String, val room: Boolean) {
  companion object {
    fun of(a: Alert): FromFamily? {
      val convo = a.convo?.takeIf { a.source == "chat" && it.isNotBlank() } ?: return null
      val room = convo == "family"
      val sender = (if (room) a.title.removeSuffix(ROOM_SUFFIX) else a.title).trim().ifEmpty { "Someone" }
      return FromFamily(convo, sender, room)
    }

    private const val ROOM_SUFFIX = ", to the family"
  }
}

/**
 * The messages each family conversation's notification shows, the newest [keep] of each: who said
 * it (null for you) and what. A conversation is one notification, as a messaging app has it.
 */
class Messages(private val keep: Int = 8) {
  data class Line(val from: String?, val text: String, val at: Long)

  private val convos = HashMap<String, List<Line>>()

  @Synchronized
  fun add(convo: String, line: Line): List<Line> {
    val now = (convos[convo].orEmpty() + line).takeLast(keep)
    convos[convo] = now
    return now
  }

  @Synchronized fun of(convo: String): List<Line> = convos[convo].orEmpty()

  /** Read, in the app: its notification is gone, and the next starts afresh. */
  @Synchronized fun clear(convo: String) {
    convos.remove(convo)
  }
}
