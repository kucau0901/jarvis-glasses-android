package app.jarvis.glasses.chat

import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What Jarvis put on the screen for an answer: a `display` event (tools/place.ts, camera.ts,
 * image.ts and page.ts in jarvis-voice-agent), which the web app draws on its stage (ui/Stage.ts)
 * and Chat here draws under the answer. Each is fetched from Jarvis with the sign-in, as the web
 * fetches it, so no Google key or Home Assistant token reaches the phone.
 */
sealed interface Shown {
  /** A place as a map, a satellite view or a street-level photo. */
  data class Place(val lat: Double, val lng: Double, val label: String, val street: Boolean, val satellite: Boolean) : Shown {
    /** The picture, as the web's stage asks for it (Stage.ts showStatic), so both share what Jarvis keeps of it. */
    val path: String
      get() {
        val at = "lat=${"%.5f".format(Locale.US, lat)}&lng=${"%.5f".format(Locale.US, lng)}"
        val type = if (street) "" else "&maptype=${if (satellite) "satellite" else "roadmap"}"
        return "/api/map?kind=${if (street) "streetview" else "map"}&$at&sv=$MAP_STYLE&w=640&h=$HEIGHT$type"
      }

    /** Where a tap goes: Google Maps' own Street View, satellite or map, pannable as the picture isn't. */
    val open: String
      get() {
        val at = "${"%.6f".format(Locale.US, lat)},${"%.6f".format(Locale.US, lng)}"
        return when {
          street -> "google.streetview:cbll=$at"
          // geo: has no layer: Maps takes its own web address, which keeps the satellite.
          satellite -> openOnWeb
          else -> "geo:$at?q=$at(${enc(label)})"
        }
      }

    /** For a phone with no Maps app: the same on the web. */
    val openOnWeb: String
      get() {
        val at = "${"%.6f".format(Locale.US, lat)},${"%.6f".format(Locale.US, lng)}"
        return when {
          street -> "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$at"
          satellite -> "https://www.google.com/maps/@?api=1&map_action=map&center=$at&zoom=17&basemap=satellite"
          else -> "https://www.google.com/maps/search/?api=1&query=$at"
        }
      }
  }

  /** A camera, live: a frame at a time ([path]), as the web's stage refreshes one. */
  data class Camera(val entity: String, val label: String) : Shown {
    val path: String
      get() = "/api/camera?entity=${enc(entity)}&h=540"
  }

  /** A picture Jarvis made, to look at and save. */
  data class Picture(val id: String, val label: String) : Shown {
    val path: String
      get() = "/api/image?id=${enc(id)}"
  }

  /** A page Jarvis made: a card to open, kept with its answer ([app.jarvis.glasses.hub.Said.page]). */
  data class Page(val id: String, val title: String) : Shown

  /** "Close that": what is on the screen goes. */
  data object Close : Shown

  companion object {
    /** The web's map style (src/shared/version.ts MAP_STYLE_VERSION there), part of the address so both share a cached map. */
    const val MAP_STYLE = 3

    /** The web's height for its picture on a phone (Stage.ts asks for half the window, at most 440): 640×400. */
    const val HEIGHT = 400

    /** A `{"type":"display", …}` event, or null for anything else, or one this app can't draw. */
    fun from(o: JsonObject): Shown? {
      if (o.str("type") != "display") return null
      val label = o.str("label").orEmpty().trim()
      return when (o.str("kind")) {
        "map", "streetview" -> {
          val lat = o.num("lat") ?: return null
          val lng = o.num("lng") ?: return null
          if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
          Place(lat, lng, label, street = o.str("kind") == "streetview", satellite = o.str("maptype") == "satellite")
        }
        "camera" -> o.str("entity")?.takeIf { it.isNotBlank() }?.let { Camera(it, label.ifEmpty { "Camera" }) }
        "image" -> o.str("image")?.takeIf { it.isNotBlank() }?.let { Picture(it, label) }
        "page" -> o.str("page")?.takeIf { it.isNotBlank() }?.let { Page(it, label.ifEmpty { "Page" }) }
        "close" -> Close
        else -> null
      }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
  }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
