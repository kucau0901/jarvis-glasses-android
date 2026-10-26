package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Figure
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.hub.Reading
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeReadingsTest {
  private fun tile(actions: List<PanelAction> = emptyList()) = PanelRow(key = "t_ab", kind = "tile", label = "Share", icon = "web", read = true, every = 60, actions = actions)

  @Test fun aTileIsReadAtMostOnceAMinute() {
    assertTrue(due(null, 1_000))
    assertFalse(due(Got(null, 1_000), 1_000 + READ_MS - 1))
    assertTrue(due(Got(null, 1_000), 1_000 + READ_MS))
  }

  @Test fun whileJarvisLooksItUpItIsReadAgainSoon() {
    val asked = askedAt(Reading(pending = true), 100_000)
    assertFalse(due(Got(null, asked), 100_000 + SOON_MS - 1))
    assertTrue(due(Got(null, asked), 100_000 + SOON_MS))
    assertEquals(100_000L, askedAt(Reading(text = "x"), 100_000))
  }

  @Test fun aPendingLookupKeepsTheOldReadingOrSaysSo() {
    assertEquals("Looking it up…", shown(null, Reading(pending = true))!!.text)
    assertEquals("Old", shown(Reading(text = "Old"), Reading(pending = true))!!.text)
    assertEquals("New", shown(Reading(text = "Old"), Reading(text = "New"))!!.text)
    assertEquals("Old", shown(Reading(text = "Old"), Reading(stale = true))!!.text)
    assertTrue(shown(Reading(text = "Old"), failedReading("No"))!!.failed)
  }

  @Test fun anOldErrorIsNotShownWhileItIsLookedUpAgain() {
    val r = shown(failedReading("Could not look it up just now"), Reading(pending = true))!!
    assertEquals("Looking it up…", r.text)
    assertFalse(r.failed)
  }

  @Test fun theFaceShowsTheReadingUpToThreeLines() {
    assertEquals("…", faceOf(tile()).status)
    val f = faceOf(tile(), Reading(text = "XYZ: 251.30 USD"))
    assertEquals("XYZ: 251.30 USD", f.status)
    assertEquals(3, f.statusLines)
    assertEquals(1, f.labelLines)
    assertTrue(f.more)
    assertEquals("Price 251.30 USD", faceOf(tile(), Reading(figures = listOf(Figure("Price", "251.30 USD")))).status)
  }

  @Test fun aReadingBesideAButtonGetsOneLine() {
    assertEquals(1, faceOf(tile(listOf(PanelAction("open", "Open"))), Reading(text = "Battery 33%")).statusLines)
  }

  @Test fun aFailedReadingIsFaint() {
    val f = faceOf(tile(), failedReading("Could not read it just now"))
    assertEquals("Could not read it just now", f.status)
    assertTrue(f.faint)
    assertFalse(f.warning)
  }

  @Test fun aCarShowsItsBatteryRedWhenLow() {
    val car = PanelRow(key = "car1", kind = "car", label = "Car", value = "135 km range", level = 33.0)
    val f = faceOf(car)
    assertEquals("33%", f.badge)
    assertEquals(0.33f, f.bar!!, 0.001f)
    assertFalse(f.low)
    assertEquals("135 km range", f.status)
    assertTrue(faceOf(car.copy(level = 12.0)).low)
    assertNull(faceOf(PanelRow(key = "x", kind = "home", label = "x")).badge)
  }

  @Test fun figuresAreTheirFirstWordLarge() {
    assertEquals("251.30" to "USD", splitFigure("251.30 USD"))
    assertEquals("12" to "", splitFigure(" 12 "))
    assertEquals("1" to "of 8 on", splitFigure("1 of 8 on"))
  }

  @Test fun whereAReadingComesFrom() {
    assertEquals("hour", everyWords(60))
    assertEquals("day", everyWords(1440))
    assertEquals("3 hours", everyWords(180))
    assertEquals("90 minutes", everyWords(90))
    assertEquals("Looked up every hour, even with Home closed", infoLine(null, 60, true))
    assertEquals("Reads calendar check each minute while Home is open", infoLine("calendar_check", null, false))
    assertNull(infoLine(null, null, false))
    assertEquals("Checked 9:25 PM", checkedAt(1_700_000_000_000L - 1_700_000_000_000L % 86_400_000L + (21 * 60 + 25) * 60_000L, ZoneOffset.UTC))
  }
}
