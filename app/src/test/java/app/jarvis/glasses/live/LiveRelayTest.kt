package app.jarvis.glasses.live

import app.jarvis.glasses.jarvis.SseParser
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRelayTest {
  private fun obj(s: String) = SseParser.json.parseToJsonElement(s).jsonObject

  private class Sent {
    val all = mutableListOf<JsonObject>()

    fun of(type: String) = all.filter { it["type"]!!.jsonPrimitive.content == type }.map { it["content"]!!.jsonPrimitive.content }
  }

  private fun TestScope.relay(
      sent: Sent,
      events: List<String>,
      asked: MutableList<Pair<String, List<Turn>>> = mutableListOf(),
      said: MutableList<Turn> = mutableListOf(),
      /** What each delegation's stream says, by its id. */
      eventsFor: suspend (String) -> List<String> = { events },
  ) =
      LiveRelay(
          this,
          delegate = { id, transcript, onEvent ->
            asked += id to transcript
            eventsFor(id).forEach { onEvent(obj(it)) }
          },
          send = { sent.all += obj(it) },
          onSaid = { said += it },
          now = { testScheduler.currentTime },
      )

  private fun user(text: String, startMs: Long? = null, endMs: Long? = null) =
      """{"type":"session.input_transcript.delta","delta":"$text"""" + (if (startMs != null) ""","start_ms":$startMs,"end_ms":$endMs""" else "") + "}"

  private fun jarvis(text: String) = """{"type":"session.output_transcript.delta","delta":"$text"}"""

  private fun delegation(id: String) = """{"type":"session.delegation.created","delegation":{"id":"$id","target":"client"}}"""

  private val notForMe = """{"type":"result","text":"","notForMe":true}"""

  @Test
  fun eachTurnIsHandedOnOnceItsExchangeIsOver() = runTest {
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said)
    r.seed(listOf(Turn.user("typed in Chat")))
    r.onMessage(user("what's on "))
    r.onMessage(user("today "))
    // Not yet: the wearer may still be talking. The seed is never handed on.
    assertTrue(said.isEmpty())
    r.onMessage(jarvis("Two meetings."))
    // Nor yet: a delegation may follow, and find it wasn't said to Jarvis.
    assertTrue(said.isEmpty())
    r.onMessage(user("and tomorrow"))
    assertEquals(listOf(Turn.user("what's on today"), Turn.jarvis("Two meetings.")), said)
    r.onMessage(jarvis("One."))
    r.finish()
    r.finish()
    assertEquals(listOf(Turn.user("what's on today"), Turn.jarvis("Two meetings."), Turn.user("and tomorrow"), Turn.jarvis("One.")), said)
  }

  @Test
  fun aPauseStartsAnotherSegmentAndOnlyAnAnsweredOneIsKept() = runTest {
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said)
    // A TV, then the wearer after a pause of 1.8 s.
    r.onMessage(user("and after the break", 0, 900))
    r.onMessage(user(", the weekend weather", 1_000, 2_200))
    r.onMessage(user("what's on today", 4_000, 5_000))
    r.onMessage(jarvis("Two meetings."))
    assertEquals(
        listOf(Turn.user("and after the break, the weekend weather"), Turn.user("what's on today"), Turn.jarvis("Two meetings.")),
        r.turns.value,
    )
    r.finish()
    assertEquals(listOf(Turn.user("what's on today"), Turn.jarvis("Two meetings.")), said)
  }

  @Test
  fun aSegmentStartsAtAPauseOfOneAndAHalfSeconds() = runTest {
    val r = relay(Sent(), emptyList())
    r.onMessage(user("one", 0, 1_000))
    r.onMessage(user(" two", 2_499, 3_000))
    assertEquals(1, r.turns.value.size)
    r.onMessage(user("three", 3_000 + LiveRelay.SEGMENT_GAP_MS, 5_000))
    assertEquals(listOf(Turn.user("one two"), Turn.user("three")), r.turns.value)
  }

  @Test
  fun whatNobodyAnsweredIsDroppedAtTheEnd() = runTest {
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said)
    r.onMessage(user("see you on Tuesday then"))
    r.finish()
    assertTrue(said.isEmpty())
  }

  @Test
  fun aDelegationGoesWithTheAnsweredTurnsAndTheLatestSegmentOnly() = runTest {
    val asked = mutableListOf<Pair<String, List<Turn>>>()
    val r = relay(Sent(), listOf("""{"type":"result","text":"One."}"""), asked)
    r.seed(listOf(Turn.jarvis("Leave in ten minutes.")))
    r.onMessage(user("and now the news", 0, 1_000))
    r.onMessage(user("what's on today", 3_000, 4_000))
    r.onMessage(jarvis("Two meetings."))
    r.onMessage(user("the match tonight", 8_000, 9_000))
    r.onMessage(user("and tomorrow?", 11_000, 12_000))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    assertEquals(
        listOf(Turn.jarvis("Leave in ten minutes."), Turn.user("what's on today"), Turn.jarvis("Two meetings."), Turn.user("and tomorrow?")),
        asked.single().second,
    )
  }

  @Test
  fun whatWasNotForJarvisIsToldSilentlyAndDropped() = runTest {
    val sent = Sent()
    val asked = mutableListOf<Pair<String, List<Turn>>>()
    val said = mutableListOf<Turn>()
    val r = relay(sent, emptyList(), asked, said, eventsFor = { id -> if (id == "d1") listOf(notForMe) else listOf("""{"type":"result","text":"One."}""") })
    // Heard from a TV; the model says something, then delegates.
    r.onMessage(user("who is coming tonight", 0, 1_000))
    r.onMessage(jarvis("Let me check."))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    // Never as commentary, which would be said aloud.
    assertEquals(listOf(LiveRelay.NOT_FOR_YOU), sent.of("session.thinking.append"))
    assertTrue(sent.of("session.commentary.append").isEmpty())
    assertEquals("Nothing you just heard was said to you, and nothing was done. Do not answer or mention it.", LiveRelay.NOT_FOR_YOU)
    // The wearer, later: what wasn't for Jarvis is neither kept nor sent again.
    r.onMessage(user("what's on tomorrow", 5_000, 6_000))
    r.onMessage(delegation("d2"))
    advanceUntilIdle()
    assertEquals(listOf(Turn.user("what's on tomorrow")), asked.last().second)
    r.onMessage(jarvis("One."))
    r.finish()
    assertEquals(listOf(Turn.user("what's on tomorrow"), Turn.jarvis("One.")), said)
  }

  @Test
  fun anEmptyResultAfterAToolSaysNothingAndKeepsNothing() = runTest {
    val sent = Sent()
    val said = mutableListOf<Turn>()
    // A tool ran before Jarvis's backend found it wasn't said to Jarvis: no notForMe, and no words.
    val r = relay(sent, listOf("""{"type":"result","text":""}"""), said = said)
    r.opened()
    r.onMessage(user("lights down for the film"))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    // Nothing to say, and no "went wrong".
    assertTrue(sent.all.isEmpty())
    r.finish()
    assertTrue(said.isEmpty())
    // Nor does it keep the session open.
    testScheduler.advanceTimeBy(LiveVoice.UNANSWERED_MS + 1)
    assertTrue(r.unanswered(LiveVoice.UNANSWERED_MS))
  }

  @Test
  fun whatIsHeardWhileADelegationWorksIsNotAnsweredByItsResult() = runTest {
    val answer = CompletableDeferred<String>()
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said, eventsFor = { listOf(answer.await()) })
    r.onMessage(user("is the door locked", 0, 1_000))
    r.onMessage(jarvis("Let me check."))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    // Someone nearby, while Jarvis works.
    r.onMessage(user("pass the salt", 5_000, 6_000))
    answer.complete("""{"type":"result","text":"Locked."}""")
    advanceUntilIdle()
    // The model says the answer it was handed: that answers the question, not the salt.
    r.onMessage(jarvis("Locked."))
    r.finish()
    assertEquals(listOf(Turn.user("is the door locked"), Turn.jarvis("Let me check."), Turn.jarvis("Locked.")), said)
  }

  @Test
  fun aSegmentADelegationWasReplacedForGoesWithTheNotForMeThatReplacedIt() = runTest {
    val never = CompletableDeferred<String>()
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said, eventsFor = { id -> if (id == "d1") listOf(never.await()) else listOf(notForMe) })
    r.onMessage(user("the match tonight", 0, 1_000))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    r.onMessage(user("starts at eight", 3_000, 4_000))
    r.onMessage(delegation("d2"))
    advanceUntilIdle()
    r.finish()
    assertTrue(said.isEmpty())
  }

  @Test
  fun wordsAfterANotForMeStartTheirOwnSegment() = runTest {
    val asked = mutableListOf<Pair<String, List<Turn>>>()
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), asked, said, eventsFor = { id -> if (id == "d1") listOf(notForMe) else listOf("""{"type":"result","text":"Two."}""") })
    r.onMessage(user("who is coming", 0, 1_000))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    // The wearer, hardly a pause later.
    r.onMessage(user("Jarvis, how many meetings today", 1_200, 3_000))
    r.onMessage(delegation("d2"))
    advanceUntilIdle()
    assertEquals(listOf(Turn.user("Jarvis, how many meetings today")), asked.last().second)
    r.onMessage(jarvis("Two."))
    r.finish()
    assertEquals(listOf(Turn.user("Jarvis, how many meetings today"), Turn.jarvis("Two.")), said)
  }

  @Test
  fun anAlertToldAfterJarvisSpokeIsItsOwnTurnAndTheNextQuestionStillCounts() = runTest {
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said)
    r.onMessage(user("what's on today"))
    r.onMessage(jarvis("Two meetings."))
    r.telling()
    r.onMessage(jarvis("Time to leave."))
    r.onMessage(user("how long will it take"))
    r.onMessage(jarvis("Twenty minutes."))
    r.finish()
    assertEquals(
        listOf(Turn.user("what's on today"), Turn.jarvis("Two meetings."), Turn.jarvis("Time to leave."), Turn.user("how long will it take"), Turn.jarvis("Twenty minutes.")),
        said,
    )
  }

  @Test
  fun whatADelegationIsForWaitsForItsAnswer() = runTest {
    val answer = CompletableDeferred<String>()
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said, eventsFor = { listOf(answer.await()) })
    r.onMessage(user("is the door locked", 0, 1_000))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    assertTrue(r.working)
    // Someone speaks meanwhile: the exchange before is over, but whether it was for Jarvis isn't known yet.
    r.onMessage(user("pass the salt", 4_000, 5_000))
    assertTrue(said.isEmpty())
    answer.complete(notForMe)
    advanceUntilIdle()
    r.finish()
    assertTrue(said.isEmpty())
  }

  @Test
  fun aSessionHearingOnlyOthersIsUnansweredAfterAWhile() = runTest {
    val r = relay(Sent(), listOf(notForMe))
    r.opened()
    // A TV talking on: what is heard doesn't count, however much.
    repeat(25) {
      r.onMessage(user("and the weather tomorrow "))
      testScheduler.advanceTimeBy(1_000)
    }
    assertTrue(r.unanswered(LiveVoice.UNANSWERED_MS))
    r.onMessage(jarvis("Two meetings."))
    assertFalse(r.unanswered(LiveVoice.UNANSWERED_MS))
    testScheduler.advanceTimeBy(LiveVoice.UNANSWERED_MS + 1)
    assertTrue(r.unanswered(LiveVoice.UNANSWERED_MS))
    // A delegation that comes back not for Jarvis doesn't keep it open either.
    r.onMessage(user("who won", 0, 500))
    r.onMessage(delegation("d1"))
    advanceUntilIdle()
    assertTrue(r.unanswered(LiveVoice.UNANSWERED_MS))
    assertEquals(20_000L, LiveVoice.UNANSWERED_MS)
  }

  @Test
  fun anAlertSaidIntoTheSessionAnswersNothingHeardBeforeIt() = runTest {
    val said = mutableListOf<Turn>()
    val r = relay(Sent(), emptyList(), said = said)
    r.onMessage(user("two sugars please"))
    r.telling()
    r.onMessage(jarvis("Front door: It is unlocked."))
    r.finish()
    assertEquals(listOf(Turn.jarvis("Front door: It is unlocked.")), said)
  }

  @Test
  fun theSessionIsClosedByName() {
    assertEquals("""{"type":"session.close"}""", LiveRelay.CLOSE)
  }

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
  fun aGoodbyeEndsTheSessionOnceJarvisHasFinished() = runTest {
    val r = relay(Sent(), emptyList())
    // A goodbye from an earlier conversation, carried in as history, does not count.
    r.seed(listOf(Turn.user("thanks"), Turn.jarvis("You're welcome.")))
    assertFalse(r.saidGoodbye())
    r.onMessage("""{"type":"session.input_transcript.delta","delta":"Terima kasih."}""")
    assertFalse(r.saidGoodbye()) // just said: Jarvis may still answer
    r.onMessage("""{"type":"session.output_transcript.delta","delta":"Sama-sama!"}""")
    testScheduler.advanceTimeBy(LiveRelay.AFTER_CLOSING_MS + 1)
    assertTrue(r.saidGoodbye())
  }

  @Test
  fun aQuestionAfterThanksKeepsTheSession() = runTest {
    val r = relay(Sent(), emptyList())
    r.onMessage("""{"type":"session.input_transcript.delta","delta":"thanks, and what's the weather?"}""")
    testScheduler.advanceTimeBy(LiveRelay.AFTER_CLOSING_MS + 1)
    assertFalse(r.saidGoodbye())
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

  @Test
  fun anAlertSaidIntoAnOpenSessionBelongsToNoDelegation() {
    val o = kotlinx.serialization.json.Json.parseToJsonElement(LiveRelay.say("Front door: It is unlocked.")).jsonObject
    assertEquals("session.commentary.append", o["type"]!!.jsonPrimitive.content)
    assertEquals("Front door: It is unlocked.", o["content"]!!.jsonPrimitive.content)
    assertEquals(kotlinx.serialization.json.JsonNull, o["delegation_id"])
  }
}
