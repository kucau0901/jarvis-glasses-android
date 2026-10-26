package app.jarvis.glasses.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.hub.Link

/**
 * The Hark Pro app's chat, which the Jarvis web app's look comes from: a soft sky behind
 * everything, Jarvis in solid round bubbles on the left, you in glass ones on the right, and a
 * floating "Message" pill at the bottom. Colours follow the
 * web app's (index.html), light and dark. Used where people talk; the rest of the app stays Even.
 */
@Immutable
data class HarkColors(
    val sky: List<Color>,
    val glow: Color,
    val bubble: Color,
    val bubbleYou: Color,
    val glass: Color,
    val solid: Color,
    val onSolid: Color,
    val ink: Color,
    val dim: Color,
)

val LightHark =
    HarkColors(
        sky = listOf(Color(0xFF9DBBE3), Color(0xFFC6D6EC), Color(0xFFE4EAF3)),
        glow = Color(0x73FFFFFF),
        bubble = Color(0xFAFFFFFF),
        bubbleYou = Color(0x8CFFFFFF),
        glass = Color(0xD9FFFFFF),
        solid = Color(0xFF171A20),
        onSolid = Color(0xFFFFFFFF),
        ink = Color(0xFF171A20),
        dim = Color(0xFF5C5E62),
    )

val DarkHark =
    HarkColors(
        sky = listOf(Color(0xFF0E1A33), Color(0xFF070B14), Color(0xFF000000)),
        glow = Color(0x383E6AE1),
        bubble = Color(0xEB393C41),
        bubbleYou = Color(0x1FFFFFFF),
        glass = Color(0xE6222222),
        solid = Color(0xFFEEEEEE),
        onSolid = Color(0xFF171A20),
        ink = Color(0xFFEEEEEE),
        dim = Color(0xFFA2A3A5),
    )

val LocalHark = staticCompositionLocalOf { LightHark }

/** Whether the page behind the shell shows the sky; set by [SkyBehind] while a Hark screen is up. */
val LocalSky = staticCompositionLocalOf<MutableState<Boolean>> { mutableStateOf(false) }

object Hark {
  val colors: HarkColors
    @Composable get() = LocalHark.current

  val bubble = RoundedCornerShape(22.dp)
}

/** Paints the sky: Hark's vertical wash with a soft light in the upper right. */
fun Modifier.sky(h: HarkColors): Modifier =
    background(Brush.verticalGradient(h.sky))
        .background(Brush.radialGradient(listOf(h.glow, Color.Transparent), center = Offset(900f, 500f), radius = 1400f))

/** Asks the shell to put the sky behind the whole page, status bar included, while this screen shows. */
@Composable
fun SkyBehind() {
  val on = LocalSky.current
  DisposableEffect(on) {
    on.value = true
    onDispose { on.value = false }
  }
}

/** One message: [mine] on the right in glass, anyone else on the left, solid. */
@Composable
fun HarkBubble(mine: Boolean, content: @Composable () -> Unit) {
  val h = Hark.colors
  Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
    Column(
        Modifier.widthIn(max = 310.dp).clip(Hark.bubble).background(if (mine) h.bubbleYou else h.bubble).padding(horizontal = 16.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      content()
    }
  }
}

/** A link Jarvis gave, as a small card under its answer: the site's initial, title and address. */
@Composable
fun LinkCard(link: Link) {
  val h = Hark.colors
  val ctx = LocalContext.current
  val host = runCatching { Uri.parse(link.url).host }.getOrNull().orEmpty().removePrefix("www.")
  Row(
      Modifier.widthIn(max = 280.dp)
          .clip(RoundedCornerShape(18.dp))
          .background(h.bubble)
          .clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
          .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(h.bubbleYou), contentAlignment = Alignment.Center) {
      Text(host.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = h.ink)
    }
    Column {
      Text(link.title.ifEmpty { host }, style = MaterialTheme.typography.bodyMedium, color = h.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (host.isNotEmpty()) Text(host, style = MaterialTheme.typography.bodySmall, color = h.dim, maxLines = 1)
    }
  }
}

/** A pill button: solid for the main choice, glass for the other. */
@Composable
fun HarkButton(label: String, solid: Boolean, onClick: () -> Unit) {
  val h = Hark.colors
  Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = if (solid) h.onSolid else h.ink,
      modifier = Modifier.clip(CircleShape).background(if (solid) h.solid else h.bubbleYou).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 9.dp),
  )
}

/**
 * The floating message pill. With [lead] it shows a + on the left; with [mic], a mic on the right
 * while nothing is typed, which turns into a send arrow once there is text.
 */
@Composable
fun MessagePill(
    text: String,
    onText: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    lead: Pair<ImageVector, () -> Unit>? = null,
    leadOn: Boolean = false,
    mic: (() -> Unit)? = null,
) {
  val h = Hark.colors
  Row(
      modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(h.glass).padding(start = if (lead != null) 6.dp else 20.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    lead?.let { (icon, onLead) -> PillIcon(icon, "Add a photo", if (leadOn) Even.colors.on else h.dim, onLead) }
    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
      if (text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = h.dim)
      BasicTextField(text, onText, textStyle = MaterialTheme.typography.bodyLarge.copy(color = h.ink), cursorBrush = SolidColor(h.ink), maxLines = 5, modifier = Modifier.fillMaxWidth())
    }
    when {
      text.isNotBlank() -> PillIcon(Glyphs.Send, "Send", if (canSend) h.ink else h.dim) { if (canSend) onSend() }
      mic != null -> PillIcon(Glyphs.Mic, "Talk in the glasses", h.dim, mic)
    }
  }
}

@Composable
private fun PillIcon(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
  Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
  }
}
