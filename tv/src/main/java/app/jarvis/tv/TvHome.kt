package app.jarvis.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.DotMatrix
import app.jarvis.glasses.ui.Dots
import app.jarvis.glasses.ui.Even
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The TV's home, in the Even Realities app's look: the phone's LCD status panel across the top,
 * which is also the Talk button, then Home's apps as plain Even tiles with one-line icons, and the
 * drawer for the rest. [answer] sits under the panel (Jarvis's answer); [more] adds rows below (Jarvis's panels).
 */
@Composable
fun TvHome(
    installed: Set<String>,
    voice: Voice.State,
    note: String,
    onTalk: () -> Unit,
    onOpen: (pkg: String) -> Unit,
    onDrawer: () -> Unit,
    answer: @Composable () -> Unit = {},
    more: LazyListScope.() -> Unit = {},
) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { first.requestFocus() }
  Page {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
      item { StatusLcd(voice, note, onTalk, Modifier.focusRequester(first)) }
      item { answer() }
      item { Title("Watch") }
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(Even.gutter * 2)) {
          for (app in HOME_APPS.filter { it.pkg in installed }) {
            IconTile(app.icon, app.name, Modifier.weight(1f)) { onOpen(app.pkg) }
          }
          IconTile(AppIcons.Drawer, "All apps", Modifier.weight(1f), onClick = onDrawer)
        }
      }
      more()
    }
  }
}

/** The apps that aren't on Home, Settings first, their own icons in grey so they sit with the rest. */
@Composable
fun AppDrawer(apps: List<AppTile>, onOpen: (pkg: String) -> Unit, onSettings: () -> Unit) {
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { first.requestFocus() }
  val grey = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
  Page {
    Title("All apps")
    LazyVerticalGrid(
        GridCells.Fixed(6),
        horizontalArrangement = Arrangement.spacedBy(Even.gutter * 2),
        verticalArrangement = Arrangement.spacedBy(Even.gutter * 2),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
      item { IconTile(AppIcons.Settings, "Settings", Modifier.focusRequester(first), onClick = onSettings) }
      items(apps, key = { it.app.pkg }) { tile ->
        FocusTile({ onOpen(tile.app.pkg) }) {
          tile.icon?.let { Image(it, null, Modifier.size(34.dp).align(Alignment.TopStart).clip(Even.corner), colorFilter = grey) }
          Label(tile.app.label, Modifier.align(Alignment.BottomStart))
        }
      }
    }
  }
}

/** The page: Even's soft grey, inside the TV's safe area (a TV draws a little past its edges). */
@Composable
private fun Page(content: @Composable () -> Unit) {
  Column(Modifier.fillMaxSize().background(Even.colors.page).padding(horizontal = 48.dp, vertical = 27.dp)) { content() }
}

@Composable
fun Title(text: String) {
  Text(text, style = TextStyle(fontSize = 19.sp, color = Even.colors.ink), modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp))
}

/**
 * The phone's status panel, an old LCD: the date, the time in dots and what Jarvis is doing, and
 * on the right the words it hears with a loudness bar. Pressing it talks.
 */
@Composable
private fun StatusLcd(voice: Voice.State, note: String, onTalk: () -> Unit, modifier: Modifier) {
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
  val (state, words) =
      when (voice) {
        is Voice.State.Listening -> "LISTENING" to voice.words.ifEmpty { note }
        is Voice.State.Failed -> voice.why to ""
        Voice.State.Idle -> (if (note.isEmpty()) "READY" else "") to note
      }
  Focusable(onTalk, modifier, fill = c.hud, height = 150.dp, grow = 1.01f) {
    Row(Modifier.fillMaxSize().padding(4.dp)) {
      Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
        Lcd(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(), ink)
        DotMatrix(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = ink, dot = 5.dp, ghost = ghost)
        Lcd(if (state.isEmpty()) "JARVIS" else "JARVIS: $state", ink)
      }
      Spacer(Modifier.width(40.dp))
      Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
        Text(
            words.ifEmpty { if (voice == Voice.State.Idle && note.isEmpty()) "Press the mic, or OK here, and talk" else "" },
            style = TextStyle(fontSize = 21.sp, color = if (words.isEmpty()) ink.copy(alpha = 0.45f) else ink),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val level = (voice as? Voice.State.Listening)?.level
        Dots(levelDots(level ?: 0f), if (level == null) ink.copy(alpha = 0.2f) else ink, dot = 2.5.dp, ghost = ghost)
      }
    }
  }
}

/** The panel's news on a page that hasn't the panel: one line on its LCD glass at the bottom. Nothing when [text] is empty. */
@Composable
fun StatusStrip(text: String, modifier: Modifier = Modifier) {
  if (text.isEmpty()) return
  val c = Even.colors
  Box(modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 20.dp).clip(Even.corner).background(c.hud).padding(horizontal = 20.dp, vertical = 14.dp)) {
    Text(text, style = TextStyle(fontSize = 19.sp, color = c.hudInk), maxLines = 2, overflow = TextOverflow.Ellipsis)
  }
}

/** How loud, as a bar of dots three high, lit from the left. */
private fun levelDots(level: Float): List<String> {
  val lit = (level * LEVEL_DOTS).roundToInt()
  return List(3) { "#".repeat(lit).padEnd(LEVEL_DOTS, '.') }
}

private const val LEVEL_DOTS = 40

@Composable
private fun Lcd(text: String, ink: Color) {
  Text(text, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, letterSpacing = 1.5.sp, color = ink), maxLines = 1)
}

/** The phone's QuickTile: an icon at the top, the name at the bottom. */
@Composable
fun IconTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  FocusTile(onClick, modifier) {
    Icon(icon, null, tint = Even.colors.ink, modifier = Modifier.size(30.dp).align(Alignment.TopStart))
    Label(label, Modifier.align(Alignment.BottomStart))
  }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) {
  Text(text, style = TextStyle(fontSize = 17.sp, color = Even.colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** A plain Even tile the remote can move to, in Even's proportions. */
@Composable
fun FocusTile(onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null, content: @Composable BoxScope.() -> Unit) {
  Focusable(onClick, modifier.fillMaxWidth(), aspect = Even.tileAspect, onLongClick = onLongClick, content = content)
}

/**
 * What the remote moves between: flat, with Even's small corners and no shadow; the chosen one
 * grows a little and gets a ring in ink.
 */
@Composable
private fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier,
    fill: Color = Even.colors.tile,
    height: Dp? = null,
    aspect: Float? = null,
    grow: Float = 1.05f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
  var focused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(if (focused) grow else 1f, label = "focus")
  Box(
      modifier
          .let { m -> height?.let { m.fillMaxWidth().height(it) } ?: aspect?.let { m.aspectRatio(it) } ?: m }
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clip(Even.corner)
          .background(fill)
          .border(BorderStroke(2.5.dp, if (focused) Even.colors.ink else Color.Transparent), Even.corner)
          .onFocusChanged { focused = it.isFocused }
          .let { if (onLongClick != null) it.combinedClickable(onLongClick = onLongClick, onClick = onClick) else it.clickable(onClick = onClick) }
          .padding(16.dp),
      content = content,
  )
}
