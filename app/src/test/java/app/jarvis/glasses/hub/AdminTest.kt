package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class AdminTest {
  private val server = MockWebServer()
  private lateinit var admin: Admin

  @Before
  fun setUp() {
    server.start()
    admin = Admin(JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun clearingASettingSendsNull() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"groups":[],"settings":[]}"""))
    admin.save(mapOf("TESSIE_VIN" to null, "UNITS" to "metric"))
    val req = server.takeRequest()
    assertEquals("PUT", req.method)
    val changes = Json.parseToJsonElement(req.body.readUtf8()).jsonObject["changes"]!!.jsonObject
    assertEquals(JsonNull, changes["TESSIE_VIN"])
    assertEquals("metric", changes["UNITS"]!!.jsonPrimitive.content)
  }

  @Test
  fun aRefusedSaveSaysWhyBySetting() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(422).setBody("""{"saved":false,"errors":{"TESSIE_VIN":"must be 17 letters and digits"}}"""))
    assertEquals("must be 17 letters and digits", admin.save(mapOf("TESSIE_VIN" to "x")).errors!!["TESSIE_VIN"])
  }

  @Test(expected = Admin.NotAdmin::class)
  fun someoneNotAnAdminIsToldSo(): Unit = runBlocking {
    server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"only a family admin can do that"}"""))
    admin.settings()
  }

  @Test
  fun aModelThatFailsItsTestIsNotSwitchedTo() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(422).setBody("""{"saved":false,"probe":{"ok":false,"model":"gpt-x","stage":"tool","error":"no tools"},"model":"gpt-6-luna","source":"ui"}"""))
    val r = admin.useRouter("gpt-x")
    assertFalse(r.saved!!)
    assertEquals("tool", r.probe!!.stage)
    assertEquals("gpt-6-luna", r.model)
  }

  @Test
  fun startingTheChatgptSignInPostsWithNoBody() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"url":"https://auth.example.com/authorize?x=1"}"""))
    assertEquals("https://auth.example.com/authorize?x=1", admin.planStart())
    val req = server.takeRequest()
    assertEquals("POST", req.method)
    assertEquals("/api/chatgpt/start", req.path)
  }
}
