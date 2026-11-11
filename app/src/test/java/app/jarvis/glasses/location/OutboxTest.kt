package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Place
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OutboxTest {
  private val grocer = Place("corner_grocer", "Corner Grocer", 10.5, 20.25, 150.0, 1)
  private val office = Place("office", "Office", -10.125, -20.5, 300.0, 2)
  private val minute = 60_000L

  private var kept = ""
  private var now = 1_000_000L
  private var on = true
  private var reachable = true
  private val sent = mutableListOf<String>()
  private val outbox = Outbox({ kept }, { kept = it }, { now }, { on }) { c -> reachable.also { if (it) sent += c.event } }

  @Test
  fun aCrossedPlaceIsItsEventWithItsName() {
    assertEquals(listOf(Crossing("arrived:corner_grocer", "Corner Grocer", 5)), crossings(true, listOf("corner_grocer"), listOf(grocer, office), offers = false, now = 5))
    assertEquals(listOf(Crossing("left:office", "Office", 6)), crossings(false, listOf("office"), listOf(grocer, office), offers = false, now = 6))
  }

  @Test
  fun theBareEventGoesTooOnlyWhenOffersAreOn() {
    assertEquals(
        listOf(Crossing("arrived:corner_grocer", "Corner Grocer", 5), Crossing("arrived", "Corner Grocer", 5)),
        crossings(true, listOf("corner_grocer"), listOf(grocer), offers = true, now = 5),
    )
    assertEquals(listOf(Crossing("left:office", "Office", 5), Crossing("left", "Office", 5)), crossings(false, listOf("office"), listOf(office), offers = true, now = 5))
  }

  @Test
  fun aFenceWhosePlaceIsGoneSaysNothing() {
    assertEquals(emptyList<Crossing>(), crossings(true, listOf("old_flat"), listOf(grocer), offers = true, now = 5))
    // Two fences at once, one of them told twice: each place once.
    assertEquals(listOf("arrived:corner_grocer", "arrived:office"), crossings(true, listOf("corner_grocer", "office", "corner_grocer"), listOf(grocer, office), false, 5).map { it.event })
  }

  @Test
  fun theRuleKeepsWhatIsUnderTenMinutesOld() {
    val old = Crossing("arrived:office", "Office", now - 10 * minute)
    val fresh = Crossing("left:office", "Office", now - 10 * minute + 1)
    assertEquals(listOf(fresh), Outbox.due(listOf(old, fresh), now))
    // The newest, when more waited than is worth sending.
    val many = (1..30).map { Crossing("arrived:p$it", "P$it", now) }
    assertEquals(many.takeLast(Outbox.MOST), Outbox.due(many, now))
  }

  @Test
  fun whatJarvisTookIsNotKept() = runBlocking {
    outbox.send(crossings(true, listOf("corner_grocer"), listOf(grocer), offers = true, now = now))
    assertEquals(listOf("arrived:corner_grocer", "arrived"), sent)
    assertEquals("", kept)
  }

  @Test
  fun whatDidNotGetThereIsSentAtTheNextChance() = runBlocking {
    reachable = false
    outbox.send(listOf(Crossing("arrived:corner_grocer", "Corner Grocer", now)))
    assertEquals(emptyList<String>(), sent)
    assertEquals(listOf(Crossing("arrived:corner_grocer", "Corner Grocer", 1_000_000L)), Outbox.decode(kept))

    // The next chance: another crossing three minutes on, sent after the one that waited.
    reachable = true
    now += 3 * minute
    outbox.send(listOf(Crossing("left:corner_grocer", "Corner Grocer", now)))
    assertEquals(listOf("arrived:corner_grocer", "left:corner_grocer"), sent)
    assertEquals("", kept)
  }

  @Test
  fun aChanceWithNothingNewSendsWhatWaited() = runBlocking {
    reachable = false
    outbox.send(listOf(Crossing("left:office", "Office", now)))
    reachable = true
    now += minute
    outbox.send()
    assertEquals(listOf("left:office"), sent)
    assertEquals("", kept)
    // And nothing twice.
    outbox.send()
    assertEquals(listOf("left:office"), sent)
  }

  @Test
  fun afterTenMinutesItIsDroppedUnsent() = runBlocking {
    reachable = false
    outbox.send(listOf(Crossing("arrived:office", "Office", now)))
    reachable = true
    now += 10 * minute
    outbox.send()
    assertEquals(emptyList<String>(), sent)
    assertEquals("", kept)
  }

  @Test
  fun onlyTheOneThatFailedWaits() = runBlocking {
    val outbox = Outbox({ kept }, { kept = it }, { now }, { on }) { c -> (c.event != "arrived").also { if (it) sent += c.event } }
    outbox.send(listOf(Crossing("arrived:office", "Office", now), Crossing("arrived", "Office", now)))
    assertEquals(listOf("arrived:office"), sent)
    assertEquals(listOf("arrived"), Outbox.decode(kept).map { it.event })
  }

  @Test
  fun switchedOffNothingIsSentOrKept() = runBlocking {
    reachable = false
    outbox.send(listOf(Crossing("arrived:office", "Office", now)))
    on = false
    reachable = true
    outbox.send(listOf(Crossing("left:office", "Office", now)))
    assertEquals(emptyList<String>(), sent)
    assertEquals("", kept)
  }

  @Test
  fun clearingForgetsWhatWaited() = runBlocking {
    reachable = false
    outbox.send(listOf(Crossing("arrived:office", "Office", now)))
    outbox.clear()
    reachable = true
    outbox.send()
    assertEquals(emptyList<String>(), sent)
  }
}
