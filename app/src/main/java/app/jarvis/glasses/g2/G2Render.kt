package app.jarvis.glasses.g2

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Where a G2 screen is drawn on a picture: [width] across from [left], [top] down, twice as wide as it is high, as the display is. */
data class G2Strip(val left: Float, val top: Float, val width: Float) {
  val height: Float
    get() = width / 2
}

/**
 * The strip a G2 screen takes in the middle of a [w] × [h] picture, where the glasses show it: the
 * full width less a small margin in portrait; in landscape, under half the height.
 */
fun g2Strip(w: Int, h: Int): G2Strip {
  val margin = minOf(w, h) * 0.03f
  val width = minOf(w - 2 * margin, h * 0.45f * 2)
  return G2Strip((w - width) / 2, (h - width / 2) / 2, width)
}

/** The glasses' text size, in the display's own pixels: about [G2_LINE_CHARS] characters to a line, as on them. */
const val G2_TEXT_PX = 24f

/**
 * Draws G2 screens on a picture as the glasses show them: green text and frames, the frames as
 * bright as their [G2Box.shade], and nothing behind them, as on the glasses. The font is the
 * phone's, not the glasses', so the lines are close to theirs but not exact. The text's layout is
 * kept between frames while the screen stays the same.
 */
class G2Painter {
  private val green = Color.rgb(0x5C, 0xFF, 0x8A)
  private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = green
    textSize = G2_TEXT_PX
    typeface = Typeface.SANS_SERIF
  }
  private var laidOut: List<G2Box>? = null
  private var layouts = emptyList<StaticLayout>()

  fun draw(canvas: Canvas, boxes: List<G2Box>, strip: G2Strip) {
    if (boxes !== laidOut) {
      layouts = boxes.map { b -> StaticLayout.Builder.obtain(b.text, 0, b.text.length, text, maxOf(1, b.w - 2 * inset(b))).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build() }
      laidOut = boxes
    }
    val scale = strip.width / EvenHub.WIDTH
    canvas.save()
    canvas.translate(strip.left, strip.top)
    canvas.scale(scale, scale)
    boxes.forEachIndexed { i, b ->
      if (b.border > 0) {
        val half = b.border / 2f
        frame.strokeWidth = b.border.toFloat()
        frame.color = Color.argb(70 + 185 * b.shade.coerceIn(0, 15) / 15, Color.red(green), Color.green(green), Color.blue(green))
        canvas.drawRoundRect(b.x + half, b.y + half, b.x + b.w - half, b.y + b.h - half, 10f, 10f, frame)
      }
      canvas.save()
      canvas.clipRect(b.x, b.y, b.x + b.w, b.y + b.h)
      canvas.translate((b.x + inset(b)).toFloat(), (b.y + inset(b)).toFloat())
      layouts[i].draw(canvas)
      canvas.restore()
    }
    canvas.restore()
  }

  /** Where a box's text starts inside it: past its frame and padding, as the glasses place it. */
  private fun inset(b: G2Box) = if (b.border > 0) b.border + b.padding else 0
}
