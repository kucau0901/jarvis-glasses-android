package app.jarvis.glasses.jarvis

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    // Heard by the glasses' microphone, which hears everyone.
    assertEquals(true, body["openMic"]!!.jsonPrimitive.content.toBoolean())
  }

  @Test
  fun aQuestionNotForJarvisComesBackEmptyAndSaysSo() = runBlocking {
    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: {\"type\":\"result\",\"text\":\"\",\"ok\":true,\"notForMe\":true}\n\n"))
    val seen = mutableListOf<VoiceEvent>()
    val answer = client.ask("and after the break, the weekend weather", emptyList(), null) { seen += it }
    assertEquals(VoiceEvent.Answer("", true, notForMe = true), answer)
    assertTrue(seen.none { it is VoiceEvent.Audio })
  }

  @Test
  fun onlyASpokenQuestionSaysTheMicrophoneHearsEveryone() {
    val spoken = JarvisClient.askBody("what's on today", emptyList(), null)
    assertEquals("true", spoken["openMic"]!!.jsonPrimitive.content)
    assertEquals("audio", spoken["reply"]!!.jsonPrimitive.content)
    // The G2's, asked for its display: Jarvis assumes it already.
    val shown = JarvisClient.askBody("what's on today", emptyList(), null, chars = 200)
    assertNull(shown["openMic"])
    assertEquals("200", shown["chars"]!!.jsonPrimitive.content)
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
  fun aQuestionStoppedBeforeTheAnswerIsHungUpAtOnce() = runBlocking {
    // Jarvis has the question and is slow to answer; the glasses come off meanwhile.
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val asking = launch(Dispatchers.IO) { client.ask("lock the front door", emptyList(), null) {} }
    assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
    // Not when the read times out, two minutes on; and as stopped, not as Jarvis out of reach (that would fail this test).
    withTimeout(10_000) { asking.cancelAndJoin() }
  }

  @Test
  fun anAnswerStoppedHalfWayIsReadNoFurther() = runBlocking {
    // Padded with a comment: MockWebServer reads the question at the same pace, and it must come in whole.
    val first = ": ${" ".repeat(500)}\ndata: {\"type\":\"progress\",\"text\":\"Locking\"}\n\n"
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(first + "data: {\"type\":\"result\",\"text\":\"Locked.\",\"ok\":true}\n\n")
            // The first frame at once, the rest three seconds later.
            .throttleBody(first.length.toLong(), 3, TimeUnit.SECONDS),
    )
    val seen = Channel<VoiceEvent>(Channel.UNLIMITED)
    val asking = launch(Dispatchers.IO) { client.ask("lock the front door", emptyList(), null) { seen.send(it) } }
    assertEquals(VoiceEvent.Progress("Locking"), withTimeout(10_000) { seen.receive() })
    withTimeout(10_000) { asking.cancelAndJoin() }
    // Past when the rest would have come: hung up, it never does.
    delay(3_500)
    assertTrue(seen.isEmpty)
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
    // Heard by a live session's microphone, which hears everyone.
    assertEquals("true", body["openMic"]!!.jsonPrimitive.content)
  }

  private fun clientOf(req: RecordedRequest) =
      kotlinx.serialization.json.Json.parseToJsonElement(req.body.readUtf8()).jsonObject["client"]!!.jsonPrimitive.content

  @Test
  fun aLiveSessionThroughTheGlassesSaysSoToAJarvisThatKnowsThem() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"ok":true,"version":"2.8.0"}"""))
    repeat(3) { server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer"}""")) }
    client.liveSession("v=0 offer", emptyList(), glasses = true)
    assertEquals("/api/health", server.takeRequest().path)
    assertEquals("glasses", clientOf(server.takeRequest()))
    // Asked once: the next session goes straight on.
    client.liveSession("v=0 offer", emptyList(), glasses = true)
    assertEquals("glasses", clientOf(server.takeRequest()))
    // Through the phone, "mobile" as before.
    client.liveSession("v=0 offer", emptyList(), glasses = false)
    assertEquals("mobile", clientOf(server.takeRequest()))
  }

  @Test
  fun anOlderJarvisIsToldMobileEvenThroughTheGlasses() = runBlocking {
    // An older one would take "glasses" for a car.
    for (health in listOf(MockResponse().setBody("""{"ok":true,"version":"2.7.4"}"""), MockResponse().setResponseCode(404))) {
      val fresh = JarvisClient({ server.url("/").toString() }, { "jdv1_test" })
      server.enqueue(health)
      server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer"}"""))
      fresh.liveSession("v=0 offer", emptyList(), glasses = true)
      assertEquals("/api/health", server.takeRequest().path)
      assertEquals("mobile", clientOf(server.takeRequest()))
    }
  }

  @Test
  fun aHealthThatWasNoAnswerIsAskedAgain() = runBlocking {
    // Jarvis restarting behind its tunnel: not taken for an older Jarvis.
    server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
    server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer"}"""))
    server.enqueue(MockResponse().setBody("""{"ok":true,"version":"2.8.0"}"""))
    server.enqueue(MockResponse().setBody("""{"sdp":"v=0 answer"}"""))
    client.liveSession("v=0 offer", emptyList(), glasses = true)
    assertEquals("/api/health", server.takeRequest().path)
    assertEquals("mobile", clientOf(server.takeRequest()))
    client.liveSession("v=0 offer", emptyList(), glasses = true)
    assertEquals("/api/health", server.takeRequest().path)
    assertEquals("glasses", clientOf(server.takeRequest()))
  }

  @Test
  fun versionsAreComparedByNumber() {
    assertTrue(JarvisClient.atLeast("2.8.0", "2.8.0"))
    assertTrue(JarvisClient.atLeast("2.10.0", "2.8.0"))
    assertTrue(JarvisClient.atLeast("v3.0", "2.8.0"))
    assertTrue(JarvisClient.atLeast("2.8.1-rc.1", "2.8.0"))
    assertFalse(JarvisClient.atLeast("2.7.12", "2.8.0"))
    assertFalse(JarvisClient.atLeast("", "2.8.0"))
    assertFalse(JarvisClient.atLeast("dev", "2.8.0"))
  }

  @Test
  fun failuresSayWhatToFix() {
    assertEquals("Jarvis didn't accept the device token", JarvisClient.errorOf(401, ""))
    assertEquals("text is required", JarvisClient.errorOf(400, """{"error":"text is required"}"""))
    assertEquals("Jarvis answered 500", JarvisClient.errorOf(500, "oops"))
  }
}
