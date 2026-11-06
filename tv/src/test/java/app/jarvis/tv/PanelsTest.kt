package app.jarvis.tv

import app.jarvis.tv.jarvis.ButtonInput
import app.jarvis.tv.jarvis.Figure
import app.jarvis.tv.jarvis.LightColor
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
  fun aColourIsSentAsWholeNumbersWithHue360AsNought() {
    assertEquals("color:120,100", colorAction(120, 100))
    assertEquals("color:0,100", colorAction(360, 100))
    assertEquals("color:0,0", colorAction(0, -5))
    assertEquals("kelvin:2700", kelvinAction(2700))
    assertEquals(listOf("color:0,100", "color:30,100", "color:55,100", "color:120,100", "color:180,100", "color:220,100", "color:275,100", "color:320,100", "color:0,0"), SWATCHES.map { it.action })
    assertEquals("White", SWATCHES.last().name)
  }

  @Test
  fun theNearestSwatchIsMarkedOnlyWhileTheLightIsOn() {
    fun near(hue: Double, sat: Double, on: Boolean = true) = nearestSwatch(LightColor(wheel = true, hue = hue, sat = sat, on = on))?.name
    assertEquals("Red", near(0.0, 100.0))
    assertEquals("Red", near(355.0, 90.0)) // round the wheel, not the long way
    assertEquals("Orange", near(25.0, 100.0))
    assertEquals("Blue", near(230.0, 80.0))
    assertEquals("Pink", near(330.0, 100.0))
    assertEquals("White", near(0.0, 0.0))
    assertEquals("White", near(120.0, 30.0)) // a pale green is nearer white
    assertNull(near(120.0, 100.0, on = false))
  }

  @Test
  fun whiteIsFiveEvenStepsFromWarmToCool() {
    assertEquals(listOf(2000, 3125, 4250, 5375, 6500), kelvinSteps(2000.0, 6500.0))
    assertEquals(listOf(2700), kelvinSteps(2700.0, 2700.0))
    assertEquals(4250, kelvinStep(4100.0, 0, 2000.0, 6500.0))
    assertEquals(5375, kelvinStep(4250.0, 1, 2000.0, 6500.0))
    assertEquals(3125, kelvinStep(4250.0, -1, 2000.0, 6500.0))
    assertEquals(6500, kelvinStep(6500.0, 1, 2000.0, 6500.0))
    assertEquals(2000, kelvinStep(2000.0, -1, 2000.0, 6500.0))
    assertEquals(2000, kelvinStep(1500.0, 0, 2000.0, 6500.0))
  }
}
