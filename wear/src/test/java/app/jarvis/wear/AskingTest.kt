package app.jarvis.wear

import app.jarvis.link.Ask
import app.jarvis.link.FromWatch
import app.jarvis.link.Hello
import app.jarvis.link.Reply
import app.jarvis.link.Stop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AskingTest {
  /** A phone that answers each message with [answer]'s replies, as they would come back. */
  private class FakePhone(var reachable: Boolean = true, val answer: (FromWatch) -> List<Reply> = { emptyList() }) : Phone {
    val sent = mutableListOf<FromWatch>()
    private val flow = MutableSharedFlow<Reply>(extraBufferCapacity = 64)
    override val replies: SharedFlow<Reply> = flow

    override suspend fun send(m: FromWatch): Boolean {
      if (!reachable) return false
      sent += m
      for (r in answer(m)) flow.emit(r)
      return true
    }

    suspend fun reply(r: Reply) = flow.emit(r)
  }

  private class Mouth {
    val played = mutableListOf<Pair<Int, String>>()
    val said = mutableListOf<String>()
    var hushed = 0
  }

  private fun TestScope.asking(phone: Phone, mouth: Mouth = Mouth(), speak: Boolean = true): Asking {
    var n = 0
    return Asking(
        backgroundScope,
        phone,
        origin = { "w_1" },
        speak = { speak },
        play = { seq, mp3 -> mouth.played += seq to mp3.decodeToString() },
        say = { mouth.said += it },
        hush = { mouth.hushed++ },
        newId = { "q${++n}" },
    )
  }

  @Test
  fun theAnswerShowsAndItsVoiceIsPlayed() = runTest(UnconfinedTestDispatcher()) {
    val phone =
        FakePhone(answer = { m ->
          listOf(
              Reply.Progress(m.id, "Looking"),
              Reply.Answer(m.id, "Two meetings."),
              Reply.Audio(m.id, 0, true, "audio/mpeg", "aGk="),
              Reply.Done(m.id),
          )
        })
    val mouth = Mouth()
    val a = asking(phone, mouth)
    a.ask("what's on today")
    advanceUntilIdle()
    assertEquals(listOf(Ask("q1", "what's on today", "w_1", speak = true, chars = Asking.CHARS)), phone.sent)
    assertEquals(Asking.State.Answered("what's on today", "Two meetings.", ok = true, notForMe = false, done = true), a.state.value)
    assertEquals(listOf(0 to "hi"), mouth.played)
    assertTrue(mouth.said.isEmpty())
  }

  @Test
  fun progressShowsWhileItIsOnItsWay() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> listOf(Reply.Progress(m.id, "Looking at the calendar")) })
    val a = asking(phone)
    a.ask("what's on")
    assertEquals(Asking.State.Asked("what's on", "Looking at the calendar"), a.state.value)
  }

  @Test
  fun anAnswerWithoutVoiceIsSaidByTheWatchWhenItSpeaks() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> listOf(Reply.Answer(m.id, "Done."), Reply.Done(m.id)) })
    val aloud = Mouth()
    asking(phone, aloud, speak = true).ask("lights off")
    val quiet = Mouth()
    asking(phone, quiet, speak = false).ask("lights off")
    advanceUntilIdle()
    assertEquals(listOf("Done."), aloud.said)
    assertTrue(quiet.said.isEmpty())
    assertEquals(false, (phone.sent.last() as Ask).speak)
  }

  @Test
  fun aFailureIsShown() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> listOf(Reply.Failed(m.id, "Sign in to Jarvis in the phone app")) })
    val a = asking(phone)
    a.ask("x")
    assertEquals(Asking.State.Failed("x", "Sign in to Jarvis in the phone app"), a.state.value)
  }

  @Test
  fun noPhoneInReachFailsAtOnce() = runTest(UnconfinedTestDispatcher()) {
    val a = asking(FakePhone(reachable = false))
    a.ask("x")
    assertTrue(a.state.value is Asking.State.Failed)
  }

  @Test
  fun aPhoneThatNeverAnswersIsGivenUpOnAndHungUp() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone()
    val a = asking(phone)
    a.ask("x")
    advanceTimeBy(16_000)
    assertTrue(a.state.value is Asking.State.Failed)
    assertEquals(Stop("q1"), phone.sent.last())
  }

  @Test
  fun oneThatAnsweredMayTakeAWhile() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> if (m is Ask) listOf(Reply.Progress(m.id, "Working")) else emptyList() })
    val a = asking(phone)
    a.ask("x")
    advanceTimeBy(60_000)
    assertEquals(Asking.State.Asked("x", "Working"), a.state.value)
    phone.reply(Reply.Answer("q1", "There."))
    phone.reply(Reply.Done("q1"))
    assertEquals(Asking.State.Answered("x", "There.", ok = true, notForMe = false, done = true), a.state.value)
  }

  @Test
  fun thePhonesWordThatItHasTheQuestionKeepsItWaitingWithoutShowing() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> if (m is Ask) listOf(Reply.Progress(m.id, "")) else emptyList() })
    val a = asking(phone)
    a.ask("x")
    advanceTimeBy(60_000)
    assertEquals(Asking.State.Asked("x", null), a.state.value)
    phone.reply(Reply.Progress("q1", "Working"))
    phone.reply(Reply.Progress("q1", ""))
    assertEquals(Asking.State.Asked("x", "Working"), a.state.value)
  }

  @Test
  fun askingAgainHangsUpTheLastAndIgnoresItsReplies() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> if (m is Ask) listOf(Reply.Progress(m.id, "Working")) else emptyList() })
    val mouth = Mouth()
    val a = asking(phone, mouth)
    a.ask("first")
    a.ask("second")
    phone.reply(Reply.Answer("q1", "Late."))
    advanceUntilIdle()
    assertTrue(Stop("q1") in phone.sent)
    assertEquals(Asking.State.Asked("second", "Working"), a.state.value)
    assertTrue(mouth.hushed >= 2)
  }

  @Test
  fun stopForgetsTheQuestion() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> if (m is Ask) listOf(Reply.Progress(m.id, "Working")) else emptyList() })
    val a = asking(phone)
    a.ask("x")
    a.stop()
    advanceUntilIdle()
    assertEquals(Asking.State.Idle, a.state.value)
    assertEquals(Stop("q1"), phone.sent.last())
  }

  @Test
  fun helloSaysWhoThePhoneIsSignedInAs() = runTest(UnconfinedTestDispatcher()) {
    val phone = FakePhone(answer = { m -> if (m is Hello) listOf(Reply.Ready(m.id, true, "Yusri")) else emptyList() })
    assertEquals(Reply.Ready("q1", true, "Yusri"), asking(phone).hello())
    assertNull(asking(FakePhone(reachable = false)).hello())
  }

  @Test
  fun helloGivesUpOnASilentPhone() = runTest(UnconfinedTestDispatcher()) {
    assertNull(asking(FakePhone()).hello())
  }
}
