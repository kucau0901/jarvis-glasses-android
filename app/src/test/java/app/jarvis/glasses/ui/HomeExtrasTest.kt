package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Can
import app.jarvis.glasses.hub.LightColor
import app.jarvis.glasses.hub.PanelMore
import app.jarvis.glasses.hub.PanelsReply
import app.jarvis.glasses.hub.Hub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

  @Test fun aColourIsSentAsWholeNumbersWithHue360AsNought() {
    assertEquals("color:120,100", colorAction(120, 100))
    assertEquals("color:0,100", colorAction(360, 100))
    assertEquals("color:0,0", colorAction(0, -5))
    assertEquals("kelvin:2700", kelvinAction(2700))
    assertEquals(listOf("color:0,100", "color:30,100", "color:55,100", "color:120,100", "color:180,100", "color:220,100", "color:275,100", "color:320,100", "color:0,0"), SWATCHES.map { it.action })
    assertEquals("White", SWATCHES.last().name)
  }

  @Test fun theNearestSwatchIsMarkedOnlyWhileTheLightIsOn() {
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

  @Test fun aShadeOfWhiteIsToTheNearest50KWithinTheLight() {
    assertEquals(4000, kelvinAt(4013.0, 2000.0, 6500.0))
    assertEquals(4050, kelvinAt(4030.0, 2000.0, 6500.0))
    assertEquals(2202, kelvinAt(2190.0, 2202.0, 6535.0))
    assertEquals(6535, kelvinAt(6540.0, 2202.0, 6535.0))
    assertEquals(2700, kelvinAt(5000.0, 2700.0, 2700.0))
  }

  @Test fun aLightsColourIsReadAndAnythingElseHasNone() {
    val json =
        """{"panels":[{"id":"p","title":"Bedroom","rows":[{"key":"room","kind":"room","label":"Lights","more":{"members":[
          {"key":"light.living_strip","kind":"light","label":"Strip","more":{"level":{"name":"Brightness","value":60,"min":1,"max":100,"step":1,"unit":"%"},
            "color":{"wheel":true,"hue":220,"sat":100,"white":true,"kelvin":4000.0,"warm":2000,"cool":6500,"on":true}}},
          {"key":"switch.fan_plug","kind":"switch","label":"Fan","more":{}},
          {"key":"light.hall","kind":"light","label":"Hall"}]}}]}]}"""
    val members = Hub.JSON_IN.decodeFromString(PanelsReply.serializer(), json).panels[0].rows[0].more!!.members!!
    val strip = members[0].more!!
    assertEquals(LightColor(wheel = true, hue = 220.0, sat = 100.0, white = true, kelvin = 4000.0, warm = 2000.0, cool = 6500.0, on = true), strip.color)
    assertEquals(60.0, strip.level!!.value, 0.0)
    assertEquals(PanelMore(), members[1].more)
    assertNull(members[2].more)
  }
}
