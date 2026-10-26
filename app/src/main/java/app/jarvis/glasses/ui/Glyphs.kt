package app.jarvis.glasses.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Thin line icons the outline set lacks, drawn to match it (24 px grid, 1.5 px stroke). */
object Glyphs {
  private fun line(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
      ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
          .path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
          .build()

  /** A speech bubble. */
  val Chat: ImageVector =
      line("chat") {
        moveTo(4f, 5f)
        horizontalLineTo(20f)
        verticalLineTo(16f)
        horizontalLineTo(10f)
        lineTo(6f, 20f)
        verticalLineTo(16f)
        horizontalLineTo(4f)
        close()
      }

  /** A microphone. */
  val Mic: ImageVector =
      line("mic") {
        moveTo(9f, 6f)
        arcTo(3f, 3f, 0f, false, true, 15f, 6f)
        verticalLineTo(12f)
        arcTo(3f, 3f, 0f, false, true, 9f, 12f)
        close()
        moveTo(5.5f, 11f)
        arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f)
        moveTo(12f, 17.5f)
        verticalLineTo(21f)
      }

  /** A grid of four squares: panels. */
  val Tiles: ImageVector =
      line("tiles") {
        for ((x, y) in listOf(4f to 4f, 13f to 4f, 4f to 13f, 13f to 13f)) {
          moveTo(x, y)
          horizontalLineTo(x + 7f)
          verticalLineTo(y + 7f)
          horizontalLineTo(x)
          close()
        }
      }

  /** A checklist: jobs. */
  val Jobs: ImageVector =
      line("jobs") {
        moveTo(5f, 6f); lineTo(6.5f, 7.5f); lineTo(9f, 5f)
        moveTo(12f, 6.5f); horizontalLineTo(20f)
        moveTo(5f, 12f); lineTo(6.5f, 13.5f); lineTo(9f, 11f)
        moveTo(12f, 12.5f); horizontalLineTo(20f)
        moveTo(5f, 18f); lineTo(6.5f, 19.5f); lineTo(9f, 17f)
        moveTo(12f, 18.5f); horizontalLineTo(20f)
      }

  /** A clock: routines. */
  val Clock: ImageVector =
      line("clock") {
        moveTo(12f, 3.5f)
        arcTo(8.5f, 8.5f, 0f, true, true, 11.99f, 3.5f)
        moveTo(12f, 7.5f)
        verticalLineTo(12f)
        lineTo(15f, 14f)
      }

  /** Glasses. */
  val Glasses: ImageVector =
      line("glasses") {
        moveTo(3f, 12f)
        arcTo(3.5f, 3.5f, 0f, true, false, 10f, 12f)
        arcTo(3.5f, 3.5f, 0f, true, false, 3f, 12f)
        moveTo(14f, 12f)
        arcTo(3.5f, 3.5f, 0f, true, false, 21f, 12f)
        arcTo(3.5f, 3.5f, 0f, true, false, 14f, 12f)
        moveTo(10f, 11.5f)
        quadTo(12f, 10f, 14f, 11.5f)
      }

  /** A paper plane: send. */
  val Send: ImageVector =
      line("send") {
        moveTo(4f, 12f)
        lineTo(20f, 4f)
        lineTo(14f, 20f)
        lineTo(11f, 13f)
        close()
        moveTo(11f, 13f)
        lineTo(20f, 4f)
      }

  /** A light bulb. */
  val Bulb: ImageVector =
      line("bulb") {
        moveTo(9f, 17f)
        horizontalLineTo(15f)
        moveTo(10f, 20f)
        horizontalLineTo(14f)
        moveTo(9f, 17f)
        verticalLineTo(14.5f)
        arcTo(6f, 6f, 0f, true, true, 15f, 14.5f)
        verticalLineTo(17f)
      }

  /** A thermometer. */
  val Thermo: ImageVector =
      line("thermo") {
        moveTo(10f, 14.5f)
        verticalLineTo(5f)
        arcTo(2f, 2f, 0f, false, true, 14f, 5f)
        verticalLineTo(14.5f)
        arcTo(4f, 4f, 0f, true, true, 10f, 14.5f)
        close()
      }

  /** A gate or door: a frame with a handle. */
  val Gate: ImageVector =
      line("gate") {
        moveTo(5f, 20f)
        verticalLineTo(4f)
        horizontalLineTo(19f)
        verticalLineTo(20f)
        moveTo(3f, 20f)
        horizontalLineTo(21f)
        moveTo(15f, 12f)
        verticalLineTo(12.5f)
      }

  /** A camera. */
  val Camera: ImageVector =
      line("camera") {
        moveTo(4f, 8f)
        horizontalLineTo(8f)
        lineTo(9.5f, 6f)
        horizontalLineTo(14.5f)
        lineTo(16f, 8f)
        horizontalLineTo(20f)
        verticalLineTo(18f)
        horizontalLineTo(4f)
        close()
        moveTo(12f, 9.5f)
        arcTo(3.2f, 3.2f, 0f, true, true, 11.99f, 9.5f)
      }
}
