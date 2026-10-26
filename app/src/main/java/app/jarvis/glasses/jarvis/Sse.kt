package app.jarvis.glasses.jarvis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Jarvis's event streams (`/api/v1/voice` and `/api/v1/stream` with `Accept: text/event-stream`)
 * are `data: {json}` frames separated by a blank line, with no `event:`, `id:` or `retry:` lines
 * and no `[DONE]` (docs/api.md, "Streaming"). Fed one line at a time; a frame that is not a JSON
 * object is skipped rather than ending the stream.
 */
class SseParser(private val onEvent: (JsonObject) -> Unit) {
  private val data = StringBuilder()

  fun line(raw: String) {
    val line = raw.removeSuffix("\r")
    when {
      line.isEmpty() -> flush()
      line.startsWith("data:") -> {
        if (data.isNotEmpty()) data.append('\n')
        data.append(line.removePrefix("data:").removePrefix(" "))
      }
      // Comments (": keep-alive") and any other field are not used by Jarvis.
      else -> Unit
    }
  }

  /** Call at the end of the body too: a last frame without its blank line still counts. */
  fun flush() {
    if (data.isEmpty()) return
    val text = data.toString()
    data.clear()
    runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()?.let(onEvent)
  }

  companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
