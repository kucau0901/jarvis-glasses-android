package app.jarvis.glasses.g2

/** What one page of the G2's display holds, with room for its hint: it gives up at about 400 to 500. */
const val G2_PAGE_CHARS = 300

/** The most pages an alert is shown in; the rest is in Chat. */
const val G2_MOST_PAGES = 6

private const val MORE = " [more in Chat]"

/**
 * [text] cut into pages of at most [size] characters each, at a paragraph, a sentence or a word
 * where one is near the end of the page, and in no more than [most]: the last says the rest is in
 * Chat.
 */
fun g2Pages(text: String, size: Int = G2_PAGE_CHARS, most: Int = G2_MOST_PAGES): List<String> {
  val pages = mutableListOf<String>()
  var rest = text.trim()
  while (rest.length > size) {
    if (pages.size == most - 1) {
      pages += cut(rest, size - MORE.length).trimEnd() + MORE
      return pages
    }
    val at = cut(rest, size).length
    pages += rest.substring(0, at).trim()
    rest = rest.substring(at).trim()
  }
  if (rest.isNotEmpty() || pages.isEmpty()) pages += rest
  return pages
}

/** The start of [text] that fits in [size], ending at the best break near its end. */
private fun cut(text: String, size: Int): String {
  val head = text.take(size)
  val enough = size / 2
  val paragraph = head.lastIndexOf("\n\n")
  if (paragraph >= enough) return head.substring(0, paragraph)
  val sentence = maxOf(head.lastIndexOf(". "), head.lastIndexOf("! "), head.lastIndexOf("? "), head.lastIndexOf('\n'))
  if (sentence >= enough) return head.substring(0, sentence + 1)
  val word = head.lastIndexOf(' ')
  return if (word >= enough) head.substring(0, word) else head
}

/** Page [i] of [pages] as the display shows it: the text, then where it is and what to do. */
fun g2Page(pages: List<String>, i: Int): String {
  val n = pages.size
  val hint =
      when {
        n == 1 -> "[tap: reply | swipe down: close]"
        i < n - 1 -> "[${i + 1}/$n  tap: reply | swipe down: more]"
        else -> "[$n/$n  tap: reply | swipe down: close]"
      }
  return "${pages[i]}\n\n$hint"
}

/**
 * What the display shows, a page at a time: Jarvis's words through [show], turned with [next] and
 * [previous] (a swipe down and up).
 */
class G2Screen(private val draw: (String) -> Unit) {
  private var pages = listOf("")
  private var at = 0

  fun show(text: String) {
    pages = g2Pages(text)
    at = 0
    draw(g2Page(pages, at))
  }

  /** Draws [text] as it is, with no hint: "Listening...". */
  fun plain(text: String) = draw(text)

  /** The next page; false when this is the last. */
  fun next(): Boolean {
    if (at >= pages.size - 1) return false
    draw(g2Page(pages, ++at))
    return true
  }

  fun previous() {
    if (at > 0) draw(g2Page(pages, --at))
  }
}
