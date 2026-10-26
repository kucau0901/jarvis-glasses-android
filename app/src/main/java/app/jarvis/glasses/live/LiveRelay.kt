package app.jarvis.glasses.live

import app.jarvis.glasses.jarvis.SseParser
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What a GPT-Live session's `oai-events` channel says, and what the app answers: the same loop as
 * Jarvis's web app (`onEvent` in src/app/main.ts, src/app/delegate.ts there).
 *
 * The session is made with client delegation, so when the model wants Jarvis's tools it sends
 * `session.delegation.created` with no task text: the app keeps the conversation from the
 * transcript deltas and sends all of it to `/api/v1/stream`. Progress goes back as silent
 * `session.thinking.append`, the answer as `session.commentary.append` for the model to say.
 */
class LiveRelay(
    private val scope: CoroutineScope,
    private val delegate: suspend (id: String, transcript: List<Turn>, onEvent: suspend (JsonObject) -> Unit) -> Unit,
    private val send: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
  private val _turns = MutableStateFlow<List<Turn>>(emptyList())
  val turns: StateFlow<List<Turn>> = _turns

  private var work: Job? = null

  /** When someone last spoke or Jarvis last finished, for closing a quiet session. */
  @Volatile var lastActivity: Long = now()
    private set

  val working: Boolean
    get() = work?.isActive == true

  fun seed(history: List<Turn>) {
    _turns.value = history
  }

  fun onMessage(raw: String) {
    val ev = runCatching { SseParser.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    when (ev.str("type")) {
      "session.input_transcript.delta" -> heard("user", ev.str("delta"))
      "session.output_transcript.delta" -> heard("assistant", ev.str("delta"))
      "session.delegation.created" -> {
        val d = ev["delegation"] as? JsonObject ?: return
        val id = d.str("id") ?: return
        if (d.str("target") != "client") return
        // One at a time: a second request would race the first and both would speak.
        work?.cancel()
        work = scope.launch { run(id, _turns.value) }
      }
    }
  }

  fun stop() {
    work?.cancel()
  }

  private fun heard(role: String, delta: String?) {
    if (delta.isNullOrEmpty()) return
    lastActivity = now()
    val turns = _turns.value
    val last = turns.lastOrNull()
    _turns.value =
        if (last != null && last.role == role) turns.dropLast(1) + Turn(role, last.text + delta) else turns + Turn(role, delta.trimStart())
  }

  private suspend fun run(id: String, transcript: List<Turn>) {
    var answered = false
    try {
      delegate(id, transcript) { ev ->
        when (ev.str("type")) {
          "progress" -> ev.str("text")?.let { send(thinking("Progress: $it.", id)) }
          "result", "error" -> {
            answered = true
            ev.str("text")?.takeIf { it.isNotBlank() }?.let { text -> chunks(text).forEach { send(commentary(it, id)) } }
          }
        }
      }
      if (!answered) send(commentary(WENT_WRONG, id))
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      send(commentary(WENT_WRONG, id))
    } finally {
      lastActivity = now()
    }
  }

  companion object {
    private const val WENT_WRONG = "Tell the user something went wrong on the way to your backend."

    /** commentary.append and thinking.append are each capped at 500 tokens. */
    private const val CHUNK_CHARS = 1200

    fun chunks(text: String): List<String> {
      val out = mutableListOf<String>()
      var rest = text
      while (rest.length > CHUNK_CHARS) {
        // On a sentence end where possible, so each spoken piece stands alone.
        val window = rest.take(CHUNK_CHARS)
        val cut = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "))
        val at = if (cut > CHUNK_CHARS / 2) cut + 1 else CHUNK_CHARS
        out += rest.take(at).trim()
        rest = rest.drop(at).trim()
      }
      if (rest.isNotEmpty()) out += rest
      return out
    }

    fun commentary(content: String, id: String) =
        buildJsonObject { put("type", "session.commentary.append"); put("content", content); put("delegation_id", id) }.toString()

    fun thinking(content: String, id: String) =
        buildJsonObject { put("type", "session.thinking.append"); put("content", content); put("delegation_id", id) }.toString()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
  }
}
