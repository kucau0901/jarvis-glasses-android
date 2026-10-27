package app.jarvis.glasses.conversation

import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
  /** Says each of [heard] in turn, then nothing (null: nobody spoke). */
  private class FakeEars(vararg heard: String) : Ears {
    val queue = ArrayDeque(heard.toList())
    val waits = mutableListOf<Long>()

    override suspend fun listen(startTimeoutMs: Long): ByteArray? {
      waits += startTimeoutMs
      return queue.removeFirstOrNull()?.toByteArray()
    }
  }

  private class FakeMouth(private val glasses: Boolean = true) : Mouth {
    val played = mutableListOf<String>()
    val said = mutableListOf<String>()
    var closed = false

    override suspend fun open() = glasses

    override fun close() {
      closed = true
    }

    override suspend fun cue() = Unit

    override suspend fun play(audio: ByteArray) {
      played += String(audio)
    }

    override suspend fun say(text: String) {
      said += text
    }
  }

  private class FakeBrain(private val withAudio: Boolean = true) : Brain {
    val asked = mutableListOf<Triple<String, List<Turn>, ByteArray?>>()

    override suspend fun transcribe(wav: ByteArray) = String(wav)

    override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit): VoiceEvent.Answer {
      asked += Triple(text, context, photoJpeg)
      val answer = VoiceEvent.Answer("answer to $text", true)
      onEvent(VoiceEvent.Progress("Checking"))
      onEvent(answer)
      if (withAudio) onEvent(VoiceEvent.Audio(0, true, "audio/mpeg", java.util.Base64.getEncoder().encodeToString("clip:$text".toByteArray())))
      return answer
    }

    override suspend fun speech(text: String) = "tts:$text".toByteArray()
  }

  /** Chat's conversation: [before] already there, and what Talk keeps after it. */
  private class FakeMemory(before: List<Turn> = emptyList()) : Memory {
    val turns = before.toMutableList()
    val kept = mutableListOf<Pair<List<Turn>, Alert?>>()

    override fun recent() = turns.toList()

    override fun keep(turns: List<Turn>, alert: Alert?) {
      kept += turns to alert
      alert?.let { this.turns += Turn.jarvis(it.text) }
      this.turns += turns
    }
  }

  private fun TestScope.conversation(
      ears: Ears,
      mouth: Mouth,
      brain: Brain,
      followUps: Boolean = true,
      photo: ByteArray? = byteArrayOf(7),
      memory: Memory = FakeMemory(),
  ) = Conversation(this, ears, mouth, { photo }, brain, memory, followUps = { followUps })

  @Test
  fun aQuestionIsAnsweredAndAFollowUpIsListenedFor() = runTest {
    val ears = FakeEars("what's on today", "and tomorrow")
    val mouth = FakeMouth()
    val brain = FakeBrain()
    val c = conversation(ears, mouth, brain)
    assertTrue(c.start())
    advanceUntilIdle()

    assertEquals(listOf("what's on today", "and tomorrow"), brain.asked.map { it.first })
    // The follow-up carries the first question and its answer.
    assertEquals(listOf(Turn.user("what's on today"), Turn.jarvis("answer to what's on today")), brain.asked[1].second)
    assertEquals(listOf("clip:what's on today", "clip:and tomorrow"), mouth.played)
    assertEquals(listOf(Conversation.firstWaitMs, Conversation.followUpWaitMs, Conversation.followUpWaitMs), ears.waits)
    assertTrue(mouth.closed)
    assertEquals(Conversation.Status.IDLE, c.status.value)
  }

  @Test
  fun withoutFollowUpsOneQuestionEndsIt() = runTest {
    val brain = FakeBrain()
    conversation(FakeEars("one", "two"), FakeMouth(), brain, followUps = false).start()
    advanceUntilIdle()
    assertEquals(listOf("one"), brain.asked.map { it.first })
  }

  @Test
  fun aClosingEndsItWithoutAskingJarvis() = runTest {
    val brain = FakeBrain()
    conversation(FakeEars("what time is it", "terima kasih"), FakeMouth(), brain).start()
    advanceUntilIdle()
    assertEquals(listOf("what time is it"), brain.asked.map { it.first })
  }

  @Test
  fun aQuestionAboutWhatIsSeenCarriesAPhoto() = runTest {
    val brain = FakeBrain()
    conversation(FakeEars("what am I looking at?", "who won the match"), FakeMouth(), brain).start()
    advanceUntilIdle()
    assertNotNull(brain.asked[0].third)
    assertNull(brain.asked[1].third)
  }

  @Test
  fun anAlertIsSaidFirstAndTheReplyKnowsIt() = runTest {
    val ears = FakeEars("yes, remind me in an hour", "and call Sam")
    val mouth = FakeMouth()
    val brain = FakeBrain()
    val memory = FakeMemory()
    val alert = Alert("a1", "Office", "Leave in ten minutes.", speak = true, urgent = false)
    conversation(ears, mouth, brain, memory = memory).start(alert)
    advanceUntilIdle()

    assertEquals("tts:Leave in ten minutes.", mouth.played.first())
    assertEquals(listOf(Turn.jarvis("Leave in ten minutes.")), brain.asked.first().second)
    assertEquals(Conversation.replyWaitMs, ears.waits.first())
    // The alert is kept before the reply, once, and the next question has it from Chat's.
    assertEquals(listOf(alert, null), memory.kept.map { it.second })
    assertEquals(Turn.jarvis("Leave in ten minutes."), brain.asked[1].second.first())
    assertEquals(1, brain.asked[1].second.count { it.text == "Leave in ten minutes." })
  }

  @Test
  fun anAnswerWithoutAudioIsSpokenByThePhone() = runTest {
    val mouth = FakeMouth()
    conversation(FakeEars("hello"), mouth, FakeBrain(withAudio = false), followUps = false).start()
    advanceUntilIdle()
    assertEquals(listOf("answer to hello"), mouth.said)
  }

  @Test
  fun withoutTheGlassesNothingIsHeardOrAsked() = runTest {
    val ears = FakeEars("hello")
    val brain = FakeBrain()
    val c = conversation(ears, FakeMouth(glasses = false), brain)
    c.start()
    advanceUntilIdle()
    assertTrue(ears.waits.isEmpty())
    assertTrue(brain.asked.isEmpty())
    assertTrue(c.note.value.isNotEmpty())
  }

  @Test
  fun aSecondStartWhileTalkingIsRefused() = runTest {
    val c = conversation(FakeEars("hello"), FakeMouth(), FakeBrain())
    assertTrue(c.start())
    assertFalse(c.start())
    advanceUntilIdle()
  }

  @Test
  fun eachAnsweredQuestionIsKeptWithItsAnswer() = runTest {
    val memory = FakeMemory()
    conversation(FakeEars("what's on today", "thanks"), FakeMouth(), FakeBrain(), memory = memory).start()
    advanceUntilIdle()
    // The closing is not asked, so not kept.
    assertEquals(listOf(listOf(Turn.user("what's on today"), Turn.jarvis("answer to what's on today"))), memory.kept.map { it.first })
  }

  @Test
  fun aNewTalkGoesOnFromChatsConversation() = runTest {
    val brain = FakeBrain()
    val before = listOf(Turn.user("typed in Chat"), Turn.jarvis("an answer"))
    conversation(FakeEars("and then?"), FakeMouth(), brain, followUps = false, memory = FakeMemory(before)).start()
    advanceUntilIdle()
    assertEquals(before, brain.asked.single().second)
  }

  @Test
  fun aFailedAnswerIsNotKept() = runTest {
    val memory = FakeMemory()
    val failing =
        object : Brain by FakeBrain(withAudio = false) {
          override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit) =
              VoiceEvent.Answer("Jarvis couldn't do that.", false)
        }
    conversation(FakeEars("do it"), FakeMouth(), failing, followUps = false, memory = memory).start()
    advanceUntilIdle()
    assertTrue(memory.kept.isEmpty())
  }

  @Test
  fun stoppingMidAnswerStopsThePlaying() = runTest {
    var finished = 0
    val slowMouth =
        object : Mouth by FakeMouth() {
          override suspend fun play(audio: ByteArray) {
            kotlinx.coroutines.delay(5_000)
            finished++
          }
        }
    val c = conversation(FakeEars("tell me a story"), slowMouth, FakeBrain())
    c.start()
    testScheduler.advanceTimeBy(1_000)
    c.stop()
    advanceUntilIdle()
    assertEquals(0, finished)
    assertEquals(Conversation.Status.IDLE, c.status.value)
  }

  @Test
  fun theLaunchPhraseSaidAgainIsNotAsked() = runTest {
    val brain = FakeBrain()
    conversation(FakeEars("How are you?", "Hi Meta, open Jarvis.", "Okay.", "what's the time"), FakeMouth(), brain).start()
    advanceUntilIdle()
    assertEquals(listOf("How are you?", "what's the time"), brain.asked.map { it.first })
  }

  @Test
  fun phrases() {
    assertTrue(Phrases.wantsPhoto("Jarvis, what's this?"))
    assertTrue(Phrases.wantsPhoto("Apa ni?"))
    assertFalse(Phrases.wantsPhoto("apa tujuan mesyuarat esok"))
    assertFalse(Phrases.wantsPhoto("can you see my calendar"))
    assertTrue(Phrases.isStop("Thanks!"))
    assertTrue(Phrases.isStop("ok, terima kasih"))
    assertFalse(Phrases.isStop("thanks, and what's the weather"))
    assertTrue(Phrases.isAck("Okay."))
    assertTrue(Phrases.isAck("Ага."))
    assertFalse(Phrases.isAck("okay, and tomorrow?"))
    assertTrue(Phrases.isLaunchPhrase("Hey Meta, open the Jarvis app"))
    assertTrue(Phrases.isLaunchPhrase("Hi Meta, open Jarvis."))
    assertFalse(Phrases.isLaunchPhrase("open jarvis settings on the car"))
    assertFalse(Phrases.isLaunchPhrase("what does meta mean"))
  }
}
