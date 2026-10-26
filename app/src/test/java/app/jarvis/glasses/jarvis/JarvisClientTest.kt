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
  fun failuresSayWhatToFix() {
    assertEquals("Jarvis didn't accept the device token", JarvisClient.errorOf(401, ""))
    assertEquals("text is required", JarvisClient.errorOf(400, """{"error":"text is required"}"""))
    assertEquals("Jarvis answered 500", JarvisClient.errorOf(500, "oops"))
  }
}
