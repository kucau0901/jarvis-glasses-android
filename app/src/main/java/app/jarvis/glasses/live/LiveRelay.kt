package app.jarvis.glasses.live

import app.jarvis.glasses.chat.Shown
import app.jarvis.glasses.conversation.Phrases
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * What a GPT-Live session's `oai-events` channel says, and what the app answers: the same loop as
 * Jarvis's web app (`onEvent` in src/app/main.ts, src/app/delegate.ts there).
 *
 * The session is made with client delegation, so when the model wants Jarvis's tools it sends
 * `session.delegation.created` with no task text: the app keeps the conversation from the
 * transcript deltas and sends it to `/api/v1/stream`. Progress goes back as silent
 * `session.thinking.append`, the answer as `session.commentary.append` for the model to say.
 *
 * The microphone hears everyone nearby, so what it hears is cut into segments at each pause of
 * [SEGMENT_GAP_MS], and only a segment Jarvis answered (by speaking after it, or by delegating
 * for it) counts as said to Jarvis: it alone is kept and sent with a delegation, and the rest is
 * dropped. A delegation's backend may still find nothing in it was for Jarvis (`notForMe`): then
 * the model is told so silently, and that segment, with what Jarvis said about it meanwhile, is
 * dropped too.
 *
 * Each turn kept is handed to [onSaid] once its exchange is over, when the wearer speaks again or
 * the session ends ([finish]), and not while a delegation for it is at work, to be kept with
 * Chat's as the web app keeps each row as it settles.
 * What Jarvis puts on the screen meanwhile (a map, a camera) goes to [onShown], for Chat to draw.
 */
