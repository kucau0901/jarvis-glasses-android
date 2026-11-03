package app.jarvis.glasses.g2

import app.jarvis.glasses.hub.AlertFrom
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.Said
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class G2ChatTest {
  private fun chat(): Pair<G2Chat, MutableList<List<G2Box>>> {
    val drawn = mutableListOf<List<G2Box>>()
    return G2Chat({ drawn += it }, clock = { "14:05" }) to drawn
  }

  private val List<G2Box>.header get() = this[0].text
  private val List<G2Box>.body get() = this[1].text.split('\n')
  private val List<G2Box>.footer get() = this[2].text

  private fun talk(n: Int) = (1..n).flatMap { listOf(Said("q$it", it * 2L, "user", "question $it"), Said("a$it", it * 2L + 1, "assistant", "answer $it")) }

  @Test fun onlyWhatTheGlassesFontHasIsSent() {
    assertEquals("'Hi' \"there\"... - cafe -", g2Ascii("‘Hi’ “there”… — café •😀"))
    assertEquals("line\nnext", g2Ascii("line\nnext"))
  }

  @Test fun linesBreakAtSpacesAndGoOnIndented() {
    assertEquals(listOf("one two", "  three", "  four"), g2Wrap("one two three four", 9, "  "))
    assertEquals(listOf("a", "  b"), g2Wrap("a\nb", 10, "  "))
    // A word longer than a line is cut.
    assertEquals(listOf("abcde", "  fgh"), g2Wrap("abcdefgh", 5, "  "))
    assertTrue(g2Wrap("", 10).isEmpty())
  }

  @Test fun eachMessageIsUnderWhoSaidIt() {
    val said =
        listOf(
            Said("q", 1, "user", "is the gate open?"),
            Said("a", 2, "assistant", "**Yes**, since 9."),
            Said("g", 3, "assistant", "The gate is open.", alert = AlertFrom("api", "Gate")),
            Said("r", 4, "assistant", "Rain at 3.", routine = Named("r1", "Morning")),
        )
    assertEquals(listOf("You: is the gate open?", "Jarvis: Yes, since 9.", "Gate: The gate is open.", "Morning: Rain at 3."), g2ChatLines(said))
  }

  @Test fun theWindowEndsAtTheNewestAndStopsAtTheOldest() {
    val lines = (1..10).map { "$it" }
    assertEquals(listOf("5", "6", "7", "8", "9", "10"), g2Window(lines, 0, 6))
    assertEquals(listOf("3", "4", "5", "6", "7", "8"), g2Window(lines, 2, 6))
    assertEquals(listOf("1", "2", "3", "4", "5", "6"), g2Window(lines, 99, 6))
    assertEquals(listOf("1", "2"), g2Window(listOf("1", "2"), 3, 6))
  }

  @Test fun itOpensAtTheNewestUnderAHeaderAndAboveAHint() {
    val (chat, drawn) = chat()
    chat.history(talk(5))
    assertTrue(drawn.isEmpty())
    chat.open()
    val page = drawn.last()
    assertEquals(3, page.size)
    assertEquals("JARVIS  |  14:05", page.header)
    assertEquals(G2_ROWS, page.body.size)
    assertEquals("Jarvis: answer 5", page.body.last())
    assertEquals("tap: talk  |  swipe up: earlier", page.footer)
  }

  @Test fun swipesScrollAndTwoDownAtTheNewestLeave() {
    val (chat, drawn) = chat()
    chat.history(talk(10))
    chat.open(leave = "go back")
    chat.up()
    assertFalse(drawn.last().body.contains("Jarvis: answer 10"))
    repeat(10) { chat.up() }
    assertEquals("You: question 1", drawn.last().body.first())
    assertTrue(drawn.last().footer.startsWith("Start of chat"))
    while (drawn.last().body.last() != "Jarvis: answer 10") assertTrue(chat.down())
    assertTrue(chat.down())
    assertEquals("Swipe down again to go back  |  tap: talk", drawn.last().footer)
    assertFalse(chat.down())
  }

  @Test fun whatIsHeardShowsAtOnceThenAsKept() {
    val (chat, drawn) = chat()
    chat.history(talk(1))
    chat.open()
    chat.listening()
    assertEquals("JARVIS  |  Listening...", drawn.last().header)
    assertEquals("Speak now  |  swipe down: stop", drawn.last().footer)
    chat.thinking()
    chat.asked("what's on tomorrow?")
    chat.progress("Checking the calendar…")
    assertEquals("JARVIS  |  Checking the calendar...", drawn.last().header)
    assertEquals("You: what's on tomorrow?", drawn.last().body.last())
    // Kept with its answer: shown as the conversation has it, once.
    chat.history(talk(1) + listOf(Said("q", 9, "user", "what's on tomorrow?"), Said("a", 10, "assistant", "Dentist at 9.")))
    chat.said("Dentist at 9.")
    assertEquals(listOf("You: question 1", "Jarvis: answer 1", "You: what's on tomorrow?", "Jarvis: Dentist at 9."), drawn.last().body)
    assertEquals("JARVIS  |  14:05", drawn.last().header)
  }

  @Test fun aFailureIsShownThoughNotKept() {
    val (chat, drawn) = chat()
    chat.open()
    assertEquals(listOf("Nothing said yet. Tap, then ask Jarvis anything."), drawn.last().body)
    chat.said("I couldn't reach Jarvis just now.")
    assertEquals(listOf("Jarvis: I couldn't reach Jarvis just now."), drawn.last().body)
  }

  @Test fun anAlertSaidFirstIsNotShownTwice() {
    val (chat, drawn) = chat()
    chat.history(listOf(Said("g", 1, "assistant", "The gate is open.", alert = AlertFrom("api", "Gate"))))
    chat.open()
    chat.said("Gate: The gate is open.")
    assertEquals(listOf("Gate: The gate is open."), drawn.last().body)
  }

  @Test fun somethingNewBringsItBackToTheNewest() {
    val (chat, drawn) = chat()
    chat.history(talk(10))
    chat.open()
    repeat(3) { chat.up() }
    chat.history(talk(10) + Said("g", 99, "assistant", "The gate is open.", alert = AlertFrom("api", "Gate")))
    chat.notice("New: Gate")
    assertEquals("Gate: The gate is open.", drawn.last().body.last())
    assertEquals("JARVIS  |  New: Gate", drawn.last().header)
  }

  @Test fun theAlertsViewShowsOnlyAlerts() {
    val (chat, drawn) = chat()
    chat.history(talk(2) + Said("g", 9, "assistant", "The gate is open.", alert = AlertFrom("api", "Gate")))
    chat.open(only = { it.g2Alert })
    assertEquals(listOf("Gate: The gate is open."), drawn.last().body)
    assertEquals("JARVIS ALERTS  |  14:05", drawn.last().header)
    chat.open(only = { false })
    assertEquals(listOf("No alerts yet."), drawn.last().body)
  }

  @Test fun nothingIsDrawnWhileHidden() {
    val (chat, drawn) = chat()
    chat.open()
    chat.hide()
    val before = drawn.size
    chat.history(talk(2))
    chat.listening()
    assertEquals(before, drawn.size)
  }
}
