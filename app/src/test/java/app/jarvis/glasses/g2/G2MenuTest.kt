package app.jarvis.glasses.g2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G2MenuTest {
  private val List<G2Box>.tiles get() = filter { it.border > 0 }

  @Test fun fourTilesFillTheWidthTheChosenOneFramedThickAndBright() {
    val menu = G2Menu(clock = { "14:05" })
    assertEquals(G2Choice.CHAT, menu.chosen)
    val tiles = menu.page().tiles
    assertEquals(4, tiles.size)
    assertEquals(0, tiles.first().x)
    assertTrue(tiles.last().x + tiles.last().w <= EvenHub.WIDTH)
    assertEquals(listOf(3, 1, 1, 1), tiles.map { it.border })
    assertEquals(listOf(15, 5, 5, 5), tiles.map { it.shade })
    assertEquals("▶  Chat\n\nTalk to Jarvis", tiles[0].text)
    assertEquals("☆  Alerts\n\nLatest", tiles[1].text)
  }

  @Test fun aSwipeMovesTheChoiceAndItStopsAtEitherEnd() {
    val menu = G2Menu()
    menu.up()
    assertEquals(G2Choice.CHAT, menu.chosen)
    menu.down()
    assertEquals(G2Choice.ALERTS, menu.chosen)
    assertEquals(listOf(1, 3, 1, 1), menu.page().tiles.map { it.border })
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

  @Test fun jobsAreATileOfTheirOwnAndWhatWaitsIsCounted() {
    val menu = G2Menu()
    menu.down()
    menu.down()
    assertEquals(G2Choice.JOBS, menu.chosen)
    assertEquals("None at work", menu.note(G2Choice.JOBS))
    menu.working = 2
    menu.waiting = 1
    menu.unseen = 3
    assertEquals("2 at work", menu.note(G2Choice.JOBS))
    assertEquals("3 new, 1 wait", menu.note(G2Choice.ALERTS))
  }

  @Test fun aJobsQuestionIsAPageOfItsChoicesThenSayItAndGoOnWithout() {
    val page = G2Ask("A question: Dashcams", "Under RM800 only, or up to RM1,000?", listOf("Under RM800", "Up to RM1,000"))
    assertEquals(listOf("Under RM800", "Up to RM1,000", G2Ask.SAY, G2Ask.SKIP), page.options)
    assertEquals("Under RM800", page.chosen)
    val body = page.page()[1].text.lines()
    assertEquals("> Under RM800", body.first { it.startsWith(">") })
    repeat(9) { page.down() }
    assertEquals(G2Ask.SKIP, page.chosen)
    assertEquals("Your job asks: Dashcams", page.page()[0].text)
    // Answered: what came of it, in place of its choices.
    assertTrue(page.page("Told it: Under RM800")[1].text.endsWith("Told it: Under RM800"))
    assertEquals("tap: back", page.page("Told it")[2].text)
  }

  @Test fun theJobsPageApprovesOnlyWhatNeedsNoReadingAndOnASecondTap() {
    val lock = app.jarvis.glasses.hub.Proposal("p1", "Lock the car", verb = "Lock")
    val publish = app.jarvis.glasses.hub.Proposal("p2", "Publish the site", body = "index.html")
    val work = G2Work(listOf(publish, lock), listOf(app.jarvis.glasses.hub.Job("j1", "Dashcams", doing = listOf("web_search"))))
    // One with more to read is never armed, however often it is tapped.
    assertEquals(false, work.tapOk())
    assertEquals(false, work.armed)
    assertEquals(false, work.tapOk())
    assertTrue(work.page().first { it.id == 2 }.text.contains("OK? (on your phone) Publish the site"))
    work.down()
    assertEquals(false, work.tapOk())
    assertTrue(work.armed)
    assertEquals("tap again: lock", work.page()[2].text)
    assertEquals(true, work.tapOk())
    work.down()
    assertTrue(work.page()[1].text.contains("> Dashcams - Searching the web"))
    assertEquals("Nothing at work, and nothing waits for an OK.", G2Work(emptyList(), emptyList()).page()[1].text)
    assertEquals("tap again: stop it", G2Work.jobPage(app.jarvis.glasses.hub.Job("j1", "Dashcams"), armed = true)[2].text)
  }
}
