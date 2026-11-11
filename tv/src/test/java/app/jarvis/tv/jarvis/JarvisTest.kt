package app.jarvis.tv.jarvis

import android.content.SharedPreferences
import java.util.Base64
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JarvisTest {
  private val server = MockWebServer()
  private lateinit var prefs: TvPrefs
  private lateinit var jarvis: Jarvis

  @Before
  fun setUp() {
    server.start()
    prefs = TvPrefs(MemoryPrefs())
    prefs.url = server.url("/").toString()
    jarvis = Jarvis(prefs)
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun theBrowserHandedOverIsADisplayWithNoPictureOfItsOwn() {
    val shown = VoiceEvent.from(Json.parseToJsonElement("""{"type":"display","kind":"browser"}""").jsonObject)
    assertEquals(VoiceEvent.Display("browser", ""), shown)
    // Its screen is fetched again and again (browserScreen), not from an address.
    assertNull(jarvis.pictureUrl(shown as VoiceEvent.Display))
    assertNull(VoiceEvent.from(Json.parseToJsonElement("""{"type":"display","kind":"hologram"}""").jsonObject))
  }

  @Test
  fun theBrowsersScreenIsReadWithTheSignIn() = runTest {
    prefs.token = "jss1_tv"
    reply("""{"jpeg":"/9j/4AAQ","width":1280,"height":800,"url":"https://portal.example.com/login","title":"Sign in"}""")
    val s = jarvis.browserScreen()
    assertEquals(BrowserScreen("/9j/4AAQ", "https://portal.example.com/login", "Sign in"), s)
    assertEquals("Sign in", s.heading)
    assertEquals("https://portal.example.com/login", s.copy(title = "").heading)
    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/api/browser/screen", req.path)
    assertEquals("Bearer jss1_tv", req.getHeader("Authorization"))
  }

  @Test
  fun noBrowserOrOneNotAnsweringIsSaidInJarvissWords() = runTest {
    reply("""{"error":"No browser is set up"}""", 404)
    reply("""{"error":"The browser is not answering"}""", 503)
    val said = List(2) { runCatching { jarvis.browserScreen() }.exceptionOrNull() as Jarvis.JarvisException }
    assertEquals(listOf("No browser is set up" to 404, "The browser is not answering" to 503), said.map { it.message to it.status })
  }

  private fun reply(body: String, code: Int = 200) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

  private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

  private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

  // Prefs

  @Test
  fun prefsRules() {
    assertEquals("https://jarvis.example.com", TvPrefs.cleanUrl("  https://jarvis.example.com//  "))
    assertEquals(emptyMap<String, String>(), TvPrefs.gatewayOf("id.access", " "))
    assertEquals(mapOf("CF-Access-Client-Id" to "id.access", "CF-Access-Client-Secret" to "s3cret"), TvPrefs.gatewayOf(" id.access ", "s3cret"))
    assertTrue(Regex("t_[0-9a-f]{20}").matches(TvPrefs.newOrigin()))

    val p = TvPrefs(MemoryPrefs())
    assertEquals(TvPrefs.DEFAULT_URL, p.url)
    p.url = "https://other.example.com/"
    assertEquals("https://other.example.com", p.url)
    p.url = "   "
    assertEquals(TvPrefs.DEFAULT_URL, p.url)
    assertEquals(p.origin, p.origin)
    assertFalse(p.signedIn)
    p.token = "jss1_x"
    p.name = "Aminah"
    assertTrue(p.signedIn)
    p.signOut()
    assertFalse(p.signedIn)
    assertEquals("", p.name)
  }

  @Test
  fun onlyHttpsOrTheHomeNetworkGetsTheSignIn() {
    assertTrue(Jarvis.usable("https://jarvis.example.com"))
    assertTrue(Jarvis.usable("http://192.168.1.20:8787"))
    assertTrue(Jarvis.usable("http://localhost:1234"))
    assertFalse(Jarvis.usable("http://jarvis.example.com"))
    assertFalse(Jarvis.usable("jarvis.example.com"))
  }

  // Pairing

  @Test
  fun pairingShowsACodeAndKeepsTheSignInOnceApproved() = runTest {
    prefs.accessId = "id.access"
    prefs.accessSecret = "s3cret"
    reply("""{"code":"KX7P2Q","poll":"jpp1_secret","expiresAt":1793000000000}""")
    reply("""{"status":"waiting"}""")
    reply("""{"status":"approved","token":"jss1_abc","name":"Aminah"}""")

    val code = jarvis.pairStart()
    assertEquals("KX7P2Q", code.code)
    assertEquals(1793000000000, code.expiresAt)
    assertFalse("the poll secret stays out of logs", code.toString().contains("jpp1"))
    assertEquals(Jarvis.Paired.Approved("jss1_abc", "Aminah"), jarvis.pairAwait(code, every = 1))
    assertEquals("jss1_abc", prefs.token)
    assertEquals("Aminah", prefs.name)
    assertTrue(prefs.signedIn)

    val start = server.takeRequest()
    assertEquals("/api/auth/pair/start", start.path)
    assertEquals("Jarvis TV", start.json().s("label"))
    // Cloudflare Access on every call; no sign-in before there is one.
    assertEquals("id.access", start.getHeader("CF-Access-Client-Id"))
    assertEquals("s3cret", start.getHeader("CF-Access-Client-Secret"))
    assertNull(start.getHeader("Authorization"))
    repeat(2) {
      val poll = server.takeRequest()
      assertEquals("/api/auth/pair/poll", poll.path)
      assertEquals("jpp1_secret", poll.json().s("poll"))
    }
  }

  @Test
  fun anExpiredCodeSaysSo() = runTest {
    reply("""{"status":"expired"}""")
    assertEquals(Jarvis.Paired.Expired, jarvis.pairAwait(Jarvis.PairCode("ABC234", "jpp1_p", 0), every = 1))
    assertFalse(prefs.signedIn)
  }

  // Home panels

  @Test
  fun panelsAreReadWithTheSignInAndGateway() = runTest {
    prefs.token = "jss1_tv"
    prefs.accessId = "id.access"
    prefs.accessSecret = "s3cret"
    reply(
        """
        {"panels":[
          {"id":"p_home","title":"Living room","rows":[
            {"key":"climate.living","kind":"thing","label":"Air con","value":"24°","icon":"ac","active":true,
             "actions":[{"action":"toggle","label":"Off"},{"action":"up","label":"+"}],
             "more":{"climate":{"now":27.5,"target":24,"min":16,"max":30,"step":0.5,"modes":["cool","dry","off"],"mode":"cool","fans":["auto","low"],"fan":"auto"}},
             "someNewField":1},
            {"key":"weather","kind":"tile","label":"Weather","icon":"sun","read":true,"tool":"weather","every":30,"refresh":true,"alerts":["above 33"]},
            {"key":"n1","kind":"note","label":"Buy milk","done":false,"note":"from Aminah"}
          ]}
        ],"can":{"home":true,"car":false}}
        """.trimIndent(),
    )
    val reply = jarvis.panels()
    val req = server.takeRequest()
    assertEquals("/api/hub/panels", req.path)
    assertEquals("Bearer jss1_tv", req.getHeader("Authorization"))
    assertEquals("id.access", req.getHeader("CF-Access-Client-Id"))
    assertEquals("s3cret", req.getHeader("CF-Access-Client-Secret"))

    assertEquals(true, reply.can?.home)
    val (ac, weather, note) = reply.panels.single().rows
    assertEquals(listOf("toggle", "up"), ac.actions.map { it.action })
    assertEquals(24.0, ac.more?.climate?.target)
    assertEquals(listOf("cool", "dry", "off"), ac.more?.climate?.modes)
    assertTrue(ac.active)
    assertTrue(weather.read)
    assertEquals(30, weather.every)
    assertEquals(listOf("above 33"), weather.alerts)
    assertEquals("note", note.kind)
    assertEquals(false, note.done)
  }

  @Test
  fun aTileIsReadKeptOrFresh() = runTest {
    reply("""{"text":"31° and sunny","figures":[{"label":"Feels","value":"36°"}],"at":1793000000000,"stale":true}""")
    val r = jarvis.read("p_home", "weather & sky", cached = true)
    assertEquals("31° and sunny", r.text)
    assertTrue(r.stale)
    assertEquals("/api/hub/panels/read?panel=p_home&key=weather+%26+sky&cached=1", server.takeRequest().path)
  }

  @Test
  fun aButtonPressSendsWordsAndReturnsJarvissReply() = runTest {
    reply("""{"text":"Set to 23°."}""")
    assertEquals("Set to 23°.", jarvis.act("p_home", "climate.living", "set", value = "23"))
    val req = server.takeRequest()
    assertEquals("/api/hub/panels/act", req.path)
    val body = req.json()
    assertEquals("p_home", body.s("panel"))
    assertEquals("climate.living", body.s("key"))
    assertEquals("set", body.s("action"))
    assertTrue("a value goes as words", body["value"]!!.jsonPrimitive.isString)
    assertEquals("23", body.s("value"))
    assertFalse(body.containsKey("member"))
  }

  @Test
  fun aPanelIsRemovedAndNotesAreKept() = runTest {
    reply("""{"removed":"Living room"}""")
    assertEquals("Living room", jarvis.removePanel("p_home"))
    val removed = server.takeRequest()
    assertEquals("/api/hub/panels/remove", removed.path)
    assertEquals("p_home", removed.json().s("panel"))

    reply("{}")
    jarvis.note("p_notes", add = "  Buy milk ")
    val note = server.takeRequest().json()
    assertEquals("Buy milk", note.s("add"))
    assertFalse(note.containsKey("clear"))

    reply("{}")
    jarvis.note("p_notes", clear = true)
    assertEquals("true", server.takeRequest().json().s("clear"))
  }

  @Test
  fun aFailureCarriesJarvissWordsAndStatus() = runTest {
    reply("""{"error":"This profile is locked: enter the PIN"}""", code = 423)
    try {
      jarvis.panels()
      fail("expected a failure")
    } catch (e: Jarvis.JarvisException) {
      assertEquals("This profile is locked: enter the PIN", e.message)
      assertEquals(423, e.status)
      assertTrue(e.locked)
    }
    reply("", code = 401)
    try {
      jarvis.panels()
      fail("expected a failure")
    } catch (e: Jarvis.JarvisException) {
      assertTrue(e.signedOut)
    }
  }

  @Test
  fun cloudflareAccessTurningTheTvAwayIsSaidSo() = runTest {
    server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://team.cloudflareaccess.com/cdn-cgi/access/login"))
    try {
      jarvis.panels()
      fail("expected a failure")
    } catch (e: Jarvis.JarvisException) {
      assertEquals(302, e.status)
      assertTrue(e.message!!.contains("Cloudflare Access"))
    }
  }

  // Asking

  @Test
  fun askStreamsEventsAsTheyCome() = runTest {
    prefs.token = "jss1_tv"
    val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 1)
    val b64 = Base64.getEncoder().encodeToString(mp3)
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                ": ping\n\n" +
                    "data: {\"type\":\"transcript\",\"text\":\"where is KLCC\"}\n\n" +
                    "data: {\"type\":\"progress\",\"text\":\"Looking it up\",\"name\":\"place\",\"id\":\"c1\"}\n\n" +
                    "data: {\"type\":\"tool\",\"name\":\"place\",\"id\":\"c1\",\"phase\":\"done\",\"preview\":\"KLCC\"}\n\n" +
                    "data: {\"type\":\"display\",\"kind\":\"map\",\"lat\":3.1579,\"lng\":101.7116,\"label\":\"KLCC\",\"maptype\":\"satellite\"}\n\n" +
                    "data: {\"type\":\"used\",\"tokens\":120}\n\n" +
                    ": ping\n\n" +
                    "data: {\"type\":\"result\",\"text\":\"It's in the city centre.\",\"tools\":[\"place\"]}\n\n" +
                    "data: {\"type\":\"audio\",\"seq\":0,\"last\":false,\"ms\":800,\"audio\":{\"format\":\"mp3\",\"mime\":\"audio/mpeg\",\"data\":\"$b64\"}}\n\n" +
                    "data: {\"type\":\"audio\",\"seq\":1,\"last\":true,\"ms\":0,\"audioError\":\"voice failed\"}\n\n",
            ),
    )
    val events = jarvis.ask("where is KLCC", listOf(Turn.user("hi"), Turn.jarvis("Hello."))).toList()
    val map = VoiceEvent.Display("map", "KLCC", lat = 3.1579, lng = 101.7116, maptype = "satellite")
    assertEquals(
        listOf(
            VoiceEvent.Heard("where is KLCC"),
            VoiceEvent.Progress("Looking it up"),
            VoiceEvent.Tool("place", done = true),
            map,
            VoiceEvent.Result("It's in the city centre.", error = false),
            VoiceEvent.Audio(0, false, mp3),
            VoiceEvent.Audio(1, true, null),
        ),
        events,
    )
    assertArrayEquals(mp3, (events[5] as VoiceEvent.Audio).mp3)

    val req = server.takeRequest()
    assertEquals("/api/v1/voice", req.path)
    assertEquals("text/event-stream", req.getHeader("Accept"))
    assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
    assertEquals("Bearer jss1_tv", req.getHeader("Authorization"))
    val body = req.json()
    assertEquals("where is KLCC", body.s("text"))
    assertEquals("audio", body.s("reply"))
    assertEquals("mp3", body.s("format"))
    assertEquals("true", body.s("screen"))
    assertEquals("assistant", body["context"]!!.jsonArray[1].jsonObject.s("role"))
    val origin = body["origin"]!!.jsonObject
    assertEquals(prefs.origin, origin.s("id"))
    assertEquals("TV", origin.s("label"))

    // The map comes from Jarvis's proxy, with the sign-in, and only from Jarvis.
    val url = jarvis.pictureUrl(map)!!
    assertEquals(prefs.url + "/api/map?kind=map&lat=3.15790&lng=101.71160&sv=3&w=960&h=540&maptype=satellite", url)
    assertNull(jarvis.pictureUrl(VoiceEvent.Display("page", "Plan", id = "pg1")))
    assertEquals("Bearer jss1_tv", jarvis.headers()["Authorization"])
    reply("PNG!")
    assertEquals("PNG!", String(jarvis.picture(url)))
    assertEquals("Bearer jss1_tv", server.takeRequest().getHeader("Authorization"))
    try {
      jarvis.picture("https://elsewhere.example.com/api/image?id=1")
      fail("the sign-in went elsewhere")
    } catch (e: Jarvis.JarvisException) {
      assertEquals(0, e.status)
    }
  }

  @Test
  fun anAnswerThatStopsShortSaysSo() = runTest {
    server.enqueue(MockResponse().setBody("data: {\"type\":\"progress\",\"text\":\"Thinking\"}\n\n"))
    val events = jarvis.ask("hi", emptyList()).toList()
    assertEquals(VoiceEvent.Result("Jarvis's answer ended early.", true), events.last())
  }

  @Test
  fun aRefusedQuestionIsAFailureWithJarvissWords() = runTest {
    reply("""{"error":"Voice is turned off for this profile"}""", code = 403)
    try {
      jarvis.ask("hi", emptyList()).toList()
      fail("expected a failure")
    } catch (e: Jarvis.JarvisException) {
      assertEquals("Voice is turned off for this profile", e.message)
      assertEquals(403, e.status)
    }
  }

  @Test
  fun anExchangeIsKeptInChat() = runTest {
    reply("{}")
    jarvis.remember("lights off", "Done.")
    val said = server.takeRequest().json()["said"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("user", "assistant"), said.map { it.s("role") })
    assertEquals(listOf("lights off", "Done."), said.map { it.s("text") })
    for (s in said) assertTrue(Regex("t[a-z0-9]{20}").matches(s.s("id")))

    reply("""{"error":"nope"}""", code = 500)
    jarvis.remember("a", "b") // swallowed
  }
}

