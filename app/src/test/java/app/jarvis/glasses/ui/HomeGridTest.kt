package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.ButtonInput
import app.jarvis.glasses.hub.Figure
import app.jarvis.glasses.hub.Level
import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelMore
import app.jarvis.glasses.hub.PanelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGridTest {
  private fun row(key: String, kind: String = "home", label: String = key) = PanelRow(key = key, kind = kind, label = label)

  private fun note(key: String) = row(key, kind = "note")

  private fun panel(id: String, vararg rows: PanelRow) = Panel(id, id, rows.toList())

  private fun keys(s: String) = s.map { it.toString() }

  private fun String.moved(from: Char, onto: Char) = keys(this).moved(indexOf(from), indexOf(onto)).joinToString("")

  @Test fun aMoveIsTheWebsSplice() {
    assertEquals("acdbe", "abcde".moved('b', 'd'))
    assertEquals("adbce", "abcde".moved('d', 'b'))
    assertEquals("bcdea", "abcde".moved('a', 'e'))
    assertEquals("abcde", "abcde".moved('c', 'c'))
  }

  @Test fun jarvisPutsTheNamedFirstAndTheRestAsTheyWere() {
    assertEquals("cabde", ordered(keys("abcde"), keys("caz")) { it }.joinToString(""))
  }

  @Test fun theShortestOrderStillGivesTheNewOrderForEveryMoveOfFive() {
    val old = keys("abcde")
    for (from in old.indices) for (to in old.indices) {
      val new = old.moved(from, to)
      val sent = shortestOrder(old, new)
      assertEquals(new, ordered(old, sent) { it })
      if (new == old) assertTrue(sent.isEmpty())
    }
    assertEquals(keys("b"), shortestOrder(keys("abcde"), keys("bacde")))
    assertEquals(keys("acd"), shortestOrder(keys("abcde"), keys("acdbe")))
  }

  @Test fun aCarsRowsAreOneThingAndMoveTogether() {
    val p = panel("p1", row("gate"), row("car1", label = "Car"), row("car1", label = "Locks"), row("car1", label = "Climate"), row("lamp"))
    val moved = moveThing(p, "car1", "gate")
    assertEquals(listOf("Car", "Locks", "Climate", "gate", "lamp"), moved.rows.map { it.label })
    assertEquals(listOf("car1", "gate", "lamp"), keysOf(moved))
  }

  @Test fun toDosKeepTheirPlacesWhenThingsMove() {
    val p = panel("p1", row("a"), note("n_1"), row("b"), note("n_2"), row("c"))
    val moved = moveThing(p, "c", "a")
    assertEquals(listOf("c", "n_1", "a", "n_2", "b"), keysOf(moved))
  }

  @Test fun panelsMoveByTheirIds() {
    val ps = listOf(panel("p1"), panel("p2"), panel("p3"))
    assertEquals(listOf("p2", "p3", "p1"), movePanel(ps, "p1", "p3").map { it.id })
    assertEquals(ps, movePanel(ps, "p1", "gone"))
  }

  @Test fun everyCellHasItsOwnIdEvenWhenKeysRepeat() {
    val ps = listOf(panel("p1", row("car1"), row("car1"), row("car1", label = "Locks"), note("n_1")), panel("p2", row("car1")))
    val cells = cellsOf(ps)
    assertEquals(cells.size, cells.map { it.id }.toSet().size)
    assertEquals(listOf("h/p1", "n/p1", "t/p1/car1/car1#0", "t/p1/car1/car1#1", "t/p1/car1/Locks#0", "h/p2", "t/p2/car1/car1#0"), cells.map { it.id })
  }

  @Test fun aCarRowThatGoesDoesNotHandItsPlaceToTheNext() {
    val before = cellsOf(listOf(panel("p1", row("car1", label = "Car"), row("car1", label = "Locks"), row("car1", label = "Climate"))))
    val after = cellsOf(listOf(panel("p1", row("car1", label = "Car"), row("car1", label = "Climate"))))
    assertTrue(after.none { it.id == before[2].id })
    assertEquals(before[3].id, after[2].id)
  }

  @Test fun thingsAreOneTileAndTitlesAndToDosTakeTheRow() {
    val cells = cellsOf(listOf(panel("p1", row("a"), note("n_1"), row("b"))))
    assertEquals(listOf(true, true, false, false), cells.map { it.wide })
    assertFalse((cells[1] as Cell.Notes).tall)
    assertTrue(Cell.Notes(panel("p1"), List(SHORT_NOTES + 1) { note("n_$it") }).tall)
  }

  @Test fun foldedPanelsAreTheirTitlesOnly() {
    val cells = cellsOf(listOf(panel("p1", row("a"), row("b"), note("n_1")), panel("p2")), folded = true)
    assertEquals(listOf("h/p1", "h/p2"), cells.map { it.id })
    assertEquals(2, (cells[0] as Cell.Header).things)
  }

  @Test fun aTileHasAtMostOneButtonAndNeverOneThatAsks() {
    val r = row("cover.gate").copy(
        actions = listOf(PanelAction("set", "Set", ButtonInput("number")), PanelAction("open", "Open"), PanelAction("close", "Close")),
    )
    val f = faceOf(r)
    assertEquals("open", f.button!!.action)
    assertEquals(1, f.labelLines)
    assertTrue(f.more)
    assertNull(faceOf(row("x").copy(actions = listOf(PanelAction("set", "Set", ButtonInput("number"))))).button)
    assertEquals(2, faceOf(row("x")).labelLines)
  }

  @Test fun theStateLineIsTheNoteElseTheValueElseTheFigures() {
    val figures = listOf(Figure("Range", "300 km"), Figure("Inside", "29°"))
    assertEquals("Range 300 km · Inside 29°", faceOf(row("car1").copy(tiles = figures)).status)
    assertEquals("Closed", faceOf(row("g").copy(value = "Closed", tiles = figures)).status)
    val warned = faceOf(row("g").copy(value = "Closed", note = "Offline"))
    assertEquals("Offline", warned.status)
    assertTrue(warned.warning)
    assertTrue(warned.more)
    assertFalse(faceOf(row("g").copy(value = "Closed")).more)
    assertFalse(faceOf(row("g").copy(more = PanelMore())).more)
    assertTrue(faceOf(row("g").copy(more = PanelMore(level = Level("Brightness", 40.0, 0.0, 100.0, 1.0)))).more)
  }
}
