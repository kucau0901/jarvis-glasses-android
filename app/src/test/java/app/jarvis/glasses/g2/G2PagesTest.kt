package app.jarvis.glasses.g2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G2PagesTest {
  @Test fun shortTextIsOnePage() {
    assertEquals(listOf("The gate is open."), g2Pages("  The gate is open.  "))
    assertEquals(listOf(""), g2Pages(""))
  }

  @Test fun longTextIsCutAtASentenceNearTheEndOfAPage() {
    val sentence = "This sentence is about forty characters. "
    val pages = g2Pages(sentence.repeat(20), size = 100, most = 100)
    assertTrue(pages.all { it.length <= 100 })
    assertTrue(pages.all { it.endsWith(".") })
    assertEquals(sentence.repeat(20).trim(), pages.joinToString(" "))
  }

  @Test fun aParagraphBreakComesFirst() {
    // At least half a page before it, so a page isn't left nearly empty.
    val pages = g2Pages("First part, short enough.\n\n" + "word ".repeat(30), size = 40, most = 100)
    assertEquals("First part, short enough.", pages[0])
    assertTrue(pages.all { it.length <= 40 })
  }

  @Test fun aWordIsNeverSplitWhenThereIsASpace() {
    val pages = g2Pages("alpha ".repeat(50), size = 32, most = 100)
    assertTrue(pages.all { p -> p.split(' ').all { it == "alpha" } })
  }

  @Test fun tooMuchEndsSayingTheRestIsInChat() {
    val pages = g2Pages("Word. ".repeat(500), size = 100, most = 3)
    assertEquals(3, pages.size)
    assertTrue(pages.last().endsWith("[more in Chat]"))
    assertTrue(pages.all { it.length <= 100 })
  }

  @Test fun eachPageSaysWhereItIsAndWhatToDo() {
    assertEquals("Hi\n\n[tap: reply | swipe down: close]", g2Page(listOf("Hi"), 0))
    assertEquals("A\n\n[1/2  tap: reply | swipe down: more]", g2Page(listOf("A", "B"), 0))
    assertEquals("B\n\n[2/2  tap: reply | swipe down: close]", g2Page(listOf("A", "B"), 1))
  }

  @Test fun theScreenTurnsItsPages() {
    val drawn = mutableListOf<String>()
    val screen = G2Screen { drawn += it }
    screen.show("One. ".repeat(100))
    assertTrue(drawn.last().contains("[1/"))
    screen.previous()
    assertEquals(1, drawn.size)
    assertTrue(screen.next())
    assertTrue(drawn.last().contains("[2/"))
    screen.previous()
    assertTrue(drawn.last().contains("[1/"))
    while (screen.next()) Unit
    assertTrue(drawn.last().contains("swipe down: close"))
  }
}
