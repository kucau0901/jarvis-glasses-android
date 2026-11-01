package app.jarvis.glasses.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Home's icons, the Jarvis web app's own (index.html's `--i-*` masks, circles and boxes written as
 * paths), so a thing has the same picture on both: a shield for an alarm, a snowflake for an aircond.
 * Drawn in the app's thinner line.
 */
object HomeIcons {
  private class Shape(val stroke: String, val fill: String)

  private val shapes =
      mapOf(
          "house" to Shape("M3 11 12 4l9 7 M5 10v10h14V10M10 20v-6h4v6", ""),
          "gate" to Shape("M3 5v16M21 5v16M3 8h18M3 18h18M7.5 8v10M12 8v10M16.5 8v10", ""),
          "garage" to Shape("M3 21V9l9-6 9 6v12 M7 21v-9h10v9M7 15h10M7 18h10", ""),
          "door" to Shape("M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17M4 21h16M14.5 12h.01", ""),
          "window" to Shape("M5.5 3h13a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1 -1.5 1.5h-13a1.5 1.5 0 0 1 -1.5 -1.5v-15a1.5 1.5 0 0 1 1.5 -1.5z M12 3v18M4 12h16", ""),
          "blind" to Shape("M3 4h18M5 4v12h14V4M5 8h14M5 12h14M12 16v4", ""),
          "lock" to Shape("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z M8 11V7a4 4 0 0 1 8 0v4", ""),
          "unlock" to Shape("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z M8 11V7a4 4 0 0 1 7.7-1.5", ""),
          "bulb" to Shape("M9 18h6M10 21h4M12 3a6 6 0 0 0-3.5 10.9c.7.5 1.1 1.2 1.2 2.1h4.6c.1-.9.5-1.6 1.2-2.1A6 6 0 0 0 12 3z", ""),
          "power" to Shape("M12 3v8M7.5 6.5a7 7 0 1 0 9 0", ""),
          "plug" to Shape("M9 2v5M15 2v5M6 7h12v4a6 6 0 0 1-12 0zM12 17v5", ""),
          "fan" to Shape("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z M12 12V6.5M12 12l4.8 2.8M12 12l-4.8 2.8", ""),
          "snow" to Shape("M12 2v20M3.3 7l17.4 10M3.3 17 20.7 7M9 4l3 2 3-2M9 20l3-2 3 2", ""),
          "sparkle" to Shape("M11 3l1.8 4.7 4.7 1.8-4.7 1.8L11 16l-1.8-4.7L4.5 9.5l4.7-1.8zM18.5 14.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z", ""),
          "play" to Shape("", "M7 4.5v15l12-7.5z"),
          "press" to Shape("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z", ""),
          "temp" to Shape("M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0zM12 11v6", ""),
          "drop" to Shape("M12 3s6 6.5 6 11a6 6 0 0 1-12 0c0-4.5 6-11 6-11z", ""),
          "bolt" to Shape("M13 2 4 14h7l-1 8 9-12h-7z", ""),
          "person" to Shape("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z M4 21a8 8 0 0 1 16 0", ""),
          "gauge" to Shape("M4 17a8 8 0 1 1 16 0M12 17l4-5", ""),
          "cloud" to Shape("M7 19h10a4 4 0 0 0 .4-8 5.5 5.5 0 0 0-10.7 1.4A3.3 3.3 0 0 0 7 19z", ""),
          "music" to Shape("M9 18V5l11-2v13 M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z M14 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", ""),
          "car" to Shape("M5 16V12l2-5h10l2 5v4z M3 12h18M7 19v-3M17 19v-3 M7.5 13.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0z M15.5 13.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0z", ""),
          "bars" to Shape("M5 20V12M12 20V5M19 20v-9", ""),
          "note" to Shape("M5 3h10l4 4v14H5z M14 3v5h5M8 13h8M8 17h5", ""),
          "shield" to Shape("M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6z", ""),
          "bell" to Shape("M6 16V11a6 6 0 0 1 12 0v5l2 2H4z M10 21h4", ""),
          "calendar" to Shape("M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z M3 10h18M8 3v4M16 3v4", ""),
          "mail" to Shape("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z M3 7l9 6 9-6", ""),
          "camera" to Shape("M4 8h3l2-3h6l2 3h3v11H4z M8.5 13a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0z", ""),
          "clock" to Shape("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z M12 7v5l3 2", ""),
          "terminal" to Shape("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z M7 9l3 3-3 3M12 15h5", ""),
          "route" to Shape("M4 19a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M16 5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z M8 19h8a3.5 3.5 0 0 0 0-7H8a3.5 3.5 0 0 1 0-7h8", ""),
          "messages" to Shape("M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z", ""),
          "globe" to Shape("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18", ""),
          "dot" to Shape("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z", ""),
      )

  /** A panel row's icon name (PanelIcon in the web's src/shared/panels.ts) to its picture's name. */
  private val names =
      mapOf(
          "light" to "bulb",
          "climate" to "snow",
          "scene" to "sparkle",
          "motion" to "person",
          "sensor" to "gauge",
          "weather" to "cloud",
          "usage" to "bars",
          "alarm" to "shield",
          "alert" to "bell",
          "chat" to "messages",
          "web" to "globe",
      )

  private val made = HashMap<String, ImageVector>()

  /** The picture for [icon]; one the app doesn't know is a dot, as on the web. */
  fun of(icon: String): ImageVector {
    val name = (names[icon] ?: icon).takeIf { it in shapes } ?: "dot"
    return made.getOrPut(name) {
      val shape = shapes.getValue(name)
      ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
          .apply {
            if (shape.stroke.isNotEmpty()) {
              addPath(addPathNodes(shape.stroke), stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
            if (shape.fill.isNotEmpty()) addPath(addPathNodes(shape.fill), fill = SolidColor(Color.Black))
          }
          .build()
    }
  }

  /** The colour a lit thing's icon takes, by what it is, as on the web: openings blue, the car and alarm green, an alert red, light amber. */
  enum class Tint { OPEN, GOOD, ALERT, WARM }

  fun tintOf(icon: String): Tint =
      when (icon) {
        "gate", "garage", "door", "window", "blind", "unlock", "climate", "motion", "music" -> Tint.OPEN
        "car", "bolt", "alarm" -> Tint.GOOD
        "alert" -> Tint.ALERT
        else -> Tint.WARM
      }
}
