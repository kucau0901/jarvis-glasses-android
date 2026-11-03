package app.jarvis.glasses.watch

import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import app.jarvis.glasses.hub.Hub
import app.jarvis.link.Act
import app.jarvis.link.Approval
import app.jarvis.link.Approve
import app.jarvis.link.Ask
import app.jarvis.link.Fetch
import app.jarvis.link.Thing
import app.jarvis.link.Hello
import app.jarvis.link.Reply
import app.jarvis.link.Stop
import app.jarvis.link.WatchLink
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WatchRelayTest {
  private class Watch {
    val got = mutableListOf<Pair<String, Reply>>()
    val replies get() = got.map { it.second }
  }

  private fun TestScope.relay(
      watch: Watch,
      signedIn: String? = "Yusri",
      asked: MutableList<Ask> = mutableListOf(),
      kept: MutableList<List<Turn>> = mutableListOf(),
      answer: suspend (Ask, suspend (VoiceEvent) -> Unit) -> VoiceEvent.Answer = { _, on ->
        on(VoiceEvent.Progress("Looking"))
        VoiceEvent.Answer("Two meetings.", ok = true).also { on(it) }
      },
      busy: MutableList<Boolean> = mutableListOf(),
      wrist: Wrist? = null,
  ) =
      WatchRelay(
          backgroundScope,
          signedInAs = { signedIn },
          ask = { q, on -> asked += q; answer(q, on) },
          keep = { kept += it },
          send = { node, r -> watch.got += node to r },
          busy = { busy += it },
          wrist = wrist,
      )

  /** What the watch got, less the phone's word that it has a question. */
  private val Watch.answers get() = replies.filterNot { it is Reply.Progress && it.text.isEmpty() }

  private fun ask(q: Ask) = WatchLink.encode(q)

  @Test
  fun helloSaysWhetherThePhoneIsSignedIn() = runTest(UnconfinedTestDispatcher()) {
    val watch = Watch()
    relay(watch).onMessage("n1", WatchLink.HELLO, WatchLink.encode(Hello("h1")))
    relay(watch, signedIn = null).onMessage("n1", WatchLink.HELLO, WatchLink.encode(Hello("h2")))
    assertEquals(listOf("n1" to Reply.Ready("h1", true, "Yusri"), "n1" to Reply.Ready("h2", false, "")), watch.got)
  }

  @Test
  fun aQuestionStreamsBackToTheWatchThatAskedAndIsKept() = runTest(UnconfinedTestDispatcher()) {
    val watch = Watch()
    val kept = mutableListOf<List<Turn>>()
    relay(watch, kept = kept).onMessage("n1", WatchLink.ASK, ask(Ask("q1", "what's on today", "w_1")))
    advanceUntilIdle()
    assertEquals(
        listOf(Reply.Progress("q1", ""), Reply.Progress("q1", "Looking"), Reply.Answer("q1", "Two meetings."), Reply.Done("q1")),
        watch.replies,
    )
    assertTrue(watch.got.all { it.first == "n1" })
    assertEquals(listOf(listOf(Turn.user("what's on today"), Turn.jarvis("Two meetings."))), kept)
  }

  @Test
  fun itsVoiceIsSentOnlyWhenTheWatchSpeaks() = runTest(UnconfinedTestDispatcher()) {
    val voiced: suspend (Ask, suspend (VoiceEvent) -> Unit) -> VoiceEvent.Answer = { _, on ->
      on(VoiceEvent.Answer("Hi.", true))
      on(VoiceEvent.Audio(0, true, "audio/mpeg", "SUQz"))
      VoiceEvent.Answer("Hi.", true)
    }
    val aloud = Watch()
    relay(aloud, answer = voiced).onMessage("n1", WatchLink.ASK, ask(Ask("q1", "hi", "w_1", speak = true)))
    val quiet = Watch()
    relay(quiet, answer = voiced).onMessage("n1", WatchLink.ASK, ask(Ask("q2", "hi", "w_1", speak = false)))
    advanceUntilIdle()
    assertEquals(listOf(Reply.Answer("q1", "Hi."), Reply.Audio("q1", 0, true, "audio/mpeg", "SUQz"), Reply.Done("q1")), aloud.answers)
    assertEquals(listOf(Reply.Answer("q2", "Hi."), Reply.Done("q2")), quiet.answers)
  }

  @Test
  fun whatWentWrongOrWasntForJarvisIsShownButNotKept() = runTest(UnconfinedTestDispatcher()) {
    val kept = mutableListOf<List<Turn>>()
    val watch = Watch()
    relay(watch, kept = kept, answer = { _, _ -> VoiceEvent.Answer("No calendar.", ok = false) }).onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    relay(watch, kept = kept, answer = { _, _ -> VoiceEvent.Answer("", ok = true, notForMe = true) }).onMessage("n1", WatchLink.ASK, ask(Ask("q2", "x", "w_1")))
    advanceUntilIdle()
    assertTrue(kept.isEmpty())
    assertEquals(listOf(Reply.Done("q1"), Reply.Done("q2")), watch.answers)
  }

  @Test
  fun signedOutOrUnreachableTheWatchIsToldWhy() = runTest(UnconfinedTestDispatcher()) {
    val asked = mutableListOf<Ask>()
    val watch = Watch()
    relay(watch, signedIn = null, asked = asked).onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    relay(watch, answer = { _, _ -> throw IOException("Jarvis is out of reach") }).onMessage("n1", WatchLink.ASK, ask(Ask("q2", "x", "w_1")))
    advanceUntilIdle()
    assertTrue(asked.isEmpty())
    assertEquals(listOf(Reply.Failed("q1", "Sign in to Jarvis in the phone app"), Reply.Failed("q2", "Jarvis is out of reach")), watch.answers)
  }

  @Test
  fun stopHangsUpOnJarvisAndNothingMoreIsSent() = runTest(UnconfinedTestDispatcher()) {
    val never = CompletableDeferred<VoiceEvent.Answer>()
    val kept = mutableListOf<List<Turn>>()
    val watch = Watch()
    val r = relay(watch, kept = kept, answer = { _, on -> on(VoiceEvent.Progress("Looking")); never.await() })
    r.onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    r.onMessage("n1", WatchLink.STOP, WatchLink.encode(Stop("q1")))
    never.complete(VoiceEvent.Answer("Late.", true))
    advanceUntilIdle()
    assertEquals(listOf(Reply.Progress("q1", "Looking")), watch.answers)
    assertTrue(kept.isEmpty())
  }

  @Test
  fun theSameQuestionAskedAgainReplacesTheFirst() = runTest(UnconfinedTestDispatcher()) {
    val first = CompletableDeferred<VoiceEvent.Answer>()
    var n = 0
    val watch = Watch()
    val r = relay(watch, answer = { _, _ -> if (n++ == 0) first.await() else VoiceEvent.Answer("Second.", true) })
    r.onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    r.onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    first.complete(VoiceEvent.Answer("First.", true))
    advanceUntilIdle()
    assertEquals(listOf(Reply.Done("q1")), watch.answers)
  }

  @Test
  fun thePhoneIsKeptUpWhileAnyQuestionIsOnItsWay() = runTest(UnconfinedTestDispatcher()) {
    val first = CompletableDeferred<VoiceEvent.Answer>()
    val second = CompletableDeferred<VoiceEvent.Answer>()
    val busy = mutableListOf<Boolean>()
    val r = relay(Watch(), busy = busy, answer = { q, _ -> if (q.id == "q1") first.await() else second.await() })
    r.onMessage("n1", WatchLink.ASK, ask(Ask("q1", "x", "w_1")))
    r.onMessage("n1", WatchLink.ASK, ask(Ask("q2", "y", "w_1")))
    first.complete(VoiceEvent.Answer("One.", true))
    assertEquals(listOf(true), busy)
    second.complete(VoiceEvent.Answer("Two.", true))
    advanceUntilIdle()
    assertEquals(listOf(true, false), busy)
  }

  private class FakeWrist(val fail: Exception? = null) : Wrist {
    val acted = mutableListOf<String>()

    private fun check() {
      fail?.let { throw it }
    }

    override suspend fun home() = check().let { listOf(Thing("home", "light.hall", "Hall")) }

    override suspend fun approvals() = check().let { listOf(Approval("p1", "Send it")) }

    override suspend fun glance() = check().let { 2 to 1 }

    override suspend fun act(panel: String, key: String, action: String, value: String?, member: String?) = check().let { acted += "$panel/$key${member?.let { "/$it" }.orEmpty()} $action"; "Done" }

    override suspend fun approve(proposal: String, yes: Boolean) = check().let { acted += "$proposal $yes"; Unit }
  }

  @Test
  fun theWristsFetchesAreAnsweredOnceEach() = runTest(UnconfinedTestDispatcher()) {
    val watch = Watch()
    val r = relay(watch, wrist = FakeWrist())
    r.onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f1", Fetch.HOME)))
    r.onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f2", Fetch.APPROVALS)))
    r.onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f3", Fetch.GLANCE)))
    r.onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f4", "later")))
    advanceUntilIdle()
    assertEquals(
        listOf(
            Reply.Home("f1", listOf(Thing("home", "light.hall", "Hall"))),
            Reply.Approvals("f2", listOf(Approval("p1", "Send it"))),
            Reply.Glance("f3", 2, 1),
            Reply.Failed("f4", "The phone app can't show that yet: update it"),
        ),
        watch.replies,
    )
  }

  @Test
  fun aPressAndAnApprovalAreDoneAndSaidSo() = runTest(UnconfinedTestDispatcher()) {
    val watch = Watch()
    val w = FakeWrist()
    val r = relay(watch, wrist = w)
    r.onMessage("n1", WatchLink.ACT, WatchLink.encode(Act("a1", "home", "light.hall", "level:40")))
    r.onMessage("n1", WatchLink.APPROVE, WatchLink.encode(Approve("a2", "p1", yes = false)))
    r.onMessage("n1", WatchLink.ACT, WatchLink.encode(Act("a3", "home", "room.master", "turn_off", member = "light.bed")))
    advanceUntilIdle()
    assertEquals(listOf("home/light.hall level:40", "p1 false", "home/room.master/light.bed turn_off"), w.acted)
    assertEquals(listOf(Reply.Acted("a1", "Done"), Reply.Acted("a2"), Reply.Acted("a3", "Done")), watch.replies)
  }

  @Test
  fun aDeviceTokenIsToldToSignInAsAPerson() = runTest(UnconfinedTestDispatcher()) {
    val watch = Watch()
    relay(watch, wrist = FakeWrist(Hub.NotAPerson())).onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f1", Fetch.HOME)))
    relay(watch, signedIn = null, wrist = FakeWrist()).onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f2", Fetch.HOME)))
    relay(watch).onMessage("n1", WatchLink.FETCH, WatchLink.encode(Fetch("f3", Fetch.HOME)))
    advanceUntilIdle()
    assertEquals(
        listOf(
            Reply.Failed("f1", "Sign in to Jarvis with a code in the phone app to see this"),
            Reply.Failed("f2", "Sign in to Jarvis in the phone app"),
            Reply.Failed("f3", "The phone app can't show that yet: update it"),
        ),
        watch.replies,
    )
  }

  @Test
  fun whatItCantReadIsLeftAlone() = runTest(UnconfinedTestDispatcher()) {
    val asked = mutableListOf<Ask>()
    val watch = Watch()
    val r = relay(watch, asked = asked)
    r.onMessage("n1", "/jarvis/later", "{}".toByteArray())
    r.onMessage("n1", WatchLink.ASK, "garbled".toByteArray())
    advanceUntilIdle()
    assertTrue(asked.isEmpty())
    assertTrue(watch.got.isEmpty())
  }
}
