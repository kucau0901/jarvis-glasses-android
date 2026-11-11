package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Place
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
  private val grocer = Place("corner_grocer", "Corner Grocer", 10.5, 20.25, 150.0, 1)
  private val office = Place("office", "Office", -10.125, -20.5, 300.0, 2)
  private val gym = Place("gym", "Gym", 30.0, 40.0, 100.0, 3)

  @Test
  fun aNewPlaceIsAddedAndAGoneOneRemoved() {
    assertEquals(FenceChange(add = listOf(gym), remove = listOf("corner_grocer")), fenceChange(listOf(grocer, office), listOf(office, gym)))
  }

  @Test
  fun theSameListChangesNothing() {
    assertEquals(FenceChange(emptyList(), emptyList()), fenceChange(listOf(grocer, office), listOf(office, grocer)))
    assertEquals(FenceChange(emptyList(), emptyList()), fenceChange(emptyList(), emptyList()))
  }

  @Test
  fun aPlaceThatMovedOrChangedItsRadiusIsReplaced() {
    val moved = grocer.copy(lat = 10.6, at = 9)
    val wider = office.copy(radius = 500.0)
    // Added again under the same slug, which replaces its fence: not removed first.
    assertEquals(FenceChange(add = listOf(moved, wider), remove = emptyList()), fenceChange(listOf(grocer, office), listOf(moved, wider)))
  }

  @Test
  fun aPlaceOnlyRenamedOrSavedAgainKeepsItsFence() {
    assertEquals(FenceChange(emptyList(), emptyList()), fenceChange(listOf(grocer), listOf(grocer.copy(name = "The grocer", at = 99))))
  }

  @Test
  fun fromNothingEveryPlaceIsAddedAndToNothingEveryOneRemoved() {
    assertEquals(FenceChange(listOf(grocer, office), emptyList()), fenceChange(emptyList(), listOf(grocer, office)))
    assertEquals(FenceChange(emptyList(), listOf("corner_grocer", "office")), fenceChange(listOf(grocer, office), emptyList()))
  }

  @Test
  fun syncKeepsJarvissListAndTellsWhatChanged() = runBlocking {
    var kept = ""
    var now = listOf(grocer, office)
    val told = mutableListOf<FenceChange>()
    val places = Places({ now }, { kept }, { kept = it }, told::add)
    assertEquals(emptyList<Place>(), places.list.value)

    assertEquals(listOf(grocer, office), places.sync())
    assertEquals(listOf(grocer, office), places.list.value)
    assertEquals(listOf(FenceChange(listOf(grocer, office), emptyList())), told)

    // The app started again with no network: the list is as it was kept.
    val again = Places({ throw IOException("offline") }, { kept }, { kept = it }, told::add)
    assertEquals(listOf(grocer, office), again.list.value)
    assertTrue(runCatching { again.sync() }.isFailure)
    assertEquals(listOf(grocer, office), again.list.value)
    assertEquals(1, told.size)

    now = listOf(office, gym)
    places.sync()
    assertEquals(FenceChange(listOf(gym), listOf("corner_grocer")), told.last())
    assertEquals(listOf(office, gym), Places.decode(kept))
  }

  @Test
  fun forgettingLeavesNoPlaces() = runBlocking {
    var kept = Places.encode(listOf(grocer))
    val places = Places({ emptyList() }, { kept }, { kept = it }, {})
    assertEquals(listOf(grocer), places.list.value)
    places.forget()
    assertEquals("", kept)
    assertEquals(emptyList<Place>(), places.list.value)
  }

  @Test
  fun whatCannotBeReadIsNoPlaces() {
    assertEquals(emptyList<Place>(), Places.decode(""))
    assertEquals(emptyList<Place>(), Places.decode("not json"))
  }
}
