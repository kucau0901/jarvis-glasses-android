package app.jarvis.glasses.g2

import app.jarvis.glasses.chat.Markdown
import app.jarvis.glasses.hub.Said
import java.text.Normalizer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Characters a line of the chat holds in the glasses' font, and the lines it shows at once: to be tried on the glasses. */
const val G2_LINE_CHARS = 44
const val G2_ROWS = 6

/** How far back the chat on the glasses reaches: the conversation's latest messages. */
const val G2_MOST_SAID = 40

/** [text] in what the glasses' font has, plain ASCII: quotes, dashes, the ellipsis and bullets swapped, accents taken off, the rest dropped. */
fun g2Ascii(text: String): String {
  val swapped =
      text
          .replace('‘', '\'').replace('’', '\'').replace('“', '"').replace('”', '"')
          .replace("…", "...").replace('–', '-').replace('—', '-').replace('•', '-').replace('·', '-')
          .replace(' ', ' ')
  return buildString { for (c in Normalizer.normalize(swapped, Normalizer.Form.NFD)) if (c == '\n' || c in ' '..'~') append(c) }
}

/**
 * [text] in lines of at most [width] characters, broken at spaces (a word longer than a line is
 * cut), each line after the first starting with [indent]. Paragraphs start a new line.
 */
fun g2Wrap(text: String, width: Int, indent: String = ""): List<String> {
  val lines = mutableListOf<String>()
  var line = ""
  fun start() = if (lines.isEmpty()) "" else indent
  for (paragraph in text.split('\n')) {
    if (line.isNotEmpty()) lines += line.also { line = "" }
    for (w in paragraph.split(' ').filter { it.isNotEmpty() }) {
      val lead = if (line.isEmpty()) start() else "$line "
      if (lead.length + w.length <= width) {
        line = lead + w
        continue
      }
      if (line.isNotEmpty()) lines += line.also { line = "" }
      var word = w
      while (start().length + word.length > width) {
        val room = maxOf(1, width - start().length)
        lines += start() + word.take(room)
        word = word.drop(room)
      }
      line = start() + word
    }
  }
  if (line.isNotEmpty()) lines += line
  return lines
}

/** Whether Jarvis said it first: an alert, a routine's or a job's. */
val Said.g2Alert: Boolean
  get() = role == "assistant" && (alert != null || routine != null || job != null)

/** The conversation as the chat shows it, oldest first: each message under who said it ("You", "Jarvis", an alert's title). */
fun g2ChatLines(said: List<Said>, width: Int = G2_LINE_CHARS): List<String> =
    said.takeLast(G2_MOST_SAID).flatMap { s ->
      val who = if (s.role == "user") "You" else s.from ?: "Jarvis"
      g2Wrap(g2Ascii("$who: ${Markdown.plain(s.text)}"), width, "  ")
    }

/** The most lines the chat can be scrolled up by. */
fun g2MostUp(lines: Int, rows: Int = G2_ROWS) = (lines - rows).coerceAtLeast(0)

/** The [rows] lines shown when scrolled [up] lines above the newest. */
fun g2Window(lines: List<String>, up: Int, rows: Int = G2_ROWS): List<String> {
  val top = (lines.size - rows - up.coerceIn(0, g2MostUp(lines.size, rows))).coerceAtLeast(0)
  return lines.subList(top, minOf(lines.size, top + rows))
}

/** A page of Jarvis's on the glasses: [header] across the top, [body] framed, [footer] along the bottom. */
fun g2Page(header: String, body: List<String>, footer: String): List<G2Box> =
    listOf(
        G2Box(1, 8, 0, EvenHub.WIDTH - 16, 36, header),
        G2Box(2, 0, 38, EvenHub.WIDTH, 212, body.joinToString("\n"), border = true),
        G2Box(3, 8, 252, EvenHub.WIDTH - 16, 36, footer),
    )