/** SharedPreferences in memory, for [TvPrefs] off a device. */
private class MemoryPrefs : SharedPreferences {
  private val map = HashMap<String, Any?>()

  override fun getAll(): MutableMap<String, *> = HashMap(map)

  override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue

  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = map[key] as? MutableSet<String> ?: defValues

  override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue

  override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue

  override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue

  override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

  override fun contains(key: String): Boolean = key in map

  override fun edit(): SharedPreferences.Editor = Editor()

  override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

  override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

  private inner class Editor : SharedPreferences.Editor {
    private val changes = HashMap<String, Any?>()
    private val removed = HashSet<String>()
    private var clear = false

    private fun put(key: String, value: Any?): SharedPreferences.Editor {
      changes[key] = value
      return this
    }

    override fun putString(key: String, value: String?) = put(key, value)

    override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)

    override fun putInt(key: String, value: Int) = put(key, value)

    override fun putLong(key: String, value: Long) = put(key, value)

    override fun putFloat(key: String, value: Float) = put(key, value)

    override fun putBoolean(key: String, value: Boolean) = put(key, value)

    override fun remove(key: String): SharedPreferences.Editor {
      removed += key
      return this
    }

    override fun clear(): SharedPreferences.Editor {
      clear = true
      return this
    }

    override fun commit(): Boolean {
      apply()
      return true
    }

    override fun apply() {
      if (clear) map.clear()
      removed.forEach(map::remove)
      map.putAll(changes)
    }
  }
}
