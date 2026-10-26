package app.jarvis.glasses.chat

/**
 * Jarvis's answers as the web app reads them (src/app/markdown.ts and `inline` in ui/util.ts in
 * jarvis-voice-agent): headings, lists, tables, code and quotes as blocks, and inside them bold,
 * italic, code and https links. Plain words stay plain. Links are https only: an answer can quote
 * a web page.
 */
object Markdown {
  sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block

    data class Para(val text: String) : Block

    data class Items(val numbered: Boolean, val items: List<String>) : Block

    data class Table(val head: List<String>, val rows: List<List<String>>) : Block

    data class Code(val text: String) : Block

    data class Quote(val text: String) : Block

    data object Rule : Block
  }

  sealed interface Span {
    data class Plain(val text: String) : Span

    data class Bold(val text: String) : Span

    data class Italic(val text: String) : Span

    data class Mono(val text: String) : Span

    data class Link(val label: String, val url: String) : Span
  }

  private const val MAX_ROWS = 200
  private const val MAX_COLS = 12
  private val HEADING = Regex("""^(#{1,3})\s+(.+?)\s*#*\s*$""")
  private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
  private val NUMBERED = Regex("""^\s*\d{1,3}[.)]\s+(.*)$""")
  private val FENCE = Regex("""^\s*```""")
  private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
  private val SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")
  private val QUOTE = Regex("""^\s*>""")
  private val INLINE =
      Regex("""\(?\[([^\]\n]{1,160})]\((https://[^\s)]{1,2000})\)\)?|(https://[^\s<>()]{3,2000})|\*\*([^*\n]{1,200})\*\*|`([^`\n]{1,200})`|(?<![*\w])\*([^*\s][^*\n]{0,198}[^*\s]|[^*\s])\*(?![*\w])""")

  private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }.take(MAX_COLS)

  private fun isTable(lines: List<String>, i: Int) = '|' in lines[i] && SEPARATOR.matches(lines.getOrElse(i + 1) { "" })

  fun blocks(text: String): List<Block> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
    val out = mutableListOf<Block>()
    val para = mutableListOf<String>()
    fun flush() {
      if (para.isNotEmpty()) out += Block.Para(para.joinToString("\n"))
      para.clear()
    }
    var i = 0
    while (i < lines.size) {
      val line = lines[i]
      val heading = HEADING.find(line)
      val item = BULLET.find(line) ?: NUMBERED.find(line)
      when {
        FENCE.containsMatchIn(line) -> {
          flush()
          val code = mutableListOf<String>()
          while (++i < lines.size && !FENCE.containsMatchIn(lines[i])) code += lines[i]
          out += Block.Code(code.joinToString("\n"))
        }
        heading != null -> {
          flush()
          out += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
        }
        isTable(lines, i) -> {
          flush()
          val head = cells(line)
          val rows = mutableListOf<List<String>>()
          i++
          while (i + 1 < lines.size && '|' in lines[i + 1] && lines[i + 1].isNotBlank()) {
            i++
            // Ragged rows are made as wide as the heading.
            if (rows.size < MAX_ROWS) cells(lines[i]).let { r -> rows += head.indices.map { c -> r.getOrElse(c) { "" } } }
          }
          out += Block.Table(head, rows)
        }
        RULE.matches(line) -> {
          flush()
          out += Block.Rule
        }
        item != null -> {
          flush()
          val numbered = BULLET.find(line) == null
          val pattern = if (numbered) NUMBERED else BULLET
          val items = mutableListOf(item.groupValues[1])
          while (i + 1 < lines.size && pattern.containsMatchIn(lines[i + 1])) items += pattern.find(lines[++i])!!.groupValues[1]
          out += Block.Items(numbered, items)
        }
        QUOTE.containsMatchIn(line) -> {
          flush()
          val quote = mutableListOf(line.replace(Regex("""^\s*>\s?"""), ""))
          while (i + 1 < lines.size && QUOTE.containsMatchIn(lines[i + 1])) quote += lines[++i].replace(Regex("""^\s*>\s?"""), "")
          out += Block.Quote(quote.joinToString("\n"))
        }
        line.isBlank() -> flush()
        else -> para += line
      }
      i++
    }
    flush()
    return out
  }

  /** An answer as plain lines, for where nothing can be drawn (a notification): bullets kept, marks dropped. */
  fun plain(text: String): String =
      blocks(text).joinToString("\n") { b ->
        when (b) {
          is Block.Heading -> words(b.text)
          is Block.Para -> words(b.text)
          is Block.Items -> b.items.mapIndexed { i, it -> (if (b.numbered) "${i + 1}. " else "• ") + words(it) }.joinToString("\n")
          is Block.Table -> (listOf(b.head) + b.rows).joinToString("\n") { r -> r.joinToString(" · ") { words(it) } }
          is Block.Code -> b.text
          is Block.Quote -> words(b.text)
          Block.Rule -> ""
        }
      }

  private fun words(text: String) =
      spans(text).joinToString("") {
        when (it) {
          is Span.Plain -> it.text
          is Span.Bold -> it.text
          is Span.Italic -> it.text
          is Span.Mono -> it.text
          is Span.Link -> it.label
        }
      }

  /** A line's words, with its bold, italic, code and links picked out. */
  fun spans(text: String): List<Span> {
    val out = mutableListOf<Span>()
    var last = 0
    for (m in INLINE.findAll(text)) {
      if (m.range.first > last) out += Span.Plain(text.substring(last, m.range.first))
      val g = m.groups
      when {
        g[4] != null -> out += Span.Bold(g[4]!!.value)
        g[5] != null -> out += Span.Mono(g[5]!!.value)
        g[6] != null -> out += Span.Italic(g[6]!!.value)
        else -> {
          val url = g[2]?.value ?: g[3]!!.value
          val label = g[1]?.value ?: url.removePrefix("https://").replace(Regex("[?#].*$"), "")
          val bracketed = m.value.startsWith("(") && g[1] != null
          if (bracketed) out += Span.Plain("(")
          out += Span.Link(label, url)
          if (bracketed) out += Span.Plain(")")
        }
      }
      last = m.range.last + 1
    }
    if (last < text.length) out += Span.Plain(text.substring(last))
    return out
  }
}
