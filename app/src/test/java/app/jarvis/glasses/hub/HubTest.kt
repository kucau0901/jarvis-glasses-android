package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
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

  @Test
  fun aTypedQuestionCarriesRecentTurnsOnly() {
    val now = 10 * 3_600_000L
    val history = listOf(Said("old0001", now - 4 * 3_600_000L, "user", "old")) + List(25) { Said("n$it" + "aaaa", now - 1000, "user", "q$it") }
    val t = Hub.transcript(history, now)
    assertEquals(20, t.size)
    assertTrue(t.none { it == Turn("user", "old") })
  }
}
