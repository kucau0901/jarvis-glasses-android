package app.jarvis.glasses.location

import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer

/**
 * The person's saved places, as Jarvis has them (`GET /api/v1/places`), kept on the phone too
 * ([read], [write]: the app's prefs) so their geofences can be put back after a restart with no
 * network, and a crossed one named. Jarvis's list is the one that counts: [sync] reads it again,
 * keeps it, and tells [changed] which fences to add and remove ([fenceChange]).
 */
class Places(
    private val fetch: suspend () -> List<Place>,
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val changed: (FenceChange) -> Unit,
) {
  private val lock = Mutex()
  private val _list = MutableStateFlow(decode(read()))

  /** As last read from Jarvis, or as kept from the last time. */
  val list: StateFlow<List<Place>> = _list

  suspend fun sync(): List<Place> =
      lock.withLock {
        val now = fetch()
        val was = _list.value
        write(encode(now))
        _list.value = now
        changed(fenceChange(was, now))
        now
      }

  /** Signed out, or in as someone else: these were another person's. Their fences are the caller's to remove. */
  fun forget() {
    write("")
    _list.value = emptyList()
  }

  companion object {
    private val LIST = ListSerializer(Place.serializer())

    fun encode(places: List<Place>): String = Hub.JSON_IN.encodeToString(LIST, places)

    /** Nothing kept, or something unreadable, is no places. */
    fun decode(text: String): List<Place> = if (text.isBlank()) emptyList() else runCatching { Hub.JSON_IN.decodeFromString(LIST, text) }.getOrDefault(emptyList())
  }
}

/** Which geofences to register ([add]) and which to take away ([remove], by slug) after the list changed. */
data class FenceChange(val add: List<Place>, val remove: List<String>)

/**
 * From [old] to [new]: a place no longer there is removed; a new one is added, and so is one that
 * moved or changed its radius, which replaces its fence (Android keeps one per id, the slug). A
 * place only renamed keeps its fence: the name is read from the list when it is crossed.
 */
fun fenceChange(old: List<Place>, new: List<Place>): FenceChange {
  val before = old.associateBy { it.slug }
  val after = new.associateBy { it.slug }
  return FenceChange(
      add = after.values.filter { p -> before[p.slug]?.let { it.lat != p.lat || it.lng != p.lng || it.radius != p.radius } ?: true },
      remove = before.keys.filter { it !in after },
  )
}
