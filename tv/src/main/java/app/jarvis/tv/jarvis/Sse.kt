package app.jarvis.tv.jarvis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Jarvis's answer stream (`/api/v1/voice` with `Accept: text/event-stream`) is `data: {json}`
 * frames separated by a blank line, with no `event:`, `id:` or `retry:` lines and no `[DONE]`.
 * Fed one line at a time; a frame that is not a JSON object is skipped rather than ending the
 * stream. The phone's own (app/…/jarvis/Sse.kt), until it moves into core.
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
      // Comments (": ping") keep the connection open while Jarvis works; nothing else is used.
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
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
  }
}