class LiveRelay(
    private val scope: CoroutineScope,
    private val delegate: suspend (id: String, transcript: List<Turn>, onEvent: suspend (JsonObject) -> Unit) -> Unit,
    private val send: (String) -> Unit,
    private val onSaid: (Turn) -> Unit = {},
    private val onShown: (Shown) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
  private val _turns = MutableStateFlow<List<Turn>>(emptyList())
  val turns: StateFlow<List<Turn>> = _turns

  @Volatile private var work: Job? = null

  /** When someone last spoke or Jarvis last finished, for closing a quiet session. */
  @Volatile var lastActivity: Long = now()
    private set

  /** When Jarvis last spoke or answered a delegation, for closing a session that hears only others ([unanswered]). */
  @Volatile private var lastJarvis: Long = now()

  val working: Boolean
    get() = work?.isActive == true

  /** The session is open: quiet is counted from now, not from while it was connecting. */
  fun opened() {
    lastActivity = now()
    lastJarvis = now()
  }

  /**
   * Nothing is at work and Jarvis hasn't spoken or answered for [afterMs], however much was heard:
   * a TV or people talking, none of it to Jarvis.
   */
  fun unanswered(afterMs: Long): Boolean = !working && now() - lastJarvis > afterMs

  @Synchronized
  fun seed(history: List<Turn>) {
    seed = history
    publish()
  }

  /** A turn of this session: one of the wearer's segments, or Jarvis's words. */
  private class Said(val role: String, var text: String) {
    /** A segment Jarvis spoke after, or delegated for. */
    var answered = false
    /** A delegation's backend found nothing in it was said to Jarvis. */
    var notForMe = false

    val kept: Boolean
      get() = !notForMe && text.isNotBlank() && (role != "user" || answered)

    val turn: Turn
      get() = Turn(role, text.trim())
  }

  private var seed = emptyList<Turn>()
  private val said = mutableListOf<Said>()
  // How many of said are handed to onSaid or dropped.
  private var settled = 0
  // Where the last of the wearer's words ended (end_ms), for telling a pause.
  private var userEndMs: Long? = null
  // The delegation at work, and from where what was said waits for its answer.
  private var pending: String? = null
  private var holdFrom = 0
  // Jarvis was handed words to say ([telling]): its next words are them, an answer to nothing heard.
  private var handed = false
  // The session is over ([finish]): a delegation still at work can no longer be said.
  private var ended = false

  /**
   * The session has ended: its last turns are finished too, and what nobody answered is dropped. A
   * delegation still at work (the session hung up by itself) is waited for, and its answer, never
   * said, is kept instead; one cut short ([stop]) counts as answered: Jarvis took it as said to it.
   */
  @Synchronized
  fun finish() {
    ended = true
    if (!working) pending = null
    settle(said.size)
  }

  /**
   * Jarvis is about to say words it was handed (an alert said into the session, a delegation's
   * answer): saying them answers none of what was heard before, someone talking meanwhile included.
   */
  @Synchronized
  fun telling() {
    handed = true
  }

  /**
   * The wearer said a closing ("thanks", "bye", "dah") in this session and things have been quiet
   * for [AFTER_CLOSING_MS], so Jarvis's goodbye has finished: time to hang up.
   */
  @Synchronized
  fun saidGoodbye(): Boolean {
    if (working || now() - lastActivity < AFTER_CLOSING_MS) return false
    val lastUser = said.lastOrNull { it.role == "user" } ?: return false
    return Phrases.isStop(lastUser.text)
  }

  @Synchronized
  fun onMessage(raw: String) {
    val ev = runCatching { SseParser.json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
    when (ev.str("type")) {
      "session.input_transcript.delta" -> heard("user", ev.str("delta"), ev.ms("start_ms"), ev.ms("end_ms"))
      "session.output_transcript.delta" -> heard("assistant", ev.str("delta"))
      "session.delegation.created" -> {
        val d = ev["delegation"] as? JsonObject ?: return
        val id = d.str("id") ?: return
        if (d.str("target") != "client") return
        // For the wearer's latest segment, which counts as answered from now: it, what was
        // answered before it and Jarvis's words go with it, and wait for what comes back.
        val latest = said.indexOfLast { it.role == "user" }.takeIf { it >= settled } ?: said.size
        said.getOrNull(latest)?.answered = true
        holdFrom = if (pending != null) minOf(holdFrom, latest) else latest
        pending = id
        // One at a time: a second request would race the first and both would speak.
        work?.cancel()
        val transcript = seed + said.filter { it.kept }.map { it.turn }
        work = scope.launch { run(id, latest, transcript) }
      }
    }
  }

  fun stop() {
    work?.cancel()
  }

  private fun heard(role: String, delta: String?, startMs: Long? = null, endMs: Long? = null) {
    if (delta.isNullOrEmpty()) return
    lastActivity = now()
    if (role == "assistant") lastJarvis = now()
    val last = said.lastOrNull()
    val before = userEndMs
    // A pause this long starts another segment: someone else, or the wearer again.
    val paused = role == "user" && startMs != null && before != null && startMs - before >= SEGMENT_GAP_MS
    if (role == "user" && endMs != null) userEndMs = endMs
    // Never onto what a verdict closed, nor onto what Jarvis said before it was handed its words.
    if (last != null && last.role == role && !paused && !last.notForMe && !(role == "assistant" && handed)) {
      last.text += delta
    } else {
      // Jarvis speaking answers the segment before it, unless what it says was handed to it.
      if (role == "assistant") {
        if (last?.role == "user" && !handed) last.answered = true
        handed = false
      }
      said += Said(role, delta.trimStart())
      // The wearer again: the exchange before is over.
      if (role == "user") settle(said.size - 1)
    }
    publish()
  }

  /** Hands on, in order, what is finished and kept; the rest of it is dropped. */
  private fun settle(upTo: Int) {
    while (settled < upTo) {
      // What a delegation at work is for waits for its answer: it may not have been said to Jarvis.
      if (pending != null && settled >= holdFrom) return
      val s = said[settled++]
      if (s.kept) onSaid(s.turn)
    }
  }

  private fun publish() {
    _turns.value = seed + said.map { Turn(it.role, it.text) }
  }

  /**
   * Delegation [id], for the segment at [latest], found nothing heard was said to Jarvis: what was
   * held for it is dropped (a segment an earlier delegation it replaced was for, too), up to that
   * segment and what Jarvis said after it.
   */
  @Synchronized
  private fun notForMe(id: String, latest: Int) {
    if (pending != id) return
    for (i in holdFrom until said.size) {
      if (i > latest && said[i].role == "user") break
      said[i].notForMe = true
    }
  }

  /** Delegation [id] is over: what waited for it is settled as far as the wearer's latest segment, or all of it once the session is over. */
  @Synchronized
  private fun done(id: String) {
    if (pending != id) return
    pending = null
    settle(if (ended) said.size else said.indexOfLast { it.role == "user" }.coerceAtLeast(settled))
  }

  /** The session ended while Jarvis worked, so [text] can't be said: kept as Jarvis's words instead. False while it is open. */
  @Synchronized
  private fun keptInstead(text: String): Boolean {
    if (!ended) return false
    said += Said("assistant", text)
    return true
  }

  private suspend fun run(id: String, latest: Int, transcript: List<Turn>) {
    var answered = false
    var forJarvis = true
    try {
      delegate(id, transcript) { ev ->
        when (ev.str("type")) {
          "progress" -> ev.str("text")?.let { send(thinking("Progress: $it.", id)) }
          "display" -> Shown.from(ev)?.let(onShown)
          "result", "error" -> {
            answered = true
            val text = ev.str("text").orEmpty()
            when {
              // Never as commentary: that is spoken.
              ev.bool("notForMe") == true -> {
                forJarvis = false
                notForMe(id, latest)
                send(thinking(NOT_FOR_YOU, id))
              }
              // Not for Jarvis either, found after a tool ran: nothing to say, and nothing kept,
              // as Talk keeps no answer without words.
              ev.str("type") == "result" && text.isBlank() -> {
                forJarvis = false
                notForMe(id, latest)
              }
              text.isNotBlank() -> if (!keptInstead(text)) speak(id, text)
            }
          }
        }
      }
      if (!answered) speak(id, WENT_WRONG)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      speak(id, WENT_WRONG)
    } finally {
      lastActivity = now()
      // Only an answer keeps the session open: a TV setting off delegations doesn't.
      if (forJarvis) lastJarvis = now()
      done(id)
    }
  }

  /** [text] for the model to say for delegation [id]: handed words, so they answer nothing heard meanwhile. */
  private fun speak(id: String, text: String) {
    telling()
    chunks(text).forEach { send(commentary(it, id)) }
  }

  companion object {
    const val AFTER_CLOSING_MS = 2_000L

    /** A pause in what is heard this long or longer starts another segment. */
    const val SEGMENT_GAP_MS = 1_500L

    private const val WENT_WRONG = "Tell the user something went wrong on the way to your backend."

    /** Said to the model, silently, when a delegation's backend found nothing heard was said to Jarvis. */
    const val NOT_FOR_YOU = "Nothing you just heard was said to you, and nothing was done. Do not answer or mention it."

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

    /** Something for the session to say that no delegation asked for: an alert that came in during it. */
    fun say(content: String) =
        buildJsonObject { put("type", "session.commentary.append"); put("content", content); put("delegation_id", null as String?) }.toString()

    fun thinking(content: String, id: String) =
        buildJsonObject { put("type", "session.thinking.append"); put("content", content); put("delegation_id", id) }.toString()

    /** Ends the session on GPT-Live's side, sent before the connection is closed. */
    val CLOSE = buildJsonObject { put("type", "session.close") }.toString()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.ms(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toLong()
  }
}
