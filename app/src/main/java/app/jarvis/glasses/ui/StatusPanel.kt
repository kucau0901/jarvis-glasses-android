package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.glasses.GlassesState
import app.jarvis.glasses.glasses.LOW_GLASSES
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * Home's status panel, an old black-and-white LCD as the glasses' own display might be: the date,
 * the time in large dots, what Jarvis is doing, and the glasses: which, their battery, and whether
 * they are connected, worn or folded. Whatever the glasses' maker doesn't report is left out.
 */
@Composable
fun StatusPanel(glasses: GlassesState?, jarvis: String, modifier: Modifier = Modifier) {
  val c = Even.colors
  val ink = c.hudInk
  val ghost = ink.copy(alpha = 0.07f)
  var time by remember { mutableStateOf(LocalDateTime.now()) }
  LaunchedEffect(Unit) {
    while (true) {
      time = LocalDateTime.now()
      delay(15_000)
    }
  }
  val lines = glassesLines(glasses)
  Row(
      modifier
          .fillMaxWidth()
          .height(184.dp)
          .clip(Even.corner)
          .background(c.hud)
          .padding(18.dp)
          .semantics { contentDescription = (listOf("Jarvis $jarvis") + lines + listOfNotNull(glasses?.battery?.let { "battery $it%" })).joinToString(", ") },
  ) {
    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
      Lcd(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(), ink)
      DotMatrix(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = ink, dot = 4.5.dp, ghost = ghost)
      Lcd("JARVIS: ${jarvis.uppercase()}", ink)
    }
    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
      Dots(GLASSES, if (glasses?.link == GlassesState.Link.CONNECTED) ink else ink.copy(alpha = 0.35f), dot = 1.5.dp, ghost = ghost)
      for (line in lines) Lcd(line, ink)
      val battery = glasses?.battery?.takeIf { glasses.link == GlassesState.Link.CONNECTED }
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (battery != null) {
          if (glasses.charging == true) Dots(BOLT, ink, dot = 1.2.dp, ghost = ghost)
          Lcd(if (battery < LOW_GLASSES) "LOW $battery%" else "$battery%", ink)
        }
        Dots(batteryDots(battery), if (battery == null) ink.copy(alpha = 0.35f) else ink, dot = 1.2.dp, ghost = ghost)
      }
    }
  }
}

@Composable
private fun Lcd(text: String, ink: Color) {
  Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp, color = ink), maxLines = 1)
}

/** The glasses in words, for the panel: which they are, then how they are. */
internal fun glassesLines(g: GlassesState?): List<String> {
  if (g == null) return listOf("NO GLASSES")
  val which = g.model.ifEmpty { g.name }.ifEmpty { "GLASSES" }.uppercase()
  val how =
      when {
        g.link == GlassesState.Link.AWAY -> "AWAY"
        g.link == GlassesState.Link.CONNECTING -> "CONNECTING"
        g.worn == true -> "ON FACE"
        g.folded == true -> "FOLDED"
        g.worn == false -> "OFF FACE"
        else -> "CONNECTED"
      }
  return listOfNotNull(which, how, "WARM".takeIf { g.hot }, "UPDATE NEEDED".takeIf { g.needsUpdate != null })
}

/** A battery in dots, filled to [level] (empty, and faint, when not known). */
internal fun batteryDots(level: Int?): List<String> {
  val inner = 9
  val filled = level?.let { ceil(it.coerceIn(0, 100) * inner / 100.0).toInt() } ?: 0
  val body = (0 until 5).map { "#" + "#".repeat(filled).padEnd(inner, '.') + "#" }
  val nub = listOf("..", "##", "##", "##", "..")
  return listOf("#".repeat(inner + 2) + "..") + body.mapIndexed { i, r -> r + nub[i] } + listOf("#".repeat(inner + 2) + "..")
}

/** A pair of glasses, front on. */
internal val GLASSES = listOf(
    ".####.....####.",
    "#....#...#....#",
    "#....#####....#",
    "#....#...#....#",
    ".####.....####.",
)

/** Charging. */
internal val BOLT = listOf("...#.", "..##.", ".##..", "#####", "..##.", ".##..", ".#...")
