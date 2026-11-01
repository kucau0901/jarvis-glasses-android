package app.jarvis.glasses.conversation

import app.jarvis.glasses.hub.SkillFrom
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
  /** Says each of [heard] in turn, then nothing (null: nobody spoke, or too long to be a question). */
  private class FakeEars(vararg heard: String) : Ears {
    val queue = ArrayDeque(heard.toList())
    val waits = mutableListOf<Long>()
    val caps = mutableListOf<Long>()

    override suspend fun listen(startTimeoutMs: Long, maxMs: Long): ByteArray? {
      waits += startTimeoutMs
      caps += maxMs
      return queue.removeFirstOrNull()?.toByteArray()
    }
  }

  private class FakeMouth(private val glasses: Boolean = true) : Mouth {
    val played = mutableListOf<String>()
    val said = mutableListOf<String>()
    var closed = false
    /** The tones and the closing, in order. */
    val log = mutableListOf<String>()

    override suspend fun open() = glasses

    override fun close() {
      closed = true
      log += "close"
    }

    override suspend fun cue() {
      log += "cue"
    }

    override suspend fun closingCue() {
      log += "closing"
    }

    override suspend fun play(audio: ByteArray) {
      played += String(audio)
    }

    override suspend fun say(text: String) {
      said += text
    }
  }

  /** Answers each question, but [notForMe] as Jarvis does what wasn't said to it: no audio, and [shown] (the G2's line) or no words. */
  private class FakeBrain(private val withAudio: Boolean = true, private val notForMe: Set<String> = emptySet(), private val shown: String = "") : Brain {
    val asked = mutableListOf<Triple<String, List<Turn>, ByteArray?>>()

    override suspend fun transcribe(wav: ByteArray) = String(wav)

    override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit): VoiceEvent.Answer {
      asked += Triple(text, context, photoJpeg)
      if (text in notForMe) return VoiceEvent.Answer(shown, true, notForMe = true).also { onEvent(it) }
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
      // As ChatLog keeps it: under its title, a bot's words quoted.
      alert?.let { this.turns += Turn.jarvis(it.told) }
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
      eyes: Eyes = Eyes { photo },
  ) = Conversation(this, ears, mouth, eyes, brain, memory, followUps = { followUps })

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
    assertEquals(5_000L, Conversation.followUpWaitMs)
    // The opening question may run long; a follow-up, the microphone open by itself, not.
    assertEquals(listOf(Conversation.maxMs, Conversation.followUpMaxMs, Conversation.followUpMaxMs), ears.caps)
    assertEquals(listOf(45_000L, 15_000L), listOf(Conversation.maxMs, Conversation.followUpMaxMs))
    // The closing tone once, after the last listening and before the glasses are let go.
    assertEquals(listOf("cue", "cue", "cue", "closing", "close"), mouth.log)
    assertTrue(mouth.closed)
    assertEquals(Conversation.Status.IDLE, c.status.value)
  }

  @Test
  fun whatWasNotSaidToJarvisIsNeitherKeptNorSaidAndEndsIt() = runTest {
    // A TV heard in the follow-up: Jarvis says it wasn't for it.
    val tv = "and after the break, the weekend weather"
    val ears = FakeEars("what's on today", tv, "this is never listened for")
    val mouth = FakeMouth()
    val brain = FakeBrain(notForMe = setOf(tv))
    val memory = FakeMemory()
    val c = conversation(ears, mouth, brain, memory = memory)
    c.start()
    advanceUntilIdle()

    assertEquals(listOf("what's on today", tv), brain.asked.map { it.first })
    // Only the question that was for Jarvis is kept: nothing of the TV's reaches Chat or Jarvis's conversation.
    assertEquals(listOf(listOf(Turn.user("what's on today"), Turn.jarvis("answer to what's on today"))), memory.kept.map { it.first })
    assertEquals(listOf("clip:what's on today"), mouth.played)
    assertTrue(mouth.said.isEmpty())
    // No more listening: the closing tone says to open Jarvis again.
    assertEquals(2, ears.waits.size)
    assertEquals(listOf("cue", "cue", "closing", "close"), mouth.log)
    assertTrue(c.note.value.isNotEmpty())
    assertFalse(c.note.value.contains("weather"))
  }

  @Test
  fun anAnswerWithoutWordsEndsItKeepingNothing() = runTest {
    // A tool ran, then Jarvis found it wasn't said to it: no words, and no notForMe.
    val silent =
        object : Brain by FakeBrain() {
          override suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit) =
              VoiceEvent.Answer("", true).also { onEvent(it) }
        }
    val ears = FakeEars("lights down for the film", "never listened for")
    val mouth = FakeMouth()
    val memory = FakeMemory()
    conversation(ears, mouth, silent, memory = memory).start()
    advanceUntilIdle()
    assertEquals(1, ears.waits.size)
    assertTrue(memory.kept.isEmpty())
    assertTrue(mouth.said.isEmpty() && mouth.played.isEmpty())
    assertEquals(listOf("cue", "closing", "close"), mouth.log)
  }

  @Test
  fun anOpeningQuestionNotForJarvisKeepsNothing() = runTest {
    val heard = "can you pass me the blue folder"
    val memory = FakeMemory()
    val mouth = FakeMouth()
    conversation(FakeEars(heard, "and tomorrow"), mouth, FakeBrain(notForMe = setOf(heard)), memory = memory).start()
    advanceUntilIdle()
    assertTrue(memory.kept.isEmpty())
    assertTrue(mouth.said.isEmpty() && mouth.played.isEmpty())
  }

  @Test
  fun onTheG2WhatWasNotForJarvisShowsItsLineButIsNotKept() = runTest {
    val heard = "see you on Tuesday then"
    val memory = FakeMemory()
    val lens = FakeMouth()
    val ears = FakeEars(heard, "never asked")
    conversation(ears, lens, FakeBrain(withAudio = false, notForMe = setOf(heard), shown = "That wasn't for me."), memory = memory).start()
    advanceUntilIdle()
    assertEquals(listOf("That wasn't for me."), lens.said)
    assertTrue(memory.kept.isEmpty())
    assertEquals(1, ears.waits.size)
  }

  @Test
  fun aReplyToAnAlertNotForJarvisLeavesTheAlertUnreplied() = runTest {
    val alert = Alert("a1", "Office", "Leave in ten minutes.", speak = true, urgent = false)
    val heard = "two sugars please"
    val memory = FakeMemory()
    conversation(FakeEars(heard), FakeMouth(), FakeBrain(notForMe = setOf(heard)), memory = memory).start(alert)
    advanceUntilIdle()
    assertTrue(memory.kept.isEmpty())
  }

  @Test
  fun theClosingToneIsPlayedHoweverTheListeningEnds() = runTest {
    // Nobody spoke.
    FakeMouth().also { conversation(FakeEars(), it, FakeBrain()).start(); advanceUntilIdle(); assertEquals(listOf("cue", "closing", "close"), it.log) }
    // A closing word.
    FakeMouth().also { conversation(FakeEars("dah"), it, FakeBrain()).start(); advanceUntilIdle(); assertEquals(listOf("cue", "closing", "close"), it.log) }
    // One answer, no follow-ups.
    FakeMouth().also { conversation(FakeEars("hello"), it, FakeBrain(), followUps = false).start(); advanceUntilIdle(); assertEquals(listOf("cue", "closing", "close"), it.log) }
    // Jarvis out of reach: said, then the tone.
    val failing =
        object : Brain by FakeBrain() {
          override suspend fun transcribe(wav: ByteArray): String = throw java.io.IOException("offline")
        }
    FakeMouth().also {
      conversation(FakeEars("hello"), it, failing).start()
      advanceUntilIdle()
      assertEquals(listOf("I couldn't reach Jarvis just now."), it.said)
      assertEquals(listOf("cue", "closing", "close"), it.log)
    }
  }

  @Test
  fun noClosingToneWhenTheMicrophoneNeverOpenedOrTheGlassesCameOff() = runTest {
    val notOpened = FakeMouth(glasses = false)
    conversation(FakeEars("hello"), notOpened, FakeBrain()).start()
    advanceUntilIdle()
    assertFalse("closing" in notOpened.log)

    val slowEars =
        Ears { _, _ ->
          kotlinx.coroutines.delay(5_000)
          "hello".toByteArray()
        }
    val takenOff = FakeMouth()
    val c = conversation(slowEars, takenOff, FakeBrain())
    c.start()
    testScheduler.advanceTimeBy(1_000)
    c.stop()
    advanceUntilIdle()
    assertEquals(listOf("cue", "close"), takenOff.log)
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
  fun onlyTheOpeningQuestionCarriesAPhoto() = runTest {
    var taken = 0
    val eyes = Eyes { taken++; byteArrayOf(7) }
    // Someone else's "what's this?" in the follow-up, or after an alert: no photo is even taken.
    val brain = FakeBrain()
    conversation(FakeEars("who won the match", "what's this?", "apa ni"), FakeMouth(), brain, eyes = eyes).start()
    advanceUntilIdle()
    assertEquals(3, brain.asked.size)
    assertTrue(brain.asked.all { it.third == null })
    val replied = FakeBrain()
    conversation(FakeEars("what's that?"), FakeMouth(), replied, eyes = eyes).start(Alert("a1", "Front door", "Someone is at the door.", speak = true, urgent = false))
    advanceUntilIdle()
    assertNull(replied.asked.single().third)
    assertEquals(0, taken)
    // After the launch phrase heard again, the question is still the opening one.
    val opened = FakeBrain()
    conversation(FakeEars("Hey Meta, open Jarvis", "what's this?"), FakeMouth(), opened, eyes = eyes).start()
    advanceUntilIdle()
    assertNotNull(opened.asked.single().third)
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

    // Said with its title, as the web app says it: a family message says who wrote it.
    assertEquals("tts:Office: Leave in ten minutes.", mouth.played.first())
    assertEquals(listOf(Turn.jarvis("Office: Leave in ten minutes.")), brain.asked.first().second)
    assertEquals(Conversation.replyWaitMs, ears.waits.first())
    // The alert is kept before the reply, once, and the next question has it from Chat's.
    assertEquals(listOf(alert, null), memory.kept.map { it.second })
    assertEquals(Turn.jarvis("Office: Leave in ten minutes."), brain.asked[1].second.first())
    assertEquals(1, brain.asked[1].second.count { it.text == "Office: Leave in ten minutes." })
  }

  @Test
  fun aBotsAlertIsSaidUnderItsNameAndAskedWithAsItsWords() = runTest {
    val mouth = FakeMouth()
    val brain = FakeBrain()
    val alert = Alert("a2", "Nova", "The trip form is due Friday.", speak = true, urgent = false, source = "api", skill = SkillFrom("kab12cd34", "Nova"))
    conversation(FakeEars("sign it for me"), mouth, brain).start(alert)
    advanceUntilIdle()
    assertEquals("tts:Nova: The trip form is due Friday.", mouth.played.first())
    // Never as Jarvis's own plain words.
    assertEquals(listOf(Turn.jarvis(alert.told)), brain.asked.single().second)
    assertTrue(alert.told.startsWith("Nova, a skill (an outside bot the user added), in its own words"))
  }

  @Test
  fun anAlertChatHasAlreadyIsNotAskedWithTwice() = runTest {
    val alert = Alert("a1", "Front door", "It is unlocked.", speak = true, urgent = false)
    // Put in Chat as it arrived (ChatLog.arrived), before the conversation it starts.
    val memory = FakeMemory(listOf(Turn.jarvis(alert.said)))
    val brain = FakeBrain()
    conversation(FakeEars("lock it"), FakeMouth(), brain, memory = memory).start(alert)
    advanceUntilIdle()
    assertEquals(listOf(Turn.jarvis("Front door: It is unlocked.")), brain.asked.single().second)
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
    conversation(FakeEars("How are you?", "Hi Meta, open Jarvis.", "what's the time"), FakeMouth(), brain).start()
    advanceUntilIdle()
    assertEquals(listOf("How are you?", "what's the time"), brain.asked.map { it.first })
  }

  @Test
  fun aLaunchPhraseOrAnOkayIsListenedPastOnlyOncePerAnswer() = runTest {
    // Each answer allows one: "okay" after each is fine.
    val brain = FakeBrain()
    conversation(FakeEars("How are you?", "Okay.", "and tomorrow?", "okay", "and Friday?"), FakeMouth(), brain).start()
    advanceUntilIdle()
    assertEquals(listOf("How are you?", "and tomorrow?", "and Friday?"), brain.asked.map { it.first })
    // Two in a row (a TV saying "okay", "alright") end it, with nothing asked.
    val ears = FakeEars("How are you?", "Hi Meta, open Jarvis.", "Okay.", "what's the time")
    val mouth = FakeMouth()
    val twice = FakeBrain()
    conversation(ears, mouth, twice).start()
    advanceUntilIdle()
    assertEquals(listOf("How are you?"), twice.asked.map { it.first })
    assertEquals(3, ears.waits.size)
    assertEquals("closing", mouth.log.dropLast(1).last())
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
