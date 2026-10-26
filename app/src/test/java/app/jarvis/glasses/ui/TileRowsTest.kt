package app.jarvis.glasses.ui

import app.jarvis.glasses.hub.PanelRow
import org.junit.Assert.assertEquals
import org.junit.Test

class TileRowsTest {
  private fun row(key: String) = PanelRow(key = key, kind = "home", label = key)

  private fun layout(keys: String, open: String = "") =
      tileRows(keys.map { row(it.toString()) }) { it.key in open }.joinToString(" ") { r -> r.joinToString("") { it.key } }

  @Test fun closedTilesGoTwoToARow() = assertEquals("ab cd e", layout("abcde"))

  @Test fun anOpenedTileTakesTheWholeRow() = assertEquals("a b cd e", layout("abcde", open = "b"))

  @Test fun tilesKeepTheirOrderAroundTwoOpenedOnes() = assertEquals("a b c d", layout("abcd", open = "bc"))
}
