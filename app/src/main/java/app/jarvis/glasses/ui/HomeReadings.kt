package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.Reading
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// A tile's reading comes on its own, as on Jarvis's web app (src/app/ui/Home.ts, read()): the one
// Jarvis kept first, at once; then, only if that is due again, a fresh one, which for a reading
// Jarvis looks up can take a minute and a half. Each tile is read at most once a minute while Home is
// open, or again in 20 s while Jarvis is still looking it up. Plain Kotlin, to be tested.

internal const val READ_MS = 60_000L

/**
 * Readings kept while the app runs, for whoever is signed in, so going to Chat and back doesn't read
 * every tile again at once (each read of a tool tile runs its tool on Jarvis).
 */
internal object KeptReadings {
  private var owner = ""
  private val readings = androidx.compose.runtime.mutableStateMapOf<String, Got>()

  fun readingsOf(who: String): MutableMap<String, Got> {
    if (who != owner) {
      readings.clear()
      owner = who
    }
    return readings
  }
}
internal const val SOON_MS = 20_000L

/** A tile's reading as last had, and when it was last asked for. */
internal data class Got(val reading: Reading?, val asked: Long)

/** Whether a tile is due to be read again. */
internal fun due(got: Got?, now: Long): Boolean = got == null || now - got.asked >= READ_MS

/** The reading to show for what came back, keeping the one before when nothing new came. */
internal fun shown(old: Reading?, new: Reading): Reading? =
    when {
      new.failed || new.text.isNotBlank() || new.figures.isNotEmpty() || new.list.isNotEmpty() -> new
      // An error from before is not the reading being looked up now.
      new.pending -> (old?.takeUnless { it.failed } ?: new).copy(text = old?.takeUnless { it.failed }?.text?.takeIf { it.isNotBlank() } ?: "Looking it up…", pending = true, failed = false)
      else -> old ?: new
    }

/** When a fresh reading was asked for, as to be read again in [SOON_MS] if Jarvis is still looking it up. */
internal fun askedAt(new: Reading, now: Long): Long = if (new.pending) now - READ_MS + SOON_MS else now

/** A reading that could not be had, saying why. */
internal fun failedReading(why: String?): Reading = Reading(text = why ?: "Could not read it just now", failed = true)

/** "Checked 9:25 PM", from when it was looked up. */
internal fun checkedAt(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "Checked " + DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(at).atZone(zone))

/** "hour", "day", "3 hours", "90 minutes": how often one Jarvis looks up is looked up again (shared/panels.ts everyWords). */
internal fun everyWords(m: Int): String = if (m == 60) "hour" else if (m == 1440) "day" else if (m % 60 != 0) "$m minutes" else "${m / 60} hours"

/** Where a reading comes from, for its sheet: the tool read each time, or how often Jarvis looks it up. */
internal fun infoLine(tool: String?, every: Int?, refresh: Boolean): String? =
    when {
      tool != null -> "Reads ${tool.replace('_', ' ')} each minute while Home is open"
      every != null -> "Looked up every ${everyWords(every)}" + if (refresh) ", even with Home closed" else ""
      else -> null
    }

/** A figure's value as the web draws it: its first word large ("251.30"), the rest small ("USD"). */
internal fun splitFigure(value: String): Pair<String, String> {
  val parts = value.trim().split(Regex("\\s+"), limit = 2)
  return parts[0] to parts.getOrElse(1) { "" }
}