/** The time for the top line, "14:05". */
fun g2Clock(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * Jarvis's chat on the G2: the conversation Chat has ([history]), newest at the bottom, under a
 * line saying what Jarvis is doing and above one saying what a touch does now. A swipe up shows
 * earlier lines and a swipe down newer ones; at the newest, a swipe down asks for another before
 * the chat is left, so one stray swipe doesn't close it. Anything new brings it back to the newest.
 *
 * What is heard ([asked]) and what Jarvis says that isn't kept ([said]: a failure) are shown here
 * until the conversation has them. Called from the conversation's coroutines and the answer's
 * stream alike, so one at a time.
 */
class G2Chat(private val draw: (List<G2Box>) -> Unit, private val clock: () -> String = ::g2Clock) {
  enum class Mode { IDLE, LISTENING, THINKING }

  private var history = emptyList<Said>()
  private val extra = mutableListOf<Said>()
  private var only: ((Said) -> Boolean)? = null
  private var leave = "close"
  private var shown = false
  private var up = 0
  private var closing = false
  private var mode = Mode.IDLE
  private var status = ""

  /**
   * On the glasses from the newest: all of the conversation, or only what [only] picks (the
   * alerts). [leave] is what the last swipe down does, as the bottom line says it.
   */
  @Synchronized
  fun open(only: ((Said) -> Boolean)? = null, leave: String = "close") {
    this.only = only
    this.leave = leave
    shown = true
    up = 0
    closing = false
    mode = Mode.IDLE
    status = ""
    render()
  }

  /** Off the glasses: something else is drawn there now. */
  @Synchronized
  fun hide() {
    shown = false
    extra.clear()
  }

  /** The conversation as Chat has it now. */
  @Synchronized
  fun history(said: List<Said>) {
    val grew = said.lastOrNull()?.id != history.lastOrNull()?.id
    history = said
    // Kept now, so shown as the conversation has it, not twice.
    val recent = said.takeLast(6)
    extra.removeAll { e -> recent.any { it.role == e.role && it.text.trim() == e.text } }
    if (grew) newest()
    render()
  }

  /** What the wearer was heard to say, shown at once. */
  @Synchronized
  fun asked(text: String) {
    extra += Said(Said.newId(), 0, "user", text.trim())
    newest()
    render()
  }

  /** What Jarvis said: shown, unless the conversation has it already (an answer kept, an alert). */
  @Synchronized
  fun said(text: String) {
    val t = text.trim()
    val known = (history.takeLast(6) + extra).any { it.role == "assistant" && it.text.isNotBlank() && t.contains(it.text.trim()) }
    if (!known && t.isNotEmpty()) extra += Said(Said.newId(), 0, "assistant", t)
    mode = Mode.IDLE
    status = ""
    newest()
    render()
  }

  @Synchronized
  fun listening() {
    mode = Mode.LISTENING
    status = "Listening..."
    newest()
    render()
  }

  @Synchronized
  fun thinking() {
    mode = Mode.THINKING
    status = "Thinking..."
    render()
  }

  /** What Jarvis is doing meanwhile ("Checking the calendar"), while it thinks. */
  @Synchronized
  fun progress(text: String) {
    if (mode != Mode.THINKING) return
    status = g2Ascii(text).trim().trimEnd('.', ' ') + "..."
    render()
  }

  /** Waiting for a touch again, [note] in the top line ("Nothing heard"). */
  @Synchronized
  fun idle(note: String = "") {
    mode = Mode.IDLE
    status = note
    render()
  }

  /** Something to see, in the top line while nothing else is going on ("New: Gate"). */
  @Synchronized
  fun notice(text: String) {
    if (mode != Mode.IDLE) return
    status = g2Ascii(text)
    render()
  }

  @Synchronized
  fun up() {
    closing = false
    up = minOf(up + STEP, g2MostUp(lines().size))
    render()
  }

  /** Newer lines; at the newest, the first asks for a second, which leaves: false. */
  @Synchronized
  fun down(): Boolean {
    if (up > 0) {
      up = maxOf(0, up - STEP)
      closing = false
    } else if (closing) {
      return false
    } else {
      closing = true
    }
    render()
    return true
  }

  private fun newest() {
    up = 0
    closing = false
  }

  private fun lines(): List<String> {
    val said = (only?.let { history.filter(it) } ?: history) + extra
    return g2ChatLines(said).ifEmpty { listOf(if (only != null) "No alerts yet." else "Nothing said yet. Tap, then ask Jarvis anything.") }
  }

  private fun render() {
    if (!shown) return
    val lines = lines()
    val title = if (only != null) "JARVIS ALERTS" else "JARVIS"
    draw(g2Page("$title  |  ${status.ifEmpty { clock() }}", g2Window(lines, up), footer(lines.size)))
  }

  private fun footer(lines: Int): String =
      when {
        mode == Mode.LISTENING -> "Speak now  |  swipe down: stop"
        mode == Mode.THINKING -> "One moment..."
        closing -> "Swipe down again to $leave  |  tap: talk"
        up >= g2MostUp(lines) && up > 0 -> "Start of chat  |  swipe down: newer"
        up > 0 -> "tap: talk  |  swipe up: earlier, down: newer"
        lines > G2_ROWS -> "tap: talk  |  swipe up: earlier"
        else -> "tap: talk  |  swipe down: $leave"
      }

  private companion object {
    /** Lines a swipe scrolls by: a screenful, keeping one line to read on from. */
    const val STEP = G2_ROWS - 1
  }
}
