package app.jarvis.glasses.g2

import org.junit.Assert.assertEquals
import org.junit.Test

class G2MenuTest {
  @Test fun theFirstChoiceIsMarkedAndASwipeMovesIt() {
    val menu = G2Menu(clock = { "14:05" })
    assertEquals(G2Choice.CHAT, menu.chosen)
    assertEquals(listOf(">  Chat with Jarvis", "    Latest alerts", "    Close"), menu.lines())
    menu.down()
    assertEquals(G2Choice.ALERTS, menu.chosen)
    assertEquals(listOf("    Chat with Jarvis", ">  Latest alerts", "    Close"), menu.lines())
  }

  @Test fun itStopsAtEitherEnd() {
    val menu = G2Menu()
    menu.up()
    assertEquals(G2Choice.CHAT, menu.chosen)
    repeat(5) { menu.down() }
    assertEquals(G2Choice.CLOSE, menu.chosen)
  }

  @Test fun alertsArrivingMeanwhileAreCounted() {
    val menu = G2Menu()
    menu.unseen = 2
    assertEquals("    Latest alerts (2 new)", menu.lines()[1])
  }

  @Test fun itIsDrawnLikeTheChat() {
    val page = G2Menu(clock = { "14:05" }).page()
    assertEquals(listOf("JARVIS  |  14:05", "\n>  Chat with Jarvis\n    Latest alerts\n    Close", "swipe: choose  |  tap: open"), page.map { it.text })
  }
}
