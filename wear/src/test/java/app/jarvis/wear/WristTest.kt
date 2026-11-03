package app.jarvis.wear

import app.jarvis.link.Act
import app.jarvis.link.Approval
import app.jarvis.link.Approve
import app.jarvis.link.Button
import app.jarvis.link.Fetch
import app.jarvis.link.FromWatch
import app.jarvis.link.Reply
import app.jarvis.link.Thing
import app.jarvis.link.WatchLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WristTest {
  private class FakePhone(var reachable: Boolean = true, val answer: (FromWatch) -> Reply?) : Phone {
    val sent = mutableListOf<FromWatch>()
    private val flow = MutableSharedFlow<Reply>(extraBufferCapacity = 64)
    override val replies: SharedFlow<Reply> = flow

    override suspend fun send(m: FromWatch): Boolean {
      if (!reachable) return false
      sent += m
      answer(m)?.let { flow.emit(it) }
      return true
    }
  }

  private class Kept : Wrist.Keep {
    override var things: String? = null
    override var approvals = 0
    override var jobs = 0
    override var pinned: List<String> = emptyList()
  }

  private val hall = Thing("home", "light.hall", "Hall", status = "Off", buttons = listOf(Button("toggle", "Turn on")))
  private val desk = Thing("home", "light.desk", "Desk")

  private fun wrist(phone: Phone, kept: Kept = Kept(), changed: () -> Unit = {}): Wrist {
    var n = 0
    return Wrist(phone, kept, changed, newId = { "r${++n}" }, timeoutMs = 1_000)
  }

  @Test
  fun homeIsFetchedKeptAndToldOf() = runTest(UnconfinedTestDispatcher()) {
    val kept = Kept()
    var told = 0
    val w = wrist(FakePhone { m -> Reply.Home(m.id, listOf(hall, desk)) }, kept) { told++ }
    assertNull(w.fetchHome())
    assertEquals(listOf(hall, desk), w.things.value)
    assertEquals(listOf(hall, desk), WatchLink.decodeThings(kept.things!!))
    assertEquals(1, told)
    // What was kept is there at once next time, before any fetch.
    assertEquals(listOf(hall, desk), wrist(FakePhone { null }, kept).things.value)
  }

  @Test
  fun whyItWasntFetchedIsSaid() = runTest(UnconfinedTestDispatcher()) {
    assertEquals("Sign in", wrist(FakePhone { m -> Reply.Failed(m.id, "Sign in") }).fetchHome())
    assertEquals("The phone isn't in reach, or hasn't the Jarvis app", wrist(FakePhone(reachable = false) { null }).fetchHome())
    assertEquals("The phone isn't in reach, or hasn't the Jarvis app", wrist(FakePhone { null }).fetchApprovals())
  }

  @Test
  fun aPressIsSentAndHomeFetchedAgain() = runTest(UnconfinedTestDispatcher()) {
    val on = hall.copy(status = "On", active = true)
    val phone = FakePhone { m -> if (m is Act) Reply.Acted(m.id, "Turned on") else Reply.Home(m.id, listOf(on)) }
    val w = wrist(phone)
    assertEquals(Wrist.Outcome(true, "Turned on"), w.act(hall, "toggle"))
    assertEquals(Act("r1", "home", "light.hall", "toggle"), phone.sent[0])
    assertEquals(Fetch("r2", Fetch.HOME), phone.sent[1])
    assertEquals(listOf(on), w.things.value)
    assertEquals(Wrist.Outcome(false, "Offline"), wrist(FakePhone { m -> Reply.Failed(m.id, "Offline") }).act(hall, "toggle"))
    // One of a room's things: pressed through the room.
    val room = FakePhone { m -> if (m is Act) Reply.Acted(m.id) else Reply.Home(m.id, emptyList()) }
    wrist(room).act(Thing("home", "room.master", "Master Bed"), "turn_off", member = "light.bed")
    assertEquals(Act("r1", "home", "room.master", "turn_off", member = "light.bed"), room.sent[0])
  }

  @Test
  fun anAnsweredApprovalLeavesTheListAndTheCount() = runTest(UnconfinedTestDispatcher()) {
    val a = Approval("p1", "Send it")
    val b = Approval("p2", "Book it")
    val kept = Kept()
    val phone = FakePhone { m -> if (m is Approve) Reply.Acted(m.id) else Reply.Approvals(m.id, listOf(a, b)) }
    val w = wrist(phone, kept)
    assertNull(w.fetchApprovals())
    assertEquals(2, w.counts.value.approvals)
    assertNull(w.approve(a, yes = true))
    assertEquals(Approve("r2", "p1", true), phone.sent[1])
    assertEquals(listOf(b), w.approvals.value)
    assertEquals(1, w.counts.value.approvals)
    assertEquals(1, kept.approvals)
  }

  @Test
  fun theCountsAreKeptForTheWatchFace() = runTest(UnconfinedTestDispatcher()) {
    val kept = Kept()
    var told = 0
    val w = wrist(FakePhone { m -> Reply.Glance(m.id, 3, 1) }, kept) { told++ }
    assertNull(w.fetchCounts())
    assertEquals(Wrist.Counts(3, 1), w.counts.value)
    assertEquals(3 to 1, kept.approvals to kept.jobs)
    // The same again changes nothing on the face.
    w.fetchCounts()
    assertEquals(1, told)
  }

  @Test
  fun theTileHoldsThreePinnedThingsTheOldestGoingFirst() = runTest(UnconfinedTestDispatcher()) {
    val kept = Kept()
    val things = (1..4).map { Thing("home", "t$it", "T$it") }
    val w = wrist(FakePhone { m -> Reply.Home(m.id, things) }, kept)
    w.fetchHome()
    for (t in things) w.pin(t, true)
    assertEquals(listOf("home/t2/T2", "home/t3/T3", "home/t4/T4"), kept.pinned)
    w.pin(things[2], false)
    assertEquals(listOf(things[1], things[3]), w.pinnedThings())
    // Pinned again, it goes last.
    w.pin(things[1], true)
    assertEquals(listOf("home/t4/T4", "home/t2/T2"), w.pinned.value)
    assertTrue(Wrist.MAX_PINNED == 3)
  }
}
