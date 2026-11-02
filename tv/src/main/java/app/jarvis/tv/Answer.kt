package app.jarvis.tv

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.Even
import app.jarvis.glasses.ui.Tile

/**
 * Jarvis's answer under the status panel, as a plain Even tile rather than a chat bubble: what it
 * heard, in grey; what it is doing while there is no answer yet; then the answer, and the map,
 * camera or picture it showed at the right. Nothing at all while there is nothing to say.
 */
@Composable
fun AnswerCard(heard: String, working: String, answer: String, picture: ImageBitmap?, modifier: Modifier = Modifier) {
  if (heard.isBlank() && working.isBlank() && answer.isBlank() && picture == null) return
  val c = Even.colors
  Tile(modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.Top) {
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (heard.isNotBlank()) Text("You: $heard", style = TextStyle(fontSize = 15.sp, color = c.dim), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (answer.isBlank() && working.isNotBlank()) Text(working, style = TextStyle(fontSize = 17.sp, color = c.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (answer.isNotBlank()) Text(answer, style = TextStyle(fontSize = 19.sp, color = c.ink), maxLines = 8, overflow = TextOverflow.Ellipsis)
      }
      picture?.let {
        Spacer(Modifier.width(16.dp))
        // As tall as a few lines of answer, as wide as the picture is, within reason.
        val ratio = (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.75f, 2f)
        Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.height(PICTURE_HEIGHT).width(PICTURE_HEIGHT * ratio).clip(Even.corner))
      }
    }
  }
}

private val PICTURE_HEIGHT = 190.dp
