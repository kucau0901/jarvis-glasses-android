package app.jarvis.glasses.ui

import app.jarvis.glasses.glasses.GlassesState
import app.jarvis.glasses.glasses.GlassesState.Link
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusPanelTest {
  @Test fun theGlassesAreSaidByWhatTheyAreThenHowTheyAre() {
    assertEquals(listOf("NO GLASSES"), glassesLines(null))
    assertEquals(listOf("RAY-BAN META", "ON FACE"), glassesLines(GlassesState("x", "Ray-Ban Meta", Link.CONNECTED, worn = true)))
    assertEquals(listOf("TEST GLASSES", "FOLDED"), glassesLines(GlassesState("Test Glasses", link = Link.CONNECTED, worn = false, folded = true)))
    assertEquals(listOf("TEST GLASSES", "OFF FACE"), glassesLines(GlassesState("Test Glasses", link = Link.CONNECTED, worn = false)))
    assertEquals(listOf("GLASSES", "CONNECTED"), glassesLines(GlassesState("", link = Link.CONNECTED)))
    assertEquals(listOf("X", "AWAY"), glassesLines(GlassesState("x", link = Link.AWAY, worn = true)))
    assertEquals(listOf("X", "CONNECTED", "WARM", "UPDATE NEEDED"), glassesLines(GlassesState("x", link = Link.CONNECTED, hot = true, needsUpdate = "Update")))
  }

  @Test fun aBatteryIsFilledToItsLevel() {
    val full = batteryDots(100)
    assertEquals(7, full.size)
    assertEquals("###########..", full[0])
    assertEquals("###########..", full[1])
    assertEquals("###########", full[2].take(11))
    assertEquals("#####.....###", batteryDots(40)[2])
    assertEquals("#.........#..", batteryDots(null)[1])
    assertEquals("##........#..", batteryDots(1)[1])
  }
}
