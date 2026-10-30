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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

  @Test
  fun skillsAreListedWithWhetherEachHasItsKey() = runBlocking {
    server.enqueue(
        MockResponse()
            .setBody(
                """{"skills":[{"id":"kab12cd34","name":"Nova","url":"https://bots.example.com/nova","when":"the house and the car","later":true,"keyHeader":"X-Nova-Key","fields":{"room":"lounge"},"hasKey":true,"createdAt":1,"updatedAt":2},""" +
                    """{"id":"kef56gh78","name":"Lumen","url":"https://bots.example.com/lumen","when":"the garden","later":false,"fields":{},"hasKey":false,"createdAt":3,"updatedAt":3}]}""",
            ),
    )
    val (nova, lumen) = admin.skills().skills
    assertTrue(nova.hasKey)
    assertEquals("X-Nova-Key", nova.keyHeader)
    assertEquals(mapOf("room" to "lounge"), nova.fields)
    assertTrue(nova.later)
    assertFalse(lumen.hasKey)
    assertNull(lumen.keyHeader)
    val req = server.takeRequest()
    assertEquals("GET", req.method)
    assertEquals("/api/skills", req.path)
  }

  @Test
  fun aSkillsKeyIsPutWithItsId() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"skill":{"id":"kab12cd34","name":"Nova","url":"https://bots.example.com/nova","when":"","later":false,"fields":{},"hasKey":true,"createdAt":1,"updatedAt":2}}"""))
    assertTrue(admin.skillKey("kab12cd34", "nova-test-key-0001").hasKey)
    val req = server.takeRequest()
    assertEquals("PUT", req.method)
    assertEquals("/api/skills", req.path)
    val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("kab12cd34", body["id"]!!.jsonPrimitive.content)
    assertEquals("nova-test-key-0001", body["key"]!!.jsonPrimitive.content)
  }

  @Test
  fun aRefusedKeySaysWhy() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"a key is 8 to 512 characters, without spaces"}"""))
    try {
      admin.skillKey("kab12cd34", "short")
      fail("a refused key should throw")
    } catch (e: JarvisClient.JarvisException) {
      assertEquals("a key is 8 to 512 characters, without spaces", e.message)
    }
  }

  @Test
  fun removingASkillDeletesItById() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"ok":true}"""))
    admin.removeSkill("kab12cd34")
    val req = server.takeRequest()
    assertEquals("DELETE", req.method)
    assertEquals("/api/skills?id=kab12cd34", req.path)
  }
}
