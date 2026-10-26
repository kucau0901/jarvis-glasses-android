package app.jarvis.glasses.chat

import app.jarvis.glasses.chat.Markdown.Block
import app.jarvis.glasses.chat.Markdown.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
  @Test fun plainWordsStayOneParagraph() = assertEquals(listOf(Block.Para("It's 7:41 AM\nin London.")), Markdown.blocks("It's 7:41 AM\nin London."))

  @Test
  fun aTableIsReadRaggedRowsMadeWide() {
    val t = Markdown.blocks("| Station | State | Power |\n|---|---|---|\n| Gurun R&R | Kedah | 180 kW |\n| Juru | Penang |").single() as Block.Table
    assertEquals(listOf("Station", "State", "Power"), t.head)
    assertEquals(listOf("Juru", "Penang", ""), t.rows[1])
  }

  @Test
  fun headingsListsQuotesAndCode() {
    val b = Markdown.blocks("## Today\n- one\n- two\n\n1. first\n2) second\n> quoted\n```\nx = 1\n```\n---")
    assertEquals(
        listOf(
            Block.Heading(2, "Today"),
            Block.Items(false, listOf("one", "two")),
            Block.Items(true, listOf("first", "second")),
            Block.Quote("quoted"),
            Block.Code("x = 1"),
            Block.Rule,
        ),
        b,
    )
  }

  @Test
  fun inlineBoldCodeItalicAndLinks() {
    assertEquals(
        listOf(Span.Plain("A "), Span.Bold("big"), Span.Plain(" and "), Span.Mono("code"), Span.Plain(" and "), Span.Italic("soft"), Span.Plain(" word")),
        Markdown.spans("A **big** and `code` and *soft* word"),
    )
    assertEquals(listOf(Span.Plain("See "), Span.Link("the form", "https://docs.example.com/f")), Markdown.spans("See [the form](https://docs.example.com/f)"))
    assertEquals(listOf(Span.Link("example.com/a", "https://example.com/a?utm=1")), Markdown.spans("https://example.com/a?utm=1"))
  }

  @Test fun onlyHttpsBecomesALink() = assertEquals(listOf(Span.Plain("[x](http://example.com)")), Markdown.spans("[x](http://example.com)"))
}
