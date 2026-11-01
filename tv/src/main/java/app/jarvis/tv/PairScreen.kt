package app.jarvis.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.DotMatrix
import app.jarvis.glasses.ui.Even

/**
 * Signing the TV in to Jarvis: the code to type on Jarvis web, large in the status panel's dots so
 * it reads from the sofa, and where on the web to type it. [code] is null until Jarvis gives one;
 * [name] is who it signed in as; [error] says what went wrong, with a tile to try again.
 */
@Composable
fun PairScreen(code: String?, name: String?, error: String?, onRetry: () -> Unit) {
  val c = Even.colors
  val ink = c.hudInk
  val ghost = ink.copy(alpha = 0.07f)
  val retry = remember { FocusRequester() }
  LaunchedEffect(error) { if (error != null) retry.requestFocus() }
  Column(Modifier.fillMaxSize().background(c.page).padding(horizontal = 48.dp, vertical = 27.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Title("Sign in to Jarvis")
    Column(
        Modifier.fillMaxWidth().clip(Even.corner).background(c.hud).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      PairLcd(if (name != null) "JARVIS: SIGNED IN" else if (error != null) "JARVIS: NOT SIGNED IN" else "JARVIS: CODE FOR THIS TV", ink, Modifier.fillMaxWidth())
      // Blank, the code's places show as unlit dots while one is on its way.
      DotMatrix((code ?: "").padEnd(CODE_LENGTH).take(CODE_LENGTH), color = ink, dot = 7.dp, ghost = ghost)
      PairLcd(
          when {
            name != null -> name.uppercase()
            error != null -> "TRY AGAIN"
            code == null -> "GETTING A CODE"
            else -> "WAITING"
          },
          ink,
          Modifier.fillMaxWidth(),
      )
    }
    Spacer(Modifier.height(8.dp))
    if (name != null) {
      Text("Signed in as $name", style = TextStyle(fontSize = 21.sp, color = c.ink))
    } else {
      Text("On Jarvis web: menu → Family → Sign in another screen, then enter this code", style = TextStyle(fontSize = 21.sp, color = c.ink))
      if (error == null) Text(if (code == null) "Getting a code…" else "Waiting for it to be entered…", style = TextStyle(fontSize = 17.sp, color = c.dim))
    }
    if (error != null) {
      Text(error, style = TextStyle(fontSize = 17.sp, color = c.danger), maxLines = 3, overflow = TextOverflow.Ellipsis)
      TileRow(2) { FocusBar(onRetry, Modifier.weight(2f).focusRequester(retry)) { Text("Try again", style = TextStyle(fontSize = 17.sp, color = c.ink)) } }
    }
  }
}

/** The pairing code's length (Jarvis's /api/auth/pair/start). */
private const val CODE_LENGTH = 6

@Composable
private fun PairLcd(text: String, ink: Color, modifier: Modifier) {
  Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 1.5.sp, color = ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
