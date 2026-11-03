package app.jarvis.glasses.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownTest {
  private fun shown(json: String) = Shown.from(Json.parseToJsonElement(json).jsonObject)

  @Test
  fun aMapIsAskedForAsTheWebAsksForIt() {
    val s = shown("""{"type":"display","kind":"map","lat":3.1578,"lng":101.7123,"label":"KLCC","maptype":"roadmap","embedUrl":"https://www.google.com/maps/embed/v1/place?key=x"}""") as Shown.Place
    assertEquals(Shown.Place(3.1578, 101.7123, "KLCC", street = false, satellite = false), s)
    assertEquals("/api/map?kind=map&lat=3.15780&lng=101.71230&sv=3&w=640&h=400&maptype=roadmap", s.path)
    assertEquals("geo:3.157800,101.712300?q=3.157800,101.712300(KLCC)", s.open)
  }

  @Test
  fun streetViewAndSatelliteOpenAsThemselves() {
    val sv = shown("""{"type":"display","kind":"streetview","lat":3.1,"lng":101.6,"label":"Home","maptype":"roadmap"}""") as Shown.Place
    assertEquals("/api/map?kind=streetview&lat=3.10000&lng=101.60000&sv=3&w=640&h=400", sv.path)
    assertEquals("google.streetview:cbll=3.100000,101.600000", sv.open)
    val sat = shown("""{"type":"display","kind":"map","lat":3.1,"lng":101.6,"maptype":"satellite"}""") as Shown.Place
    assertEquals(true, sat.satellite)
    // Maps' own address, as geo: would lose the layer.
    assertEquals(sat.openOnWeb, sat.open)
    assertEquals("https://www.google.com/maps/@?api=1&map_action=map&center=3.100000,101.600000&zoom=17&basemap=satellite", sat.openOnWeb)
  }

  @Test
  fun aCameraAPictureAndAPage() {
    assertEquals("/api/camera?entity=camera.front_gate&h=540", (shown("""{"type":"display","kind":"camera","entity":"camera.front_gate","label":"Front gate"}""") as Shown.Camera).path)
    assertEquals("/api/camera?entity=url%3Afront-gate&h=540", (shown("""{"type":"display","kind":"camera","entity":"url:front-gate"}""") as Shown.Camera).path)
    assertEquals("/api/image?id=img_abc123", (shown("""{"type":"display","kind":"image","image":"img_abc123","label":"a cat"}""") as Shown.Picture).path)
    assertEquals(Shown.Page("pg_1", "Trip plan"), shown("""{"type":"display","kind":"page","page":"pg_1","label":"Trip plan"}"""))
    assertEquals(Shown.Close, shown("""{"type":"display","kind":"close"}"""))
  }

  @Test
  fun whatCantBeDrawnIsLeftOut() {
    assertNull(shown("""{"type":"progress","text":"x"}"""))
    assertNull(shown("""{"type":"display","kind":"map","lat":"3.1","lng":101.6}"""))
    assertNull(shown("""{"type":"display","kind":"map","lat":95,"lng":101.6}"""))
    assertNull(shown("""{"type":"display","kind":"camera"}"""))
    assertNull(shown("""{"type":"display","kind":"hologram"}"""))
  }
}
