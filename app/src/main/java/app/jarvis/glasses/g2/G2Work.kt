package app.jarvis.glasses.g2

import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobWords
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.needsPhone

/**
 * A background job's question on the G2 (an alert with `ask`): who asks and the question, then its
 * choices, *Say it* (the microphone) and *Go on without me*, one chosen, moved with a swipe on the
 * ring or touchpad and taken with a tap. With [page]'s `said`, what came of it in their place. The
 * glasses' font only: the job's words made plain ASCII ([g2Ascii]). No Android, so tests read it.
 */
class G2Ask(private val title: String, private val question: String, choices: List<String>) {
  val options: List<String> = choices + listOf(SAY, SKIP)
  private var at = 0

  val chosen: String
    get() = options[at]

  fun up() {
    if (at > 0) at--
  }

  fun down() {
    if (at < options.size - 1) at++
  }

  fun page(said: String? = null): List<G2Box> {
    val asked = g2Wrap(g2Ascii(question), G2_LINE_CHARS - 2).take(2)
    val body = if (said != null) asked + listOf("", g2Ascii(said)) else asked + listOf("") + window(options.map(::g2Ascii), at, G2_ROWS - asked.size - 1)
    return g2Page(g2Ascii("Your job asks: " + title.removePrefix("A question: ")).take(G2_LINE_CHARS), body, if (said != null) "tap: back" else "swipe: choose        tap: answer")
  }

  companion object {
    const val SAY = "Say it"
    const val SKIP = "Go on without me"
  }
}

/**
 * Jarvis's Jobs on the G2: what waits for an OK, then each job at work, a line each, one chosen. A
 * tap on an OK that needs no reading arms it, and a second tap approves it, as the watch may; one
 * with more to read says to read it on the phone ([needsPhone]). A tap on a job opens its list
 * ([jobPage]), where a tap arms Stop and a second tap stops it.
 */
class G2Work(val approvals: List<Proposal>, val jobs: List<Job>) {
  val size: Int
    get() = approvals.size + jobs.size

  private var at = 0
  /** A first tap made on the chosen row: the next one does it. */
  var armed = false
    private set

  /** The chosen row: an approval, or a job. */
  val chosen: Any?
    get() = approvals.getOrNull(at) ?: jobs.getOrNull(at - approvals.size)

  fun up() {
    if (at > 0) at--
    armed = false
  }

  fun down() {
    if (at < size - 1) at++
    armed = false
  }

  /** The chosen OK tapped: armed, unless it needs the phone. True when a second tap should now approve it. */
  fun tapOk(): Boolean {
    val p = chosen as? Proposal ?: return false
    if (p.needsPhone) return false
    if (armed) {
      armed = false
      return true
    }
    armed = true
    return false
  }

  fun line(p: Proposal): String = (if (p.needsPhone) "OK? (on your phone) " else "OK? ") + p.says

  fun line(j: Job): String =
      j.title + " - " + (if (j.status == "running" && j.engine == "jarvis") JobWords.nowDoing(j) else JobWords.status(j)) + (JobWords.progress(j.todo)?.let { " - $it" } ?: "")

  fun page(note: String? = null): List<G2Box> {
    val lines = approvals.map(::line) + jobs.map(::line)
    val body = if (lines.isEmpty()) listOf("Nothing at work, and nothing waits for an OK.") else window(lines.map(::g2Ascii), at, G2_ROWS - if (note != null) 2 else 0)
    val footer =
        when {
          lines.isEmpty() -> "tap: back"
          armed -> "tap again: ${g2Ascii((chosen as? Proposal)?.verb ?: "Approve").lowercase()}"
          else -> "swipe: choose   tap: open   double tap: back"
        }
    return g2Page("JOBS", body + listOfNotNull(note?.let { "" }, note?.let(::g2Ascii)), footer)
  }

  companion object {
    /** One job: what it is doing, then its list, its state said; [armed], a tap has asked to stop it. */
    fun jobPage(j: Job, armed: Boolean, note: String? = null): List<G2Box> {
      val over = j.status != "running"
      val lines = listOf(g2Ascii(if (over) JobWords.status(j) else JobWords.nowDoing(j))) +
          j.todo.map { g2Ascii("${JobWords.todoWord(it, over)}: ${it.text}") } + listOfNotNull(note?.let(::g2Ascii))
      return g2Page(g2Ascii(j.title).take(G2_LINE_CHARS), lines.flatMap { g2Wrap(it, G2_LINE_CHARS, "  ") }.take(G2_ROWS), if (over) "swipe: back" else if (armed) "tap again: stop it" else "tap: stop   swipe: back")
    }
  }
}

/** [rows] lines of [items] around the chosen one ([at]), marked with ">". */
internal fun window(items: List<String>, at: Int, rows: Int): List<String> {
  val n = rows.coerceAtLeast(1)
  val top = (at - n / 2).coerceIn(0, (items.size - n).coerceAtLeast(0))
  return items.subList(top, minOf(items.size, top + n)).mapIndexed { i, s -> (if (top + i == at) "> " else "  ") + s.take(G2_LINE_CHARS - 2) }
}
