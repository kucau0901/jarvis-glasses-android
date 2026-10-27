package app.jarvis.glasses.jarvis

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JarvisClientTest {
  private val server = MockWebServer()
  private lateinit var client: JarvisClient

  @Before
  fun setUp() {
    server.start()
    client = JarvisClient({ server.url("/").toString() }, { "jdv1_test" })
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun transcribeSendsTheRecordingAndReadsWhatWasHeard() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"ok":true,"transcript":" what's on today ","heardBy":"openai"}"""))
    assertEquals("what's on today", client.transcribe(byteArrayOf(1, 2, 3)))
    val req = server.takeRequest()
    assertEquals("/api/v1/voice", req.path)
    assertEquals("Bearer jdv1_test", req.getHeader("Authorization"))
    val body = req.body.readUtf8()
    assertTrue(body.contains("name=\"audio\""))
    assertTrue(body.contains("""{"transcribe":true}"""))
  }

  @Test
  fun askStreamsEventsAndReturnsTheAnswer() = runBlocking {
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"type\":\"progress\",\"text\":\"Checking\"}\n\n" +
                    "data: {\"type\":\"result\",\"text\":\"Two meetings.\",\"ok\":true}\n\n" +
                    "data: {\"type\":\"audio\",\"seq\":0,\"last\":true,\"audio\":{\"mime\":\"audio/mpeg\",\"data\":\"AAEC\"}}\n\n",
            ),
    )
    val seen = mutableListOf<VoiceEvent>()
    val answer = client.ask("what's on today", listOf(Turn.jarvis("Leave in ten minutes.")), byteArrayOf(9)) { seen += it }
    assertEquals(VoiceEvent.Answer("Two meetings.", true), answer)
    assertEquals(3, seen.size)

    val req = server.takeRequest()
    assertEquals("text/event-stream", req.getHeader("Accept"))
    val body = kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("what's on today", body["text"]!!.jsonPrimitive.content)
    assertEquals("assistant", body["context"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    assertTrue(body["images"]!!.jsonArray[0].jsonPrimitive.content.startsWith("data:image/jpeg;base64,"))
  }

  @Test
  fun aStreamWithNoAnswerIsAFailure() = runBlocking {
    server.enqueue(MockResponse().setBody("data: {\"type\":\"progress\",\"text\":\"x\"}\n\n"))
    try {
      client.ask("hi", emptyList(), null) {}
      fail("expected a failure")
    } catch (e: JarvisClient.JarvisException) {
      assertTrue(e.message!!.contains("ended early"))
    }
  }

  @Test
  fun speechIsNullWhenTheDeviceSpeaksItself() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(204))
    assertNull(client.speech("Leave in ten minutes."))
  }

  @Test
  fun aLiveSessionSendsTheOfferAndReturnsTheAnswer() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer","sessionId":"s1","voice":"cedar","restoredTurns":1}"""))
    assertEquals(JarvisClient.LiveAnswer("v=0 answer"), client.liveSession("v=0 offer", listOf(Turn.user("hi"))))
    val req = server.takeRequest()
    assertEquals("/api/session", req.path)
    val body = kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("v=0 offer", body["sdp"]!!.jsonPrimitive.content)
    assertEquals("mobile", body["client"]!!.jsonPrimitive.content)
    assertEquals(1, body["history"]!!.jsonArray.size)
    assertNull(body["opening"])
  }

  @Test
  fun aLiveSessionOpenedForAnAlertSendsItAndGetsTheFirstEvent() = runBlocking {
    val first = """{"type":"session.commentary.append","content":"Front door: It is unlocked.","delegation_id":null,"event_id":"opening_a1b2c3d4"}"""
    server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer","sessionId":"s1","voice":"cedar","restoredTurns":2,"first":$first}"""))
    val got = client.liveSession("v=0 offer", emptyList(), Alert("a1b2c3d4", "Front door", "It is unlocked.", speak = true, urgent = false))
    assertEquals("v=0 answer", got.sdp)
    assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(first), kotlinx.serialization.json.Json.parseToJsonElement(got.first!!))
    val body = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    val opening = body["opening"]!!.jsonObject
    assertEquals("a1b2c3d4", opening["id"]!!.jsonPrimitive.content)
    assertEquals("Front door", opening["title"]!!.jsonPrimitive.content)
    assertEquals("It is unlocked.", opening["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun aLiveSessionWithoutAKeySaysSo() = runBlocking {
    server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"OPENAI_API_KEY is not configured"}"""))
    try {
      client.liveSession("v=0", emptyList())
      fail("expected a failure")
    } catch (e: JarvisClient.JarvisException) {
      assertEquals("OPENAI_API_KEY is not configured", e.message)
    }
  }

  @Test
  fun aDelegationPostsTheTranscriptToTheStream() = runBlocking {
    server.enqueue(MockResponse().setBody("data: {\"type\":\"result\",\"text\":\"Done.\"}"))
    val seen = mutableListOf<String>()
    client.delegate("d1", listOf(Turn.user("lock the car"))) { seen += it["type"]!!.jsonPrimitive.content }
    assertEquals(listOf("result"), seen)
    val req = server.takeRequest()
    assertEquals("/api/v1/stream", req.path)
    val body = kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()).jsonObject
    assertEquals("d1", body["delegationId"]!!.jsonPrimitive.content)
    assertEquals("lock the car", body["transcript"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun failuresSayWhatToFix() {
    assertEquals("Jarvis didn't accept the device token", JarvisClient.errorOf(401, ""))
    assertEquals("text is required", JarvisClient.errorOf(400, """{"error":"text is required"}"""))
    assertEquals("Jarvis answered 500", JarvisClient.errorOf(500, "oops"))
  }
}
