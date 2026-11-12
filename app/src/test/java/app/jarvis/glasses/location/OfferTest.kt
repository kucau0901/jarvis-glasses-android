package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Routine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class OfferTest {
  private fun onEvent(id: String, name: String, event: String) = Routine(id, name, trigger = buildJsonObject { put("kind", "event"); put("event", event) })

  private val daily = Routine("d1", "Morning", trigger = buildJsonObject { put("kind", "daily"); put("time", "07:30") })
  private var have = listOf<Routine>()
  private val added = mutableListOf<String>()
  private val removed = mutableListOf<String>()
  private val offer = Offer({ have }, { name, event, ask -> added += "$name|$event|$ask" }, { removed += it })

  @Test
  fun turnedOnWithNothingListeningItMakesTheRoutine() = runBlocking {
    have = listOf(daily, onEvent("e1", "At the grocer", "arrived:corner_grocer"), onEvent("e2", "On leaving", "left"))
    offer.ensure()
    assertEquals(listOf("${Offer.NAME}|arrived|${Offer.ASK}"), added)
  }

  @Test
  fun oneAlreadyListeningIsEnough() = runBlocking {
    have = listOf(onEvent("mine", "My own arrival", "arrived"))
    offer.ensure()
    have = listOf(onEvent("ours", Offer.NAME, "arrived"))
    offer.ensure()
    assertEquals(emptyList<String>(), added)
  }

  @Test
  fun turnedOffItRemovesOnlyTheOneItMade() = runBlocking {
    have = listOf(daily, onEvent("mine", "My own arrival", "arrived"), onEvent("ours", Offer.NAME, "arrived"), onEvent("other", Offer.NAME, "arrived:office"))
    offer.stop()
    assertEquals(listOf("ours"), removed)
  }

  @Test
  fun aRoutineWithNoTriggerShownListensForNothing() {
    assertEquals(false, Routine("x", Offer.NAME).listensFor("arrived"))
  }
}
