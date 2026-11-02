package app.jarvis.glasses.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

object Hark {
  val colors: HarkColors
    @Composable get() = LocalHark.current

  val bubble = RoundedCornerShape(22.dp)
}
