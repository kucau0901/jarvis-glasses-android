package app.jarvis.glasses.g2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G2MenuTest {
  private val List<G2Box>.tiles get() = filter { it.border > 0 }

  @Test fun threeTilesFillTheWidthTheChosenOneFramedThickAndBright() {
    val menu = G2Menu(clock = { "14:05" })
    assertEquals(G2Choice.CHAT, menu.chosen)
    val tiles = menu.page().tiles
    assertEquals(3, tiles.size)
    assertEquals(0, tiles.first().x)
    assertTrue(tiles.last().x + tiles.last().w <= EvenHub.WIDTH)
    assertEquals(listOf(3, 1, 1), tiles.map { it.border })
    assertEquals(listOf(15, 5, 5), tiles.map { it.shade })
    assertEquals("▶  Chat\n\nTalk to Jarvis", tiles[0].text)
    assertEquals("☆  Alerts\n\nLatest", tiles[1].text)
  }

  @Test fun aSwipeMovesTheChoiceAndItStopsAtEitherEnd() {
    val menu = G2Menu()
    menu.up()
    assertEquals(G2Choice.CHAT, menu.chosen)
    menu.down()
    assertEquals(G2Choice.ALERTS, menu.chosen)
    assertEquals(listOf(1, 3, 1), menu.page().tiles.map { it.border })
    assertTrue(menu.page().tiles[1].text.startsWith("★"))
    repeat(5) { menu.down() }
    assertEquals(G2Choice.CLOSE, menu.chosen)
  }

  @Test fun alertsArrivingMeanwhileAreCounted() {
    val menu = G2Menu()
    menu.unseen = 2
    assertEquals("2 new", menu.note(G2Choice.ALERTS))
  }

  @Test fun theTopLineHasTheBatteryOnceKnownAndTheTime() {
    val menu = G2Menu(clock = { "14:05" })
    assertEquals("14:05", menu.page()[1].text)
    menu.battery = 83
    assertEquals("83%    14:05", menu.page()[1].text)
  }

  @Test fun itStaysWithinWhatAPageMayHold() {
    val page = G2Menu().page()
    // With the one taking touches, at most 8 text containers, each id once.
    assertTrue(page.size + 1 <= 8)
    assertEquals(page.size, page.map { it.id }.toSet().size)
    assertTrue(page.all { it.x + it.w <= EvenHub.WIDTH && it.y + it.h <= EvenHub.HEIGHT })
  }
}
