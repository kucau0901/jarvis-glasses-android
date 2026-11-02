package app.jarvis.tv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The apps on Home, drawn as the phone's Home icons are (HomeIcons): one thin line on a 24-unit
 * grid, in the page's ink, so a row of them reads as one set instead of five brands' colours.
 * Each only hints at its app; the name under it says which.
 */
object AppIcons {
  /** A rounded screen with a play mark in it. */
  val YouTube: ImageVector =
      line(
          "youtube",
          "M2.5 9.6c0-1.9 1.2-3.2 3.1-3.4C7.7 6 9.8 5.9 12 5.9s4.3.1 6.4.3c1.9.2 3.1 1.5 3.1 3.4v4.8c0 1.9-1.2 3.2-3.1 3.4" +
              "-2.1.2-4.2.3-6.4.3s-4.3-.1-6.4-.3c-1.9-.2-3.1-1.5-3.1-3.4z",
          fill = "M10.2 9.3v5.4l4.5-2.7z",
      )

  /** An N, as its ribbon is folded. */
  val Netflix: ImageVector = line("netflix", "M7.5 3.5v17M16.5 3.5v17M7.5 3.5l9 17")

  /** A D and a plus, under the arc that sweeps over its name. */
  val DisneyPlus: ImageVector =
      line("disneyplus", "M2.5 15C3 8.5 8.5 4.5 15 5.2c3 .3 5.3 1.6 6.3 3.4 M5.5 11.5v7h1.3a3.5 3.5 0 0 0 0-7z M17 12.5v6M14 15.5h6")

  /** An apple, its leaf, and the bite. */
  val AppleTv: ImageVector =
      line(
          "appletv",
          "M12 8.2c-1.2-.9-3.3-1-4.5.4-1.6 1.9-.9 5.4 1 7.3.9.9 1.8 1 2.6.6.6-.3 1.3-.3 1.8 0 .8.4 1.7.3 2.6-.6 1-.9 1.6-2.2 1.9-3.4" +
              "-1.5-.5-2.3-1.8-2.1-3.2.1-.8.6-1.4 1.1-1.7-1.1-1.2-3-1.3-4.4-.4z M12 8.2c0-1.6.9-2.8 2.4-3.2",
          // Drawn small on the grid; grown to the others' size.
          scale = 1.6f,
      )

  /** A TV with a u on it. */
  val UnifiTv: ImageVector = line("unifitv", "M4 4.5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-10a1 1 0 0 1 1-1z M8 20h8M12 16.5V20 M9.5 8v2.8a2.5 2.5 0 0 0 5 0V8")

  /** Nine dots in a square: all the other apps. */
  val Drawer: ImageVector =
      line(
          "drawer",
          "M5 5h.01M12 5h.01M19 5h.01M5 12h.01M12 12h.01M19 12h.01M5 19h.01M12 19h.01M19 19h.01",
          width = 3f,
      )

  /** A cog. */
  val Settings: ImageVector =
      line(
          "settings",
          "M10.3 4.3c.4-1.8 3-1.8 3.4 0a1.7 1.7 0 0 0 2.6 1.1c1.5-.9 3.3.8 2.4 2.4a1.7 1.7 0 0 0 1 2.5c1.8.4 1.8 3 0 3.4" +
              "a1.7 1.7 0 0 0-1 2.6c.9 1.5-.8 3.3-2.4 2.4a1.7 1.7 0 0 0-2.6 1c-.4 1.8-3 1.8-3.4 0a1.7 1.7 0 0 0-2.5-1" +
              "c-1.6.9-3.3-.9-2.4-2.4a1.7 1.7 0 0 0-1.1-2.6c-1.8-.4-1.8-3 0-3.4a1.7 1.7 0 0 0 1.1-2.5c-.9-1.6.8-3.3 2.4-2.4" +
              "a1.7 1.7 0 0 0 2.5-1.1z M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
      )

  /** A plus: add a tile. */
  val Add: ImageVector = line("add", "M12 5v14M5 12h14")

  private fun line(name: String, stroke: String, fill: String = "", width: Float = 1.5f, scale: Float = 1f): ImageVector =
      ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
          .addGroup(pivotX = 12f, pivotY = 12f, scaleX = scale, scaleY = scale)
          .apply {
            // The line keeps its width however the picture is grown.
            addPath(addPathNodes(stroke), stroke = SolidColor(Color.Black), strokeLineWidth = width / scale, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            if (fill.isNotEmpty()) addPath(addPathNodes(fill), fill = SolidColor(Color.Black))
          }
          .clearGroup()
          .build()
}
