package app.jarvis.glasses.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The look of the Even Realities app: a soft grey page, plain square tiles with small corners and
 * no shadows, thin line icons, quiet type, and a dot-matrix panel standing in for the glasses'
 * display. Light, and a matching dark.
 */
@Immutable
data class EvenColors(
    val page: Color,
    val tile: Color,
    val tilePressed: Color,
    val chip: Color,
    val ink: Color,
    val dim: Color,
    val line: Color,
    /** The status panel, an old black-and-white LCD: its glass, and its dots. */
    val hud: Color,
    val hudInk: Color,
    val accent: Color,
    val danger: Color,
    val on: Color,
    /** A lit thing's icon, by kind (HomeIcons.Tint), in the Jarvis web app's colours. */
    val tintOpen: Color,
    val tintGood: Color,
    val tintWarm: Color,
)

val LightEven =
    EvenColors(
        page = Color(0xFFEDEDED),
        tile = Color(0xFFFFFFFF),
        tilePressed = Color(0xFFE4E4E4),
        chip = Color(0xFFEDEDED),
        ink = Color(0xFF1B1B1B),
        dim = Color(0xFF8C8C8C),
        line = Color(0xFFDADADA),
        hud = Color(0xFFD5D7D0),
        hudInk = Color(0xFF1C1E1A),
        accent = Color(0xFF1B1B1B),
        danger = Color(0xFFD64535),
        on = Color(0xFF2FA84F),
        tintOpen = Color(0xFF3E6AE1),
        tintGood = Color(0xFF0B7D26),
        // The web's amber is #FBB01B; a thin line of it on white needs it a little deeper.
        tintWarm = Color(0xFFD99100),
    )

val DarkEven =
    EvenColors(
        page = Color(0xFF0E0E0E),
        tile = Color(0xFF1B1B1B),
        tilePressed = Color(0xFF262626),
        chip = Color(0xFF262626),
        ink = Color(0xFFEDEDED),
        dim = Color(0xFF8C8C8C),
        line = Color(0xFF2C2C2C),
        // As light as the tiles, so the panel's edge shows on the near-black page.
        hud = Color(0xFF1B1C1A),
        hudInk = Color(0xFFE2E4DD),
        accent = Color(0xFFEDEDED),
        danger = Color(0xFFE5675A),
        on = Color(0xFF4CC46E),
        tintOpen = Color(0xFF5B84FF),
        tintGood = Color(0xFF12BB00),
        tintWarm = Color(0xFFFBB01B),
    )

val LocalEven = staticCompositionLocalOf { LightEven }

object Even {
  val colors: EvenColors
    @Composable get() = LocalEven.current

  val corner = RoundedCornerShape(6.dp)

  /** The space around and between the Even app's Home tiles. */
  val gutter = 6.dp

  /** Width over height of the Even app's Home tiles (215 × 171 dp on a 448 dp wide phone). */
  const val tileAspect = 215.3f / 171f
}

@Composable
fun EvenTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val c = if (dark) DarkEven else LightEven
  val scheme =
      (if (dark) darkColorScheme() else lightColorScheme()).copy(
          background = c.page,
          surface = c.tile,
          surfaceVariant = c.chip,
          onBackground = c.ink,
          onSurface = c.ink,
          onSurfaceVariant = c.dim,
          primary = c.accent,
          onPrimary = c.tile,
          secondaryContainer = c.chip,
          onSecondaryContainer = c.ink,
          outline = c.line,
          outlineVariant = c.line,
          error = c.danger,
      )
  val type =
      Typography(
          headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp),
          titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal),
          bodyLarge = TextStyle(fontSize = 15.sp),
          bodyMedium = TextStyle(fontSize = 14.sp),
          bodySmall = TextStyle(fontSize = 12.sp),
          labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
          labelSmall = TextStyle(fontSize = 11.sp),
      )
  MaterialTheme(colorScheme = scheme, typography = type) {
    // Text and icons take the page's ink unless told otherwise, in light and dark alike.
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEven provides c,
        LocalHark provides if (dark) DarkHark else LightHark,
        androidx.compose.material3.LocalContentColor provides c.ink,
        content = content,
    )
  }
}

/** A plain Even tile: white (or dark) square with small corners; tappable when [onClick] is set. */
@Composable
fun Tile(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, selected: Boolean = false, content: @Composable BoxScope.() -> Unit) {
  val c = Even.colors
  Box(
      modifier
          .clip(Even.corner)
          .background(if (selected) c.tilePressed else c.tile)
          .let { if (onClick != null) it.clickable(onClick = onClick) else it }
          .padding(16.dp),
      content = content,
  )
}

/** A thin outlined pill, as the Even app's small buttons. */
fun Modifier.outlinePill(color: Color): Modifier = border(BorderStroke(1.dp, color), RoundedCornerShape(50))
