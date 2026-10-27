package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HubTest {
  private val server = MockWebServer()
  private lateinit var hub: Hub

  @Before
  fun setUp() {
    server.start()
    hub = Hub(JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { mapOf("CF-Access-Client-Id" to "cf") }))
  }

  @After fun tearDown() = server.shutdown()

  private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

  @Test
  fun panelsAreRead() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                """{"panels":[{"id":"p1","title":"Gate","rows":[
                  {"key":"cover.gate","kind":"home","label":"Front gate","value":"Closed","icon":"gate","active":false,
                   "tiles":[],"actions":[{"action":"open","label":"Open"}],"extra":1},
                  {"key":"light.a,light.b","kind":"home","label":"Lounge","value":"2 on","icon":"light","active":true,"tiles":[],
                   "actions":[{"action":"on","label":"All on"}],
                   "more":{"members":[{"key":"light.a","kind":"home","label":"Lamp","value":"On","icon":"light","active":true,"tiles":[],"actions":[]}],
                           "level":{"name":"Brightness","value":40,"min":0,"max":100,"step":1,"unit":"%"}}}]}],
                  "can":{"home":true,"car":false}}""",
            ),
    )
    val p = hub.panels().panels.single()
    assertEquals("Gate", p.title)
    assertEquals("open", p.rows[0].actions.single().action)
    assertEquals("Lamp", p.rows[1].more!!.members!!.single().label)
    assertEquals(40.0, p.rows[1].more!!.level!!.value, 0.0)
    val req = server.takeRequest()
    assertEquals("/api/hub/panels", req.path)
    assertEquals("Bearer jss1_test", req.getHeader("Authorization"))
    assertEquals("cf", req.getHeader("CF-Access-Client-Id"))
  }

  @Test
  fun pressingATileSendsItsAction() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"text":"Gate: Open."}"""))
    assertEquals("Gate: Open.", hub.act("p1", "cover.gate", "level:40", member = "light.a", value = "40"))
    val b = body()
    assertEquals("level:40", b["action"]!!.jsonPrimitive.content)
    assertEquals("light.a", b["member"]!!.jsonPrimitive.content)
    assertEquals(40.0, b["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
  }

  @Test
  fun aDeviceTokenIsToldToSignIn() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"that is for a person, not a device"}"""))
    try {
      hub.panels()
      fail("expected NotAPerson")
    } catch (_: Hub.NotAPerson) {}
  }

  @Test
  fun aLockedProfileSaysSo() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(423).setBody("""{"error":"locked","locked":true}"""))
    try {
      hub.proposals()
      fail("expected Locked")
    } catch (_: Hub.Locked) {}
  }

  @Test
  fun movingPanelsSendsTheirOrder() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"order":["p2","p1","p3"]}"""))
    assertEquals(listOf("p2", "p1", "p3"), hub.orderPanels(listOf("p2", "p1")))
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/hub/panels/order", req.path)
    assertEquals("Bearer jss1_test", req.getHeader("Authorization"))
    val b = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals(setOf("order"), b.keys)
    assertEquals(listOf("p2", "p1"), b["order"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun movingThingsSendsThePanelAndEachKeyOnce() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"order":["car1","cover.gate"]}"""))
    hub.orderThings("p1", listOf("car1", "car1", "cover.gate"))
    val b = body()
    assertEquals("p1", b["panel"]!!.jsonPrimitive.content)
    assertEquals(listOf("car1", "cover.gate"), b["order"]!!.jsonArray.map { it.jsonPrimitive.content })
  }

  @Test
  fun aMoveInAGonePanelSaysWhy() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"There is no such panel."}"""))
    try {
      hub.orderThings("p9", listOf("a"))
      fail("expected an error")
    } catch (e: JarvisClient.JarvisException) {
      assertTrue(e.message!!, e.message!!.contains("no such panel"))
    }
  }

  @Test
  fun aMoveTooLongForJarvisIsNotSent() = runBlocking {
    try {
      hub.orderThings("p1", List(200) { "light.lamp_number_$it" })
      fail("expected an error")
    } catch (e: JarvisClient.JarvisException) {
      assertEquals("Too many tiles to move from the phone", e.message)
    }
    assertEquals(0, server.requestCount)
  }

  @Test
  fun aTilesKeptReadingIsAskedForAndRead() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                """{"text":"XYZ: 251.30 USD","figures":[{"label":"Price","value":"251.30 USD"}],"list":["Up 1.2% today"],
                  "chart":{"title":"Week","points":[{"x":"Mon","y":250},{"x":"Tue","y":251.3}]},"at":1700000000000,
                  "source":"https://example.com/quote","stale":true}""",
            ),
    )
    val r = hub.read("p_1", "t_ab", cached = true)
    assertEquals("XYZ: 251.30 USD", r.text)
    assertEquals("251.30 USD", r.figures.single().value)
    assertEquals(2, r.chart!!.points.size)
    assertTrue(r.stale)
    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/api/hub/panels/read?panel=p_1&key=t_ab&cached=1", req.path)
    assertEquals("Bearer jss1_test", req.getHeader("Authorization"))
  }

  @Test
  fun aFreshReadingIsAskedForWithoutCached() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"text":"Nothing in the calendar for today.","list":["1. Dentist, 15:00"]}"""))
    val r = hub.read("p 1", "t_cd", cached = false)
    assertEquals(listOf("1. Dentist, 15:00"), r.list)
    assertEquals("/api/hub/panels/read?panel=p+1&key=t_cd", server.takeRequest().path)
  }

  @Test
  fun aReadingThatFailsSaysWhy() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"Could not read it just now: timeout"}"""))
    try {
      hub.read("p_1", "t_ab", cached = false)
      fail("expected an error")
    } catch (e: JarvisClient.JarvisException) {
      assertEquals("Could not read it just now: timeout", e.message)
    }
  }

  @Test
  fun aNumbersDayIsRead() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"points":[{"t":1,"v":21.5},{"t":2,"v":22}],"unit":"°C"}"""))
    val h = hub.history("p_1", "sensor.lounge")
    assertEquals(22.0, h.points.last().v, 0.0)
    assertEquals("°C", h.unit)
    assertEquals("/api/hub/panels/history?panel=p_1&key=sensor.lounge", server.takeRequest().path)
  }

  @Test
  fun typedWordsAreSentAsWords() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"text":"Done."}"""))
    hub.act("p_1", "t_ab", "say", value = "007")
    assertEquals("\"007\"", body()["value"].toString())
  }

  @Test
  fun panelRowsKeepWhatTheWebShows() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                """{"panels":[{"id":"p1","title":"Things","rows":[
                  {"key":"t_ab","kind":"tile","label":"Share","value":"","icon":"web","active":false,"tiles":[],"actions":[],
                   "read":true,"every":60,"refresh":true,"more":{"alert":{"kind":"words"}},"alerts":["above 150"]},
                  {"key":"climate.x","kind":"home","label":"Aircond","value":"Cool","icon":"climate","active":true,"tiles":[],"actions":[],
                   "more":{"climate":{"min":16,"max":30,"step":1,"modes":["cool","off"],"mode":"cool","fans":["low","high"],"fan":"low"}}},
                  {"key":"sensor.t","kind":"home","label":"Lounge","value":"24 °C","icon":"temp","active":false,"tiles":[],"actions":[],
                   "more":{"history":true,"alert":{"kind":"number","unit":"°C","value":24}}},
                  {"key":"car1","kind":"car","label":"Car","value":"300 km","icon":"car","active":false,"level":80,"tiles":[],"actions":[],
                   "more":{"map":{"lat":51.5007,"lng":-0.1246}}}]}],
                  "can":{"home":true,"car":true}}""",
            ),
    )
    val reply = hub.panels()
    val (tile, aircond, sensor, car) = reply.panels.single().rows
    assertTrue(tile.read)
    assertEquals(60, tile.every)
    assertTrue(tile.refresh)
    assertEquals(listOf("above 150"), tile.alerts)
    assertEquals("words", tile.more!!.alert!!.kind)
    assertEquals("low", aircond.more!!.climate!!.fan)
    assertEquals(listOf("low", "high"), aircond.more!!.climate!!.fans)
    assertTrue(sensor.more!!.history)
    assertEquals(24.0, sensor.more!!.alert!!.value!!, 0.0)
    assertEquals(-0.1246, car.more!!.map!!.lng, 0.0)
    assertEquals(80.0, car.level!!, 0.0)
    assertTrue(reply.can!!.car)
  }

  @Test
  fun alertMeSendsWhatWasChosen() = runBlocking {
    repeat(3) { server.enqueue(MockResponse().setBody("""{"text":"You will be told when Gate opens."}""")) }
    assertEquals("You will be told when Gate opens.", hub.alert("p_1", "cover.gate", state = "on"))
    assertEquals("on", body()["when"]!!.jsonPrimitive.content)
    hub.alert("p_1", "sensor.t", state = "above", value = 31.5)
    val n = body()
    assertEquals("above", n["when"]!!.jsonPrimitive.content)
    assertEquals(31.5, n["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
    hub.alert("p_1", "t_ab", condition = "above 150")
    val w = body()
    assertEquals("above 150", w["condition"]!!.jsonPrimitive.content)
    assertEquals(setOf("panel", "key", "condition"), w.keys)
  }

  @Test
  fun alertsNeedRoutines() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"alerts are routines, which need \"routines\"","need":"routines"}"""))
    try {
      hub.alert("p_1", "cover.gate", state = "on")
      fail("expected an error")
    } catch (e: JarvisClient.JarvisException) {
      assertTrue(e.message!!, e.message!!.contains("routines"))
    }
  }

  @Test
  fun toDosAreAddedAndClearedTicked() = runBlocking {
    repeat(2) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }
    hub.addNote("p_1", "Buy milk")
    val add = server.takeRequest()
    assertEquals("/api/hub/panels/note", add.path)
    val b = Json.parseToJsonElement(add.body.readUtf8()).jsonObject
    assertEquals("Buy milk", b["add"]!!.jsonPrimitive.content)
    hub.clearTicked("p_1")
    assertEquals("true", body()["clear"].toString())
  }

  @Test
  fun aMapIsAskedForAsTheWebDoes() = runBlocking {
    assertEquals("/api/map?kind=map&lat=51.50070&lng=-0.12460&sv=3&w=640&h=300&zoom=15", Hub.mapPath(51.5007, -0.1246))
    server.enqueue(MockResponse().setBody(okio.Buffer().write(byteArrayOf(1, 2, 3))))
    assertEquals(3, hub.map(51.5007, -0.1246).size)
    val req = server.takeRequest()
    assertEquals("/api/map?kind=map&lat=51.50070&lng=-0.12460&sv=3&w=640&h=300&zoom=15", req.path)
    assertEquals("Bearer jss1_test", req.getHeader("Authorization"))
  }

  @Test
  fun historyIsKeptTenAtATime() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"kept":2}"""))
    hub.keep(List(12) { Said("id$it" + "xxxx", 0, if (it % 2 == 0) "user" else "assistant", "t$it") })
    assertEquals(10, body()["said"]!!.jsonArray.size)
  }

  @Test
  fun proposalsAndLaterAreRead() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                """{"open":[{"id":"a1","person":"u","from":{"kind":"job","id":"j","title":"t"},"says":"Archive 40 promotions",
                    "why":"From your brief","action":{"tool":"x","args":{}},"risk":"reversible","verb":"Archive",
                    "createdAt":1,"expires":2,"state":"pending"}],
                  "recent":[],"allowed":[],"later":[{"id":"a2","says":"Lock the car","why":"","risk":"reversible","when":"when you get home"}]}""",
            ),
    )
    val p = hub.proposals()
    assertEquals("Archive", p.open.single().verb)
    assertEquals("when you get home", p.later.single().`when`)
  }
}
