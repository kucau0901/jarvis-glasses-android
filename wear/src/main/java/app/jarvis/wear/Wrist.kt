package app.jarvis.wear

import app.jarvis.link.Act
import app.jarvis.link.Approval
import app.jarvis.link.Approve
import app.jarvis.link.Fetch
import app.jarvis.link.Reply
import app.jarvis.link.Thing
import app.jarvis.link.WatchLink
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the wrist shows besides questions, fetched through the phone: Home's [things], the
 * [approvals] waiting, and the [counts] for the watch face. Home's things and the counts are kept
 * on the watch ([Keep]), so the Tile and complications have something to show between fetches,
 * or with the phone away; so are the things pinned to the Tile.
 *
 * Each call says how it went: null when done, else why not, to show. [changed] is told when what
 * the Tile and complications show may have changed.
 */
class Wrist(
    private val phone: Phone,
    private val keep: Keep,
    private val changed: () -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMs: Long = 12_000,
) {
  /** Where the wrist's things are kept between runs: [WatchPrefs] on the watch. */
  interface Keep {
    var things: String?
    var approvals: Int
    var jobs: Int
    var pinned: List<String>
  }

  data class Counts(val approvals: Int, val jobs: Int)

  private val _things = MutableStateFlow(keep.things?.let(WatchLink::decodeThings).orEmpty())
  val things: StateFlow<List<Thing>> = _things

  private val _approvals = MutableStateFlow<List<Approval>>(emptyList())
  val approvals: StateFlow<List<Approval>> = _approvals

  private val _counts = MutableStateFlow(Counts(keep.approvals, keep.jobs))
  val counts: StateFlow<Counts> = _counts

  private val _pinned = MutableStateFlow(keep.pinned)
  /** The things on the Tile, by [Thing.id], in the order they were pinned. */
  val pinned: StateFlow<List<String>> = _pinned

  /** The pinned things as last fetched, in pinned order. */
  fun pinnedThings(): List<Thing> = _pinned.value.mapNotNull { id -> _things.value.firstOrNull { it.id == id } }

  // Each listens for its own reply: a screen that stops meanwhile (the watch dimming) mustn't lose it.
  private suspend fun request(m: app.jarvis.link.FromWatch): Reply? = phone.using { phone.request(m, timeoutMs) }

  suspend fun fetchHome(): String? =
      when (val r = request(Fetch(newId(), Fetch.HOME))) {
        is Reply.Home -> {
          _things.value = r.things
          keep.things = WatchLink.encodeThings(r.things)
          changed()
          null
        }
        else -> why(r)
      }

  suspend fun fetchApprovals(): String? =
      when (val r = request(Fetch(newId(), Fetch.APPROVALS))) {
        is Reply.Approvals -> {
          _approvals.value = r.open
          setCounts(_counts.value.copy(approvals = r.open.size))
          null
        }
        else -> why(r)
      }

  suspend fun fetchCounts(): String? =
      when (val r = request(Fetch(newId(), Fetch.GLANCE))) {
        is Reply.Glance -> {
          setCounts(Counts(r.approvals, r.jobs))
          null
        }
        else -> why(r)
      }

  /**
   * [action] done on [thing]: one of its buttons, or its dial's ([app.jarvis.link.Dial.actionFor]);
   * with [member], one of its room's things' buttons. Home is fetched again after, as its state has
   * changed. Returns what Jarvis said, or why not.
   */
  suspend fun act(thing: Thing, action: String, member: String? = null): Outcome =
      when (val r = request(Act(newId(), thing.panel, thing.key, action, member = member))) {
        is Reply.Acted -> {
          fetchHome()
          Outcome(true, r.text)
        }
        else -> Outcome(false, why(r))
      }

  suspend fun approve(a: Approval, yes: Boolean): String? =
      when (val r = request(Approve(newId(), a.id, yes))) {
        is Reply.Acted -> {
          _approvals.value = _approvals.value.filterNot { it.id == a.id }
          setCounts(_counts.value.copy(approvals = (_counts.value.approvals - 1).coerceAtLeast(0)))
          null
        }
        else -> why(r)
      }

  /** [thing] on the Tile, or off it. At most [MAX_PINNED]: the oldest goes to make room. */
  fun pin(thing: Thing, on: Boolean) {
    val now = _pinned.value.filterNot { it == thing.id }
    val next = if (on) (now + thing.id).takeLast(MAX_PINNED) else now
    _pinned.value = next
    keep.pinned = next
    changed()
  }

  private fun setCounts(c: Counts) {
    if (c == _counts.value) return
    _counts.value = c
    keep.approvals = c.approvals
    keep.jobs = c.jobs
    changed()
  }

  private fun why(r: Reply?): String =
      when (r) {
        null -> "The phone isn't in reach, or hasn't the Jarvis app"
        is Reply.Failed -> r.text
        else -> "The phone answered something else"
      }

  /** How an [act] went: what Jarvis said ([ok]), or why it wasn't done. */
  data class Outcome(val ok: Boolean, val text: String)

  companion object {
    /** As many things as the Tile has room for. */
    const val MAX_PINNED = 3
  }
}
