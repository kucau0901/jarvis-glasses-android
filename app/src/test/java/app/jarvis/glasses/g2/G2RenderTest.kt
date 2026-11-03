package app.jarvis.glasses.g2

import app.jarvis.glasses.ui.duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class G2RenderTest {
  private fun G2Strip.inside(w: Int, h: Int) = left >= 0 && top >= 0 && left + width <= w && top + height <= h

  private fun G2Strip.centredIn(w: Int, h: Int) {
    assertEquals(w - width, 2 * left, 0.01f)
    assertEquals(h - height, 2 * top, 0.01f)
  }

  @Test fun inPortraitItIsAlmostTheFullWidthInTheMiddle() {
    val strip = g2Strip(1080, 1920)
    assertTrue(strip.inside(1080, 1920))
    assertTrue(strip.width > 1000)
    assertEquals(strip.width / 2, strip.height, 0.01f)
    strip.centredIn(1080, 1920)
  }

  @Test fun inLandscapeItTakesUnderHalfTheHeightInTheMiddle() {
    val strip = g2Strip(1920, 1080)
    assertTrue(strip.inside(1920, 1080))
    assertTrue(strip.height <= 1080 * 0.45f + 0.01f)
    strip.centredIn(1920, 1080)
  }

  @Test fun theRecordingTimeReadsAsMinutesAndSeconds() {
    assertEquals("0:00", duration(0))
    assertEquals("0:42", duration(42_500_000_000))
    assertEquals("12:05", duration(725_000_000_000))
  }
}
