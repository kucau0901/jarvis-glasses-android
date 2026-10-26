package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Can
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeExtrasTest {
  @Test fun chartNumbersAreWholeWhenLargeAndToAHundredthWhenSmall() {
    assertEquals("251", chartNumber(251.3))
    assertEquals("-120", chartNumber(-120.4))
    assertEquals("21.5", chartNumber(21.5))
    assertEquals("22", chartNumber(22.0))
    assertEquals("0.33", chartNumber(0.3333))
  }

  @Test fun aChartGoesEvenlyAcrossAndLowToHighUp() {
    val at = chartLine(listOf(10.0, 20.0, 15.0), 300f, 90f, 6f)
    assertEquals(listOf(6f, 150f, 294f), at.map { it.first })
    assertEquals(84f, at[0].second, 0.001f)
    assertEquals(6f, at[1].second, 0.001f)
    assertEquals(45f, at[2].second, 0.001f)
    assertTrue(chartLine(emptyList(), 300f, 90f, 6f).isEmpty())
    assertEquals(84f, chartLine(listOf(5.0, 5.0), 300f, 90f, 6f)[1].second, 0.001f)
  }

  @Test fun aSliderStartsInTheMiddleOnAStep() {
    assertEquals(50.0, sliderStart(0.0, 100.0, 1.0), 0.0)
    assertEquals(20.0, sliderStart(16.0, 25.0, 2.0), 0.0)
    assertEquals(99, sliderSteps(0.0, 100.0, 1.0))
    assertEquals(0, sliderSteps(0.0, 1.0, 1.0))
  }

  @Test fun modesAreSaidAsWords() {
    assertEquals("Fan only", pretty("fan_only"))
    assertEquals("Cool", pretty("cool"))
  }

  @Test fun anEmptyHomeSaysWhatAPanelCouldShow() {
    assertEquals(
        "Ask Jarvis for a panel and it appears here: “put the gate and the porch light on a panel”. " +
            "A panel can show things in the house, your car, what you have used and notes.",
        emptyHomeText(Can(home = true, car = true)),
    )
    assertEquals(
        "Ask Jarvis for a panel and it appears here: “make me a to-do panel”. A panel can show what you have used and notes.",
        emptyHomeText(null),
    )
    assertEquals("a", inWords(listOf("a")))
  }
}
