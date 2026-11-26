package app.jarvis.glasses.ui

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A component's video, as Jarvis shows it (a `display` of kind "video", docs/api.md in
 * jarvis-voice-agent): [sources] tried in the order given, each once, the next when one fails; a
 * `#t=` on one starts it there. Played from its own address, with nothing of Jarvis's sent with it.
 * [poster] is shown until it plays; [label], the component's words, is drawn as text only. Pressed to
 * play, unless [autoplay]. [end] once it has played through, or none of it can be.
 */
@Composable
fun VideoPlayer(sources: List<String>, label: String, poster: ImageBitmap?, modifier: Modifier = Modifier, autoplay: Boolean = false, end: () -> Unit = {}) {
  var next by remember(sources) { mutableIntStateOf(0) }
  var playing by remember(sources) { mutableStateOf(autoplay) }
  var failed by remember(sources) { mutableStateOf(false) }
  Column(modifier) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
      when {
        failed -> Text("This video can't be played here.", color = Color.White, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.Center).padding(12.dp))
        !playing -> {
          poster?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
          Text(
              "▶  Play",
              color = Color.White,
              style = MaterialTheme.typography.labelLarge,
              modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).clickable { playing = true }.padding(horizontal = 18.dp, vertical = 10.dp),
          )
        }
        else ->
            AndroidView(
                factory = { ctx -> VideoView(ctx).also { v -> v.setMediaController(MediaController(ctx).also { it.setAnchorView(v) }) } },
                update = { v ->
                  val src = sources.getOrNull(next)
                  if (src != null && v.tag != src) {
                    v.tag = src
                    val (url, startMs) = videoStart(src)
                    // Each tried once: the next when this one fails, and none left, it says so.
                    v.setOnErrorListener { _, _, _ ->
                      if (next + 1 < sources.size) next++
                      else {
                        failed = true
                        end()
                      }
                      true
                    }
                    v.setOnPreparedListener { mp ->
                      if (startMs > 0) mp.seekTo(startMs)
                      v.start()
                    }
                    v.setOnCompletionListener { end() }
                    v.setVideoURI(Uri.parse(url))
                  }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxSize(),
            )
      }
    }
    if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
  }
}

/** [src] without its `#t=` fragment, and where that says to start, in milliseconds (0 without one). */
fun videoStart(src: String): Pair<String, Int> {
  val at = src.indexOf('#')
  if (at < 0) return src to 0
  val t = Regex("(?:^|&)t=([0-9.]+)").find(src.substring(at + 1))?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
  return src.substring(0, at) to (t * 1000).toInt()
}
