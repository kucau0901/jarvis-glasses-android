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
fun DotMatrix(text: String, color: Color, dot: Dp = 3.dp, modifier: Modifier = Modifier) {
  val cols = text.length * (GLYPH_W + 1) - 1
  Canvas(modifier.size(dot * 2 * cols, dot * 2 * GLYPH_H)) {
    val pitch = size.height / GLYPH_H
    val r = pitch * 0.36f
    var x0 = 0
    for (ch in text) {
      val rows = GLYPHS[ch]
      if (rows != null) {
        for ((y, row) in rows.withIndex()) {
          for (x in 0 until GLYPH_W) {
            if (row[x] == '#') drawCircle(color, r, Offset((x0 + x + 0.5f) * pitch, (y + 0.5f) * pitch))
          }
        }
      }
      x0 += GLYPH_W + 1
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
