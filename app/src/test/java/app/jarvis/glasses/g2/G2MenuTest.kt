package app.jarvis.glasses.g2

import app.jarvis.glasses.hub.AlertFrom
import app.jarvis.glasses.hub.Named
import app.jarvis.glasses.hub.Said
import org.junit.Assert.assertEquals
import org.junit.Test

class G2MenuTest {
  @Test fun theFirstChoiceIsMarkedAndASwipeMovesIt() {
    val menu = G2Menu()
    assertEquals(G2Choice.TALK, menu.chosen)
    assertEquals("Jarvis\n\n> Talk to Jarvis\n   Latest alerts\n   Close\n\n[swipe: choose | tap: open]", menu.text())
    menu.down()
    assertEquals(G2Choice.ALERTS, menu.chosen)
    assertEquals("Jarvis\n\n   Talk to Jarvis\n> Latest alerts\n   Close\n\n[swipe: choose | tap: open]", menu.text())
  }

  @Test fun itStopsAtEitherEnd() {
    val menu = G2Menu()
    menu.up()
    assertEquals(G2Choice.TALK, menu.chosen)
    repeat(5) { menu.down() }
    assertEquals(G2Choice.CLOSE, menu.chosen)
  }

  @Test fun theLatestAlertsComeNewestFirstAsChatReadsThem() {
    val said =
        listOf(
            Said("a1", 1, "assistant", "The gate is open.", alert = AlertFrom("api", "Gate")),
            Said("q1", 2, "user", "close it"),
            Said("a2", 3, "assistant", "Closed."),
            Said("a3", 4, "assistant", "Rain at 3.", routine = Named("r1", "Morning")),
            Said("a4", 5, "assistant", "Three chargers compared.", job = Named("j1", "Chargers")),
        )
    assertEquals("Chargers: Three chargers compared.\n\nMorning: Rain at 3.\n\nGate: The gate is open.", g2AlertList(said))
    assertEquals("Chargers: Three chargers compared.", g2AlertList(said, most = 1))
  }

  @Test fun noAlertsSaysSo() {
    assertEquals("No alerts yet.", g2AlertList(listOf(Said("q1", 1, "user", "hello"))))
  }
}
