package app.jarvis.glasses.jarvis

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One turn of the conversation, in the `{role, text}` shape `context` takes. */
data class Turn(val role: String, val text: String) {
  companion object {
    fun user(text: String) = Turn("user", text)
    fun jarvis(text: String) = Turn("assistant", text)
  }
}

/** What `/api/v1/voice` streams back for one question. */
sealed interface VoiceEvent {
  data class Heard(val text: String) : VoiceEvent
  data class Progress(val text: String) : VoiceEvent
  /** The answer's words; `ok` is false for a failure, which is still speakable. */
  data class Answer(val text: String, val ok: Boolean) : VoiceEvent
  /** One sentence of the spoken answer, base64 audio. */
  data class Audio(val seq: Int, val last: Boolean, val mime: String, val base64: String) : VoiceEvent

  companion object {
    fun from(o: JsonObject): VoiceEvent? =
        when (o.str("type")) {
          "transcript" -> Heard(o.str("text") ?: "")
          "progress" -> o.str("text")?.let { Progress(it) }
          "result" -> Answer(o.str("text") ?: "", o.bool("ok") ?: true)
          "error" -> Answer(o.str("text") ?: "Something went wrong on the way to an answer.", false)
          "audio" -> {
            val a = (o["audio"] as? JsonObject) ?: o
            val data = a.str("data")
            if (data.isNullOrEmpty()) null
            else Audio(o.int("seq") ?: 0, o.bool("last") ?: false, a.str("mime") ?: "audio/mpeg", data)
          }
          else -> null
        }
  }
}

/**
 * An alert from the `/api/v1/events` socket: Jarvis speaking first (docs/api.md, "Talking about
 * them", in jarvis-voice-agent). [talk] is Jarvis's word on whether to say it and listen for a
 * reply: the person's "Talk to me about alerts" (and, for the family room, "Say family-room messages
 * too"), and not a repeat. An older Jarvis sends none: then [speak] decides, as before.
 */
data class Alert(
    val id: String,
    val title: String,
    val text: String,
    val speak: Boolean,
    val urgent: Boolean,
    val talk: Boolean = speak,
    /** What raised it: "routine", "job", "chat" (a family message), "api" (the house)… */
    val source: String = "",
    /** A family message's conversation: "family" for the room, "dm:…" for a direct one. */
    val convo: String? = null,
) {
  /** How it reads, and is said: "Title: text", as the web app has it; just the text under "Jarvis". */
  val said: String
    get() = if (title == "Jarvis") text else "$title: $text"

  companion object {
    /** The `alert` field of a `{"type":"alert"}` message, or null for anything else. */
    fun fromMessage(o: JsonObject): Alert? {
      if (o.str("type") != "alert") return null
      val a = (o["alert"] as? JsonObject) ?: return null
      val id = a.str("id") ?: return null
      val text = a.str("text")?.trim().orEmpty()
      if (text.isEmpty()) return null
      val speak = a.bool("speak") ?: true
      return Alert(id, a.str("title") ?: "Jarvis", text, speak, a.bool("urgent") ?: false, a.bool("talk") ?: speak, a.str("source").orEmpty(), a.str("convo"))
    }
  }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
