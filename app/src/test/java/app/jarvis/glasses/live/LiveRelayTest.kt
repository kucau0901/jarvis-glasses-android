package app.jarvis.glasses.live

import app.jarvis.glasses.jarvis.SseParser
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRelayTest {
  private fun obj(s: String) = SseParser.json.parseToJsonElement(s).jsonObject

  private class Sent {
    val all = mutableListOf<JsonObject>()

    fun of(type: String) = all.filter { it["type"]!!.jsonPrimitive.content == type }.map { it["content"]!!.jsonPrimitive.content }
  }

  private fun TestScope.relay(sent: Sent, events: List<String>, asked: MutableList<Pair<String, List<Turn>>> = mutableListOf()) =
      LiveRelay(
          this,
          delegate = { id, transcript, onEvent ->
            asked += id to transcript
            events.forEach { onEvent(obj(it)) }
          },
          send = { sent.all += obj(it) },
          now = { testScheduler.currentTime },
      )

  @Test
  fun transcriptDeltasBuildTurns() = runTest {
    val r = relay(Sent(), emptyList())
    r.seed(listOf(Turn.jarvis("Leave in ten minutes.")))
    r.onMessage("""{"type":"session.input_transcript.delta","delta":"what's on "}""")
    r.onMessage("""{"type":"session.input_transcript.delta","delta":"today"}""")
    r.onMessage("""{"type":"session.output_transcript.delta","delta":"Two meetings."}""")
    assertEquals(
        listOf(Turn.jarvis("Leave in ten minutes."), Turn.user("what's on today"), Turn.jarvis("Two meetings.")),
        r.turns.value,
    )
  }

  @Test
  fun aDelegationSendsTheConversationAndSpeaksTheAnswer() = runTest {
    val sent = Sent()
    val asked = mutableListOf<Pair<String, List<Turn>>>()
    val r = relay(sent, listOf("""{"type":"progress","text":"Checking the calendar"}""", """{"type":"result","text":"Two meetings."}"""), asked)
    r.onMessage("""{"type":"session.input_transcript.delta","delta":"what's on today"}""")
    r.onMessage("""{"type":"session.delegation.created","delegation":{"id":"d1","target":"client"}}""")
    advanceUntilIdle()

    assertEquals(listOf("d1" to listOf(Turn.user("what's on today"))), asked)
    assertEquals(listOf("Progress: Checking the calendar."), sent.of("session.thinking.append"))
    assertEquals(listOf("Two meetings."), sent.of("session.commentary.append"))
    assertEquals("d1", sent.all.last()["delegation_id"]!!.jsonPrimitive.content)
  }

  @Test
  fun aStreamThatEndsWithoutAnAnswerIsSaidToHaveFailed() = runTest {
    val sent = Sent()
    relay(sent, listOf("""{"type":"progress","text":"x"}""")).onMessage(
        """{"type":"session.delegation.created","delegation":{"id":"d1","target":"client"}}""",
    )
    advanceUntilIdle()
    assertTrue(sent.of("session.commentary.append").single().contains("went wrong"))
  }

  @Test
  fun delegationsForOthersAreLeftAlone() = runTest {
    val asked = mutableListOf<Pair<String, List<Turn>>>()
    relay(Sent(), emptyList(), asked).onMessage("""{"type":"session.delegation.created","delegation":{"id":"d1","target":"server"}}""")
    advanceUntilIdle()
    assertTrue(asked.isEmpty())
  }

  @Test
  fun longAnswersAreSplitOnSentences() {
    val sentence = "This is one sentence of a long answer. "
    val parts = LiveRelay.chunks(sentence.repeat(60).trim())
    assertTrue(parts.size > 1)
    assertTrue(parts.all { it.length <= 1200 })
    assertTrue(parts.dropLast(1).all { it.endsWith(".") })
    assertEquals(listOf("short"), LiveRelay.chunks("short"))
  }
}
