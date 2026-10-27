package app.jarvis.glasses.glasses

import kotlinx.coroutines.flow.StateFlow

/**
 * What a pair of glasses says about itself, whoever makes them: Ray-Ban Meta through Meta's toolkit
 * today, and others (Brilliant Labs' over Bluetooth, say) through their own [GlassesSource]. What a
 * maker doesn't report stays null, and Home simply doesn't show it.
 */
data class GlassesState(
    /** Its own name, as paired ("RB Meta 012H"). */
    val name: String,
    /** What it is ("Ray-Ban Meta"), for when the name says little. */
    val model: String = "",
    val link: Link,
    /** 0–100. */
    val battery: Int? = null,
    val charging: Boolean? = null,
    /** On someone's face. */
    val worn: Boolean? = null,
    /** Arms closed. */
    val folded: Boolean? = null,
    /** Warm enough that it may slow down or stop. */
    val hot: Boolean = false,
    /** It has a display of its own. */
    val display: Boolean = false,
    /** Why it can't be used as it is ("Update the glasses"), if so. */
    val needsUpdate: String? = null,
) {
  enum class Link { AWAY, CONNECTING, CONNECTED }
}

/** One maker's glasses as they come and go. */
interface GlassesSource {
  val glasses: StateFlow<List<GlassesState>>
}

/**
 * The pair Home shows, from all sources, and the phone's Bluetooth headset when no source knows it
 * ([headset]: its name while the glasses are connected for sound). Connected before connecting before
 * away; of those, the one being worn.
 */
fun shownGlasses(known: List<GlassesState>, headset: String?): GlassesState? {
  val best = known.sortedWith(compareBy({ -it.link.ordinal }, { if (it.worn == true) 0 else 1 })).firstOrNull()
  return when {
    best?.link == GlassesState.Link.CONNECTED -> best
    // Connected for sound though the toolkit hasn't caught up (or knows nothing of this maker).
    headset != null -> best?.copy(link = GlassesState.Link.CONNECTED) ?: GlassesState(name = headset, link = GlassesState.Link.CONNECTED)
    else -> best
  }
}

/**
 * Whether the glasses in use have been taken off or folded: of those connected, none is on a face
 * and one is said to be off it or folded. Unknown counts as worn, so glasses whose maker doesn't
 * say are never taken for off.
 */
fun takenOff(known: List<GlassesState>): Boolean {
  val connected = known.filter { it.link == GlassesState.Link.CONNECTED }
  return connected.none { it.worn == true } && connected.any { it.worn == false || it.folded == true }
}

/** Under this a battery is low. */
const val LOW_GLASSES = 20
