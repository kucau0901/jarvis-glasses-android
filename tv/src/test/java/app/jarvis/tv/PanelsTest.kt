package app.jarvis.tv

import app.jarvis.tv.jarvis.ButtonInput
import app.jarvis.tv.jarvis.Figure
import app.jarvis.tv.jarvis.Panel
import app.jarvis.tv.jarvis.PanelAction
import app.jarvis.tv.jarvis.PanelRow
import app.jarvis.tv.jarvis.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelsTest {
  private fun thing(key: String, kind: String = "light", label: String = key) = PanelRow(key = key, kind = kind, label = label)

  @Test
  fun aPanelIsSixTilesARowWithAddLast() {
    val panel = Panel("p", "Living room", List(7) { thing("t$it") } + thing("n1", kind = "note"))
    val rows = panelSlots(panel)
    assertEquals(listOf(6, 2), rows.map { it.size })
    assertEquals(PanelSlot.Thing(thing("t6")), rows[1][0])
    assertEquals(PanelSlot.Add, rows[1][1])
    // To-dos aren't tiles of their own.
    assertTrue(rows.flatten().none { it is PanelSlot.Thing && it.row.kind == "note" })
  }

  @Test
  fun aFullRowPutsAddOnTheNext() {
    val rows = panelSlots(Panel("p", "P", List(6) { thing("t$it") }))
    assertEquals(listOf(6, 1), rows.map { it.size })
    assertEquals(listOf(listOf(PanelSlot.Add)), panelSlots(Panel("e", "Empty")))
  }

  @Test
  fun toDosAreTheNotes() {
    val panel = Panel("p", "P", listOf(thing("a"), thing("n1", kind = "note"), thing("n2", kind = "note")))
    assertEquals(listOf("n1", "n2"), notesOf(panel).map { it.key })
  }

  @Test
  fun theToDoTileSaysHowManyMoreWhenTheyDontFit() {
    assertEquals(0, notesShown(0))
    assertEquals(3, notesShown(3))
    assertEquals(9, notesShown(9))
    assertEquals(8, notesShown(10))
    assertEquals(8, notesShown(40))
  }

  @Test
  fun statusIsAWarningThenValueThenReadingThenFigures() {
    val figures = listOf(Figure("In", "21°"), Figure("Out", "30°"))
    assertEquals("Battery low", thingStatus(thing("a").copy(note = "Battery low", value = "On"), null))
    assertEquals("On", thingStatus(thing("a").copy(value = "On", tiles = figures), null))
    assertEquals("In 21° · Out 30°", thingStatus(thing("a").copy(tiles = figures), null))
    assertEquals("", thingStatus(thing("a"), null))
  }

  @Test
  fun aReadTileShowsItsReadingOrDots() {
    val read = thing("w", kind = "read").copy(read = true)
    assertEquals("…", thingStatus(read, null))
    assertEquals("Sunny, 31°", thingStatus(read, Reading(text = "Sunny, 31°")))
    val figures = List(4) { Figure("F$it", "$it") }
    assertEquals("F0 0 · F1 1 · F2 2", thingStatus(read, Reading(figures = figures)))
    assertEquals("…", thingStatus(read, Reading(pending = true)))
  }

  @Test
  fun okWorksTheFirstPlainButton() {
    val slider = PanelAction("bright", "Brightness", ButtonInput("slider", 0.0, 100.0, 10.0))
    val toggle = PanelAction("toggle", "Turn off")
    assertEquals(toggle, firstButton(thing("a").copy(actions = listOf(slider, toggle, PanelAction("x", "X")))))
    assertNull(firstButton(thing("a").copy(actions = listOf(slider))))
  }

  @Test
  fun aBatteryIsABadgeAndBarRedUnderTwenty() {
    val low = tileFace(thing("car", kind = "car").copy(level = 15.6), null)
    assertEquals("15%", low.badge)
    assertTrue(low.low)
    assertEquals(0.156f, low.bar!!, 1e-4f)
    val full = tileFace(thing("car", kind = "car").copy(level = 120.0), null)
    assertFalse(full.low)
    assertEquals(1f, full.bar!!, 0f)
    assertNull(tileFace(thing("a"), null).badge)
  }

  @Test
  fun theNameGetsTwoLinesOnlyWhenNothingElseIsShown() {
    assertEquals(2, tileFace(thing("a"), null).labelLines)
    assertEquals(1, tileFace(thing("a").copy(value = "On"), null).labelLines)
    assertEquals(1, tileFace(thing("a").copy(actions = listOf(PanelAction("t", "Toggle"))), null).labelLines)
    val read = thing("w").copy(read = true)
    assertEquals(2, tileFace(read, Reading(text = "Long")).statusLines)
    assertEquals(1, tileFace(read.copy(actions = listOf(PanelAction("r", "Refresh"))), null).statusLines)
    assertTrue(tileFace(read, Reading(text = "No signal", failed = true)).faint)
    assertTrue(tileFace(thing("a").copy(note = "Offline"), null).warning)
  }

  @Test
  fun readingsAndPressesAreKeyedByPanelAndThing() {
    assertEquals("p1\nlight.kitchen\nlight.kitchen", thingKey(Panel("p1", "P"), thing("light.kitchen")))
    // A car's tiles share its key; their names tell them apart.
    assertNotEquals(thingKey(Panel("p1", "P"), thing("car1", label = "Locks")), thingKey(Panel("p1", "P"), thing("car1", label = "Climate")))
  }

  @Test
  fun numbersWalkAlongTheirSteps() {
    assertEquals(50.0, stepFrom(50.0, 0, 0.0, 100.0, 10.0), 0.0)
    assertEquals(60.0, stepFrom(50.0, 1, 0.0, 100.0, 10.0), 0.0)
    assertEquals(100.0, stepFrom(100.0, 1, 0.0, 100.0, 10.0), 0.0)
    assertEquals(0.0, stepFrom(0.0, -1, 0.0, 100.0, 10.0), 0.0)
    // A range that isn't whole steps stops at the last step under its top.
    assertEquals(90.0, stepFrom(90.0, 1, 0.0, 95.0, 10.0), 0.0)
    // Off its step, it comes back onto one.
    assertEquals(-1.0, stepFrom(-1.2, 0, -2.5, 2.5, 0.5), 1e-9)
    assertEquals(1.5, stepFrom(1.0, 1, -2.5, 2.5, 0.5), 1e-9)
  }

  @Test
  fun numbersAreSentWithTheirStepsDecimalsAndAPoint() {
    assertEquals("23.5", numberWords(23.5, 0.5))
    assertEquals("24", numberWords(24.0, 0.5))
    assertEquals("60", numberWords(60.0, 10.0))
    assertEquals("0.3", numberWords(0.1 + 0.2, 0.1))
  }

  @Test
  fun anAircondStepsWithinItsRangeToATenth() {
    assertEquals(24.5, tempAfter(24.0, 0.5, 16.0, 30.0), 0.0)
    assertEquals(30.0, tempAfter(29.8, 0.5, 16.0, 30.0), 0.0)
    assertEquals(16.0, tempAfter(16.0, -1.0, 16.0, 30.0), 0.0)
  }

  @Test
  fun removingAPanelTakesTwoPresses() {
    assertEquals("Remove the Kitchen panel", removePanelWords("Kitchen", armed = false))
    assertEquals("Press again to remove Kitchen", removePanelWords("Kitchen", armed = true))
  }

  @Test
  fun modesReadAsWords() {
    assertEquals("Heat cool", prettyWord("heat_cool"))
    assertEquals("Auto", prettyWord("auto"))
  }

  @Test
  fun waysInAndTheAlarmNeedASecondPress() {
    val unlock = PanelAction("unlock", "Unlock")
    assertTrue(needsSecondPress(thing("lock.front").copy(icon = "lock"), unlock))
    assertTrue(needsSecondPress(thing("cover.gate").copy(icon = "gate"), PanelAction("open", "Open")))
    assertTrue(needsSecondPress(thing("alarm").copy(icon = "dot"), PanelAction("disarm", "Disarm")))
    assertFalse(needsSecondPress(thing("light.bed").copy(icon = "bulb"), PanelAction("toggle", "Turn off")))
    assertFalse(needsSecondPress(thing("climate.bed").copy(icon = "snow"), PanelAction("off", "Turn off")))
  }
}
