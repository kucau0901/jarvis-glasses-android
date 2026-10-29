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
 * Whether the glasses in use are folded: of those connected, none is on a face and one has its arms
 * closed. Unknown counts as open.
 */
fun folded(known: List<GlassesState>): Boolean {
  val connected = known.filter { it.link == GlassesState.Link.CONNECTED }
  return connected.none { it.worn == true } && connected.any { it.folded == true }
}

/** Whether a pair connected says it is on someone's face. */
fun worn(known: List<GlassesState>): Boolean = known.any { it.link == GlassesState.Link.CONNECTED && it.worn == true }

/**
 * Whether a pair still connected was just taken off: from [before] to [now] it folded, or went from
 * on a face to off it. Only a change seen on the same pair counts: "off the face" alone can't be
 * trusted (Meta's toolkit has said it of glasses being worn), nor a pair connecting already folded.
 */
fun cameOff(before: List<GlassesState>, now: List<GlassesState>): Boolean =
    now.any { g ->
      val was = before.firstOrNull { it.name == g.name } ?: return@any false
      g.link == GlassesState.Link.CONNECTED && was.link == GlassesState.Link.CONNECTED &&
          ((was.folded == false && g.folded == true) || (was.worn == true && g.worn == false))
    }

/** Under this a battery is low. */
const val LOW_GLASSES = 20
