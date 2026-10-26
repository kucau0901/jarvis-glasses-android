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

  @Test fun aSliderStaysOnItsOwnStepsFromItsMin() {
    assertEquals(1.5, onStep(1.7, -2.5, 2.5, 0.5), 1e-9)
    assertEquals(-1.5, onStep(-1.7, -2.5, 2.5, 0.5), 1e-9)
    assertEquals(5.0, sliderStart(1.0, 10.0, 2.0), 1e-9)
    assertEquals(9.0, sliderTop(1.0, 10.0, 2.0), 1e-9)
    assertEquals(9.0, onStep(10.0, 1.0, 10.0, 2.0), 1e-9)
    assertEquals(3, sliderSteps(1.0, 10.0, 2.0))
    assertEquals(9.0, sliderTop(0.0, 10.0, 3.0), 1e-9)
    assertEquals(0.3, onStep(0.31, 0.0, 1.0, 0.1), 1e-9)
  }

  @Test fun numbersGoWithTheirStepsDecimalsAndAPoint() {
    assertEquals("1.5", stepWords(1.5, 0.5))
    assertEquals("150.5", stepWords(150.5, 0.5))
    assertEquals("23.5", stepWords(23.5, 0.1))
    assertEquals("24", stepWords(24.0, 0.1))
    assertEquals("40", stepWords(40.0, 1.0))
    assertEquals("0.3", stepWords(0.30000000000000004, 0.1))
  }

  @Test fun anAircondsNextSetPointStaysInItsRange() {
    assertEquals(23.5, nextTemp(23.0, 0.5, 16.0, 30.0), 0.0)
    assertEquals(30.0, nextTemp(30.0, 1.0, 16.0, 30.0), 0.0)
    assertEquals(16.0, nextTemp(16.2, -1.0, 16.0, 30.0), 0.0)
  }

  @Test fun typedNumbersAreReadOrRefused() {
    assertEquals(23.5, parseNumber("23.5")!!, 0.0)
    assertEquals(23.5, parseNumber("23,5")!!, 0.0)
    assertEquals(-5.0, parseNumber(" -5 ")!!, 0.0)
    assertEquals(1000.0, parseNumber("1,000")!!, 0.0)
    assertEquals(1000.0, parseNumber("1 000")!!, 0.0)
    assertEquals(1234567.5, parseNumber("1,234,567.5")!!, 0.0)
    assertEquals(null, parseNumber("1,0000"))
    assertEquals(null, parseNumber("about 5"))
    assertEquals(null, parseNumber(""))
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
