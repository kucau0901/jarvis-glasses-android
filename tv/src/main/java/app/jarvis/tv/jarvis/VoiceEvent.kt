package app.jarvis.tv.jarvis

import java.util.Base64
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One turn of the conversation, in the `{role, text}` shape `context` takes: "user" or "assistant". */
data class Turn(val role: String, val text: String) {
  companion object {
    fun user(text: String) = Turn("user", text)

    fun jarvis(text: String) = Turn("assistant", text)
  }
}

/**
 * What `/api/v1/voice` streams back for one question, in the order it comes: what was heard, what
 * Jarvis is doing, what it puts on the screen, the answer, then the answer spoken a sentence at a
 * time. `used`, `job`, `draft` and `address` are the web app's business and are not passed on.
 */
sealed interface VoiceEvent {
  data class Heard(val text: String) : VoiceEvent

  data class Progress(val text: String) : VoiceEvent

  /** A tool starting, or [done]. */
  data class Tool(val name: String, val done: Boolean) : VoiceEvent

  /**
   * Something to show: a place ("map", "streetview": [lat], [lng], [maptype]), a camera ([entity]),
   * a picture Jarvis made ("image": [id]), a page it made ("page": [id]), the browser it works in
   * handed over ("browser": nothing else comes with it), or "close" for whatever is up. Its picture
   * comes from [Jarvis.pictureUrl]; the browser's, again and again, from [Jarvis.browserScreen].
   */
  data class Display(
      val kind: String,
      val label: String,
      val lat: Double? = null,
      val lng: Double? = null,
      val maptype: String? = null,
      val entity: String? = null,
      val id: String? = null,
  ) : VoiceEvent

  /** The answer's words. A failure ([error]) is still worth saying, and its audio may follow. */
  data class Result(val text: String, val error: Boolean) : VoiceEvent

  /** One sentence of the spoken answer as mp3; null when Jarvis couldn't voice that one. */
  data class Audio(val seq: Int, val last: Boolean, val mp3: ByteArray?) : VoiceEvent {
    override fun equals(other: Any?): Boolean =
        other is Audio && seq == other.seq && last == other.last && (mp3 === other.mp3 || mp3 != null && other.mp3 != null && mp3.contentEquals(other.mp3))

    override fun hashCode(): Int = (seq * 31 + last.hashCode()) * 31 + (mp3?.contentHashCode() ?: 0)

    override fun toString(): String = "Audio(seq=$seq, last=$last, mp3=${mp3?.size?.let { "$it bytes" }})"
  }

  companion object {
    /** The event in one frame, or null for one the TV has no use for (or can't draw). */
    fun from(o: JsonObject): VoiceEvent? =
        when (o.str("type")) {
          "transcript" -> Heard(o.str("text").orEmpty())
          "progress" -> o.str("text")?.takeIf { it.isNotBlank() }?.let { Progress(it) }
          "tool" -> o.str("name")?.let { Tool(it, o.str("phase") == "done") }
          "display" -> display(o)
          "result" -> Result(o.str("text").orEmpty(), o.bool("ok") == false)
          "error" -> Result(o.str("text") ?: "Something went wrong on the way to an answer.", true)
          "audio" -> {
            val a = o["audio"] as? JsonObject
            val mp3 = if (o.containsKey("audioError")) null else a?.str("data")?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            Audio(o.int("seq") ?: 0, o.bool("last") ?: false, mp3?.takeIf { it.isNotEmpty() })
          }
          else -> null
        }

    private fun display(o: JsonObject): Display? {
      val label = o.str("label").orEmpty().trim()
      return when (val kind = o.str("kind")) {
        "map", "streetview" -> {
          val lat = o.num("lat") ?: return null
          val lng = o.num("lng") ?: return null
          if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
          Display(kind, label, lat = lat, lng = lng, maptype = o.str("maptype")?.takeIf { it.isNotBlank() })
        }
        "camera" -> o.str("entity")?.takeIf { it.isNotBlank() }?.let { Display(kind, label.ifEmpty { "Camera" }, entity = it) }
        "image" -> o.str("image")?.takeIf { it.isNotBlank() }?.let { Display(kind, label, id = it) }
        "page" -> o.str("page")?.takeIf { it.isNotBlank() }?.let { Display(kind, label.ifEmpty { "Page" }, id = it) }
        "browser", "close" -> Display(kind, label)
        else -> null
      }
    }
  }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
