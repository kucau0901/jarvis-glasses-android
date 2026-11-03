package app.jarvis.glasses.ui

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val Red = Color(0xFFFF3B30)
private val Veil = Color(0x99000000)

/**
 * The G2 demo camera ([app.jarvis.glasses.DemoActivity]), in landscape: the camera's picture with
 * the G2 screen drawn in, the controls down the right (close, the record button with the time
 * filmed, the camera to flip), and what to say or what was saved over the picture's top.
 */
@Composable
fun DemoScreen(
    preview: PreviewView,
    recording: Boolean,
    elapsedNanos: Long,
    front: Boolean,
    saved: Uri?,
    problem: String,
    g2: Boolean,
    onRecord: () -> Unit,
    onFlip: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView({ preview }, Modifier.fillMaxSize())
    Box(Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(16.dp)) {
      when {
        problem.isNotEmpty() -> Label(problem, color = Red)
        recording -> Unit
        saved != null ->
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Veil).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
              Text("Saved to Movies/Jarvis", color = Color.White)
              TextButton(onClick = onShare) { Text("Share", fontWeight = FontWeight.SemiBold) }
            }
        g2 -> Label("Say \"Hey Even, open Jarvis\": what the G2 shows is filmed in the middle.")
        else -> Label("The G2 needs Android 13 or newer, so only the camera is filmed.")
      }
    }
    Column(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      IconButton(onClick = onClose, enabled = !recording) { Icon(Icons.Filled.Close, "Close", tint = if (recording) Color.Gray else Color.White) }
      Spacer(Modifier.weight(1f))
      Label(if (recording) "\u25CF  ${duration(elapsedNanos)}" else "0:00", color = if (recording) Red else Color.White)
      Spacer(Modifier.height(12.dp))
      RecordButton(recording, onRecord)
      Spacer(Modifier.weight(1f))
      TextButton(onClick = onFlip, enabled = !recording) { Text(if (front) "Back\ncamera" else "Front\ncamera", color = if (recording) Color.Gray else Color.White, textAlign = TextAlign.Center) }
    }
  }
}

@Composable
private fun Label(text: String, color: Color = Color.White) =
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Veil).padding(horizontal = 14.dp, vertical = 8.dp),
    )

/** A white ring around a red dot, the dot a square while recording. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) =
    Box(
        Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).clip(CircleShape).clickable(onClick = onClick).semantics {
          contentDescription = if (recording) "Stop recording" else "Record"
        },
        contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.size(if (recording) 30.dp else 62.dp).clip(if (recording) RoundedCornerShape(6.dp) else CircleShape).background(Red))
    }

/** "0:42", "12:05". */
fun duration(nanos: Long): String {
  val s = nanos / 1_000_000_000
  return "%d:%02d".format(s / 60, s % 60)
}
