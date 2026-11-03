package app.jarvis.glasses.chat

import app.jarvis.glasses.conversation.Memory
import app.jarvis.glasses.hub.AlertFrom
import app.jarvis.glasses.hub.MadePage
import app.jarvis.glasses.hub.Said
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one conversation with Jarvis, as the web app has one: what Chat shows, what Talk and Live
 * start from and add to, and what alert replies join. Signed in as a person it is Jarvis's
 * (`/api/hub/conversation`, theirs alone, on every screen of theirs); with a device token it is
 * only here.
 *
 * A question goes with only the recent part of it ([recent]), as the web app's does.
 */
class ChatLog(
    private val scope: CoroutineScope,
    private val load: suspend () -> List<Said>,
    private val save: suspend (List<Said>) -> Unit,
    private val wipe: suspend () -> Unit,
    private val person: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) : Memory {
  private val _said = MutableStateFlow<List<Said>>(emptyList())
  val said: StateFlow<List<Said>> = _said

  private val _kinds = MutableStateFlow<Map<String, String>>(emptyMap())
  /** Rows only shown, never kept or asked with, by id: "limit" for a notice, "bad" for a failure. */
  val kinds: StateFlow<Map<String, String>> = _kinds

  private val _shown = MutableStateFlow<Map<String, List<Shown>>>(emptyMap())
  /**
   * What an answer put on the screen, by its id: drawn under it while the app is open, never kept,
   * as the web's stage keeps none of it (a page goes with its answer instead, [Said.page]).
   */
  val shown: StateFlow<Map<String, List<Shown>>> = _shown

  // One save at a time, in the order said: Jarvis keeps them in the order they arrive.
  private val saving = Mutex()

  /** Jarvis's copy, with anything said here that it doesn't have yet. */
  suspend fun refresh() {
    if (!person()) return
    val kept = load()
    _said.update { merged(kept, it) }
  }

  /** Shown, not kept (yet): a question waiting for its answer, or a notice or failure ([kind]). */
  fun show(s: Said, kind: String = "") {
    if (kind.isNotEmpty()) _kinds.update { it + (s.id to kind) }
    add(listOf(s))
  }

  /** Said, and kept: those shown already are sent too, but not shown twice. */
  fun keep(said: List<Said>) {
    if (said.isEmpty()) return
    add(said)
    if (person()) scope.launch { saving.withLock { runCatching { save(said) } } }
  }

  /** Talk and Live: [turns] just said, after [alert] when they reply to one (kept by its own id, so not twice). */
  override fun keep(turns: List<Turn>, alert: Alert?) {
    val at = now()
    val opening = alert?.let { listOf(said(it, at)) }.orEmpty()
    val said = opening + turns.filter { it.text.isNotBlank() }.map { Said(Said.newId(), at, it.role, it.text.trim()) }
    keep(said)
    // What Live showed goes under the answer Jarvis says about it.
    said.lastOrNull { it.role == "assistant" }?.let { a -> takeWaiting().takeIf { it.isNotEmpty() }?.let { showed(a.id, it) } }
  }

  // What Live has shown that no answer has taken yet: on WebRTC's thread and the relay's.
  private val waiting = mutableListOf<Shown>()

  private fun takeWaiting(): List<Shown> = synchronized(waiting) { waiting.toList().also { waiting.clear() } }

  /**
   * What Jarvis put on the screen in a Live session: a page at once, kept on its own as the web
   * keeps one no answer took; "close that" at once; anything else under the answer Jarvis says
   * next, which is finished only once it has been said.
   */
  fun liveShowed(s: Shown) {
    when (s) {
      is Shown.Page -> keep(listOf(Said(Said.newId(), now(), "assistant", s.title, page = MadePage(s.id, s.title))))
      Shown.Close -> {
        takeWaiting()
        showed("", listOf(Shown.Close))
      }
      else -> synchronized(waiting) { waiting += s }
    }
  }

  /** The Live session ended: what it showed that no answer took goes under the last row, not lost. */
  fun liveEnded() {
    val left = takeWaiting()
    val last = _said.value.lastOrNull() ?: return
    if (left.isNotEmpty()) showed(last.id, left)
  }

  /** [what] the answer [id] showed, under it; "close that" ([Shown.Close]) takes all that is shown off. */
  fun showed(id: String, what: List<Shown>) {
    val closed = what.lastIndexOf(Shown.Close)
    if (closed >= 0) _shown.value = emptyMap()
    val drawn = what.drop(closed + 1).filter { it !is Shown.Page }
    if (drawn.isNotEmpty()) _shown.update { it + (id to it[id].orEmpty() + drawn) }
  }

  override fun recent(): List<Turn> = transcript(_said.value, _kinds.value.keys, now())

  /**
   * An alert as it arrives: in Chat at once, as Jarvis keeps it (by its id, under its title, a
   * bot's with its skill), so what is said next has it to refer to. Jarvis has kept it already, so
   * it is not sent.
   */
  fun arrived(alert: Alert) = add(listOf(said(alert, now())))

  private fun said(a: Alert, at: Long) = Said(a.id, at, "assistant", a.text, alert = AlertFrom(a.source, a.title), skill = a.skill, look = a.look)

  /** Emptied here and, signed in as a person, on every screen of theirs. */
  suspend fun clear() {
    if (person()) wipe()
    _said.value = emptyList()
    _kinds.value = emptyMap()
    _shown.value = emptyMap()
  }

  // Live's turns arrive on WebRTC's thread, Chat's on the main one.
  private fun add(said: List<Said>) =
      _said.update { was ->
        val have = was.map { it.id }.toSet()
        was + said.filter { it.id !in have }
      }

  companion object {
    const val RECENT_MS = 3 * 3_600_000L
    const val RECENT_TURNS = 20

    /** Jarvis's [kept], then what [here] has that it doesn't (not sent yet, or only shown), in time order. */
    fun merged(kept: List<Said>, here: List<Said>): List<Said> {
      val ids = kept.map { it.id }.toSet()
      return (kept + here.filter { it.id !in ids }).sortedBy { it.at }
    }

    /**
     * What a question goes with: the last twenty turns of the past three hours, as the web app's
     * (src/app/history.ts there), without rows only [shown]. What a routine, job or alert said is
     * named, and a bot's words are quoted as its own ([Said.read]); a page set apart is not words.
     */
    fun transcript(said: List<Said>, shown: Set<String>, now: Long): List<Turn> =
        said
            .filter { it.id !in shown && !it.apart && (now - it.at < RECENT_MS || it.at == 0L) }
            .takeLast(RECENT_TURNS)
            .map { s -> Turn(s.role, s.read) }
  }
}
