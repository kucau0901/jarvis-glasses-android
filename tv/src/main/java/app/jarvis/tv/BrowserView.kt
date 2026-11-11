package app.jarvis.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The browser Jarvis works in, when it hands the page over (a `display` of kind "browser"): its
 * screen over the whole TV, fetched about twice a second while it is up ([screen]: the page's name
 * and its picture). To watch only: a remote can't press a page, so a line says to use a phone, and
 * the TV sends the browser nothing. Back closes it.
 *
 * [said]: what Jarvis is doing or has answered meanwhile, which the screen behind would have shown.
 */
@Composable
fun BrowserView(screen: suspend () -> Pair<String, ImageBitmap?>, said: String) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  var picture by remember { mutableStateOf<ImageBitmap?>(null) }
  var heading by remember { mutableStateOf("") }
  var why by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    // Only while the TV shows it: not behind another app, not once Back has closed it.
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      var failures = 0
      // One that isn't set up won't appear by asking: after a few failures in a row, what Jarvis said stays up.
      while (failures < GIVE_UP) {
        try {
          val (name, frame) = screen()
          heading = name
          if (frame != null) picture = frame
          why = if (frame == null) "Jarvis sent no picture of the browser" else null
          failures = 0
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failures++
          why = e.message ?: "The browser's screen didn't come"
        }
        delay(if (failures > 0) FAILED_MS else SCREEN_MS)
      }
    }
  }
  // The remote stays here: nothing behind is moved to or pressed while the browser is up.
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
  Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusProperties { onExit = { cancelFocusChange() } }.focusable()) {
    picture?.let { Image(it, contentDescription = "The browser's screen", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 48.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      val line = why ?: heading.ifEmpty { if (picture == null) "Getting the browser's screen…" else "" }
      if (line.isNotEmpty()) Text(line, style = TextStyle(fontSize = 19.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (said.isNotBlank()) Text(said, style = TextStyle(fontSize = 17.sp, color = Color.White.copy(alpha = 0.8f)), maxLines = 2, overflow = TextOverflow.Ellipsis)
      Text("Use your phone to press or type · Back to close", style = TextStyle(fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f)), maxLines = 1)
    }
  }
}

private const val SCREEN_MS = 500L
private const val FAILED_MS = 3_000L
private const val GIVE_UP = 3
