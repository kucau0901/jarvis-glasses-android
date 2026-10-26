package app.jarvis.glasses.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Text drawn as dots, like the glasses' display in the Even app's preview. Digits, a colon and a
 * space; anything else is left blank.
 */
@Composable
fun DotMatrix(text: String, color: Color, dot: Dp = 3.dp, modifier: Modifier = Modifier, ghost: Color = Color.Transparent) {
  val rows = List(GLYPH_H) { y -> text.map { ch -> GLYPHS[ch]?.get(y) ?: "....." }.joinToString(".") }
  Dots(rows, color, dot, modifier, ghost)
}

/**
 * A picture in dots, row by row: '#' lit, anything else unlit, drawn faintly in [ghost] as an old
 * LCD shows its unlit dots.
 */
@Composable
fun Dots(rows: List<String>, color: Color, dot: Dp = 3.dp, modifier: Modifier = Modifier, ghost: Color = Color.Transparent) {
  val cols = rows.maxOfOrNull { it.length } ?: 0
  Canvas(modifier.size(dot * 2 * cols, dot * 2 * rows.size)) {
    val pitch = size.height / rows.size
    val r = pitch * 0.36f
    for ((y, row) in rows.withIndex()) {
      for (x in 0 until cols) {
        val lit = row.getOrNull(x) == '#'
        if (lit || ghost.alpha > 0f) drawCircle(if (lit) color else ghost, r, Offset((x + 0.5f) * pitch, (y + 0.5f) * pitch))
      }
    }
  }
}

private const val GLYPH_W = 5
private const val GLYPH_H = 7

private val GLYPHS: Map<Char, List<String>> =
    mapOf(
        '0' to listOf(".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."),
        '1' to listOf("..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."),
        '2' to listOf(".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"),
        '3' to listOf("####.", "....#", "....#", ".###.", "....#", "....#", "####."),
        '4' to listOf("...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."),
        '5' to listOf("#####", "#....", "####.", "....#", "....#", "#...#", ".###."),
        '6' to listOf("..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###."),
        '7' to listOf("#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."),
        '8' to listOf(".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."),
        '9' to listOf(".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##.."),
        ':' to listOf(".....", "..#..", "..#..", ".....", "..#..", "..#..", "....."),
        ' ' to List(7) { "....." },
    )
