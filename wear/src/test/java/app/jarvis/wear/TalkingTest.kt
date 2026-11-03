package app.jarvis.wear

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TalkingTest {
  /** The rest of the watch, as a conversation sees it: what was asked, and how often it listened. */
  private class Watch {
    val answers = MutableStateFlow<Asking.State>(Asking.State.Idle)
    val speaking = MutableStateFlow(false)
    var followUps = true
    val asked = mutableListOf<Pair<String, Boolean>>()
    var listened = 0

    fun answered(text: String, ok: Boolean = true, notForMe: Boolean = false) {
      val q = asked.last().first
      answers.value = Asking.State.Answered(q, text, ok, notForMe, done = false)
      answers.value = Asking.State.Answered(q, text, ok, notForMe, done = true)
    }
  }

  /** Past the breath before listening again: the conversation runs in the background scope, which advanceUntilIdle leaves alone. */
  private fun TestScope.settle() {
    advanceTimeBy(1_000)
    runCurrent()
  }

  private fun TestScope.talking(w: Watch) =
      Talking(backgroundScope, w.answers, w.speaking, { w.followUps }, ask = { q, open -> w.asked += q to open; w.answers.value = Asking.State.Asked(q) }, listen = { w.listened++ })

  @Test
  fun afterAnAnswerHasBeenSaidItListensAgainForAFollowUp() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    assertEquals(1, w.listened)
    t.heard("what's on today")
    w.speaking.value = true
    w.answered("Two meetings.")
    settle()
    // Not while it is still being said.
    assertEquals(1, w.listened)
    w.speaking.value = false
    settle()
    assertEquals(2, w.listened)
    assertTrue(t.following.value)
    t.heard("and tomorrow")
    // The follow-up is asked as heard by an open microphone; the opening question wasn't.
    assertEquals(listOf("what's on today" to false, "and tomorrow" to true), w.asked)
  }

  @Test
  fun eachAnswerIsFollowedUpOnce() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    t.heard("hi")
    w.answered("Hello.")
    settle()
    // Speech starting and stopping again (the watch's own voice after Jarvis's) doesn't listen twice.
    w.speaking.value = true
    w.speaking.value = false
    settle()
    assertEquals(2, w.listened)
  }

  @Test
  fun aClosingEndsItWithoutAsking() = runTest(UnconfinedTestDispatcher()) {
    for (bye in listOf("Thanks!", "stop", "terima kasih", "that's all")) {
      val w = Watch()
      val t = talking(w)
      t.start()
      t.heard("lights off")
      w.answered("Done.")
      settle()
      t.heard(bye)
      assertEquals(listOf("lights off" to false), w.asked)
      assertFalse(t.following.value)
      w.answered("Done.")
      settle()
      assertEquals(2, w.listened)
    }
  }

  @Test
  fun anOpeningThanksIsStillAsked() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    t.heard("thanks")
    assertEquals(listOf("thanks" to false), w.asked)
  }

  @Test
  fun aBareOkayListensAgainOnceOnly() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    t.heard("hi")
    w.answered("Hello.")
    settle()
    t.heard("okay")
    assertEquals(3, w.listened)
    t.heard("okay")
    assertEquals(3, w.listened)
    assertFalse(t.following.value)
    assertEquals(1, w.asked.size)
  }

  @Test
  fun itEndsWhenJarvisFindsNothingWasSaidToItOrHasNoWords() = runTest(UnconfinedTestDispatcher()) {
    for ((text, notForMe) in listOf("" to true, "Not for me." to true, "" to false)) {
      val w = Watch()
      val t = talking(w)
      t.start()
      t.heard("hi")
      w.answered(text, notForMe = notForMe)
      settle()
      assertEquals(1, w.listened)
      assertFalse(t.following.value)
    }
  }

  @Test
  fun itEndsOnAFailureAndWithFollowUpsOff() = runTest(UnconfinedTestDispatcher()) {
    val failed = Watch()
    val t1 = talking(failed)
    t1.start()
    t1.heard("hi")
    failed.answered("Jarvis is offline.", ok = false)
    settle()
    assertEquals(1, failed.listened)

    val off = Watch().apply { followUps = false }
    val t2 = talking(off)
    t2.start()
    t2.heard("hi")
    off.answered("Hello.")
    settle()
    assertEquals(1, off.listened)
  }

  @Test
  fun silenceAfterAnAnswerEndsItQuietlyButNotBeforeTheFirstQuestion() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    // Nothing heard for the opening question: worth saying so.
    assertFalse(t.missed())
    t.start()
    t.heard("hi")
    w.answered("Hello.")
    settle()
    assertTrue(t.missed())
    assertFalse(t.following.value)
    // Ended: a later answer isn't followed up.
    w.asked += "again" to false
    w.answered("Again.")
    settle()
    assertEquals(3, w.listened)
  }

  @Test
  fun endedItNoLongerListens() = runTest(UnconfinedTestDispatcher()) {
    val w = Watch()
    val t = talking(w)
    t.start()
    t.heard("hi")
    t.end()
    w.answered("Hello.")
    settle()
    assertEquals(1, w.listened)
  }
}
