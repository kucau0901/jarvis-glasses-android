package app.jarvis.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import app.jarvis.glasses.ui.DarkEven
import app.jarvis.glasses.ui.DotMatrix
import app.jarvis.glasses.ui.Dots
import app.jarvis.glasses.ui.Glyphs

/**
 * The round screen, in the Even look: what Jarvis is doing in the dot-matrix panel's letters at the
 * top, then the question and the answer, and the one round button to talk (or stop). Scrolls with
 * the bezel when an answer runs long. Always dark: an AMOLED watch lights only what it shows.
 */
@Composable
fun WatchScreen(
    heard: Ears.State,
    asked: Asking.State,
    speaking: Boolean,
    phone: PhoneSays,
    trouble: String?,
    speak: Boolean,
    following: Boolean,
    followUps: Boolean,
    onFollowUps: (Boolean) -> Unit,
    approvals: Int,
    onTalk: () -> Unit,
    onSpeak: (Boolean) -> Unit,
    onHome: () -> Unit,
    onApprovals: () -> Unit,
) {
  val c = DarkEven
  val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
  val listening = heard as? Ears.State.Listening
  val status =
      when {
        listening != null -> "LISTENING"
        heard is Ears.State.Failed -> "TRY AGAIN"
        speaking -> "SPEAKING"
        asked is Asking.State.Asked -> "THINKING"
        asked is Asking.State.Failed -> "TROUBLE"
        else -> "JARVIS"
      }
  val question =
      listening?.words?.takeIf { it.isNotBlank() }
          ?: when (asked) {
            is Asking.State.Asked -> asked.question
            is Asking.State.Answered -> asked.question
            is Asking.State.Failed -> asked.question
            Asking.State.Idle -> null
          }
  MaterialTheme {
    AppScaffold(modifier = Modifier.background(Color.Black)) {
      ScreenScaffold(scrollState = list) { padding ->
        ScalingLazyColumn(
            state = list,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item { DotMatrix(status, color = c.hudInk, dot = 1.4.dp, ghost = c.hud) }
          if (listening != null) item { Level(listening.level, c.hudInk, c.hud) }
          // Listening again after an answer, before a word of it: what can be said.
          if (following && listening != null && listening.words.isBlank()) item { Said("Go on, or say thanks", c.dim, 12) }
          else if (question != null) item { Said(question, c.dim, 13) }
          when (asked) {
            is Asking.State.Asked -> asked.progress?.let { item { Said("$it…", c.dim, 12) } }
            is Asking.State.Answered ->
                item {
                  Said(
                      if (asked.notForMe) asked.answer.ifBlank { "Nothing was said to Jarvis." } else asked.answer,
                      if (asked.ok) c.ink else c.danger,
                      15,
                  )
                }
            is Asking.State.Failed -> item { Said(asked.why, c.danger, 13) }
            Asking.State.Idle -> Unit
          }
          (heard as? Ears.State.Failed)?.let { f -> item { Said(f.why.lowercase().replaceFirstChar { it.uppercase() }, c.dim, 12) } }
          trouble?.let { item { Said(it, c.danger, 12) } }
          item { TalkButton(busy = listening != null || asked is Asking.State.Asked || speaking, ink = c.ink, tile = c.tile, line = c.line, onTalk = onTalk) }
          item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              VoiceChip(if (speak) "Voice on" else "Voice off", c.dim, c.line) { onSpeak(!speak) }
              VoiceChip(if (followUps) "Follow-ups on" else "Follow-ups off", c.dim, c.line) { onFollowUps(!followUps) }
            }
          }
          item { Chip("Home", c.ink, c.tile, onHome) }
          item { Chip(if (approvals > 0) "Approvals · $approvals" else "Approvals", if (approvals > 0) c.tintWarm else c.ink, c.tile, onApprovals) }
          item { Said(phoneLine(phone), c.dim, 11) }
        }
      }
    }
  }
}

private fun phoneLine(p: PhoneSays): String =
    when (p) {
      PhoneSays.Checking -> "Finding the phone…"
      PhoneSays.OutOfReach -> "The phone isn't in reach, or hasn't the Jarvis app"
      PhoneSays.SignedOut -> "Sign in to Jarvis in the phone app"
      is PhoneSays.SignedIn -> if (p.name.isBlank()) "Through the phone" else "${p.name}, through the phone"
    }

@Composable
private fun Said(text: String, color: Color, size: Int) {
  Text(text, color = color, fontSize = size.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp))
}

/** How loud, as a row of the panel's dots lit from the middle out. */
@Composable
private fun Level(level: Float, color: Color, ghost: Color) {
  val width = 15
  val lit = (level * width).toInt().coerceIn(1, width)
  val from = (width - lit) / 2
  val row = String(CharArray(width) { if (it in from until from + lit) '#' else '.' })
  Dots(listOf(row), color = color, dot = 2.dp, ghost = ghost)
}

@Composable
private fun TalkButton(busy: Boolean, ink: Color, tile: Color, line: Color, onTalk: () -> Unit) {
  Box(
      Modifier.size(56.dp).clip(CircleShape).background(if (busy) ink else tile).border(1.dp, line, CircleShape).clickable(onClick = onTalk),
      contentAlignment = Alignment.Center,
  ) {
    if (busy) Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(tile))
    else Icon(rememberVectorPainter(Glyphs.Mic), contentDescription = "Talk to Jarvis", tint = ink, modifier = Modifier.size(26.dp))
  }
}

/** A way on to another screen: a wide pill, as the watch's own lists have them. */
@Composable
internal fun Chip(label: String, ink: Color, tile: Color, onClick: () -> Unit) {
  Box(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(50)).background(tile).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Text(label, color = ink, fontSize = 14.sp, maxLines = 1)
  }
}

/** A switch as a small outlined pill: answers spoken, follow-ups listened for. */
@Composable
private fun VoiceChip(label: String, dim: Color, line: Color, onClick: () -> Unit) {
  Row(
      Modifier.clip(RoundedCornerShape(50)).border(1.dp, line, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp),
  ) {
    Text(label, color = dim, fontSize = 11.sp, maxLines = 1)
  }
}
