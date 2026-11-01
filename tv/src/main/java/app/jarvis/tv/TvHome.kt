package app.jarvis.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.DotMatrix
import app.jarvis.glasses.ui.Dots
import app.jarvis.glasses.ui.Even
import app.jarvis.glasses.ui.Glyphs
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The TV's home: the phone's LCD status panel across the top, with the time in dots and what
 * Jarvis hears, then the apps as Even tiles, moved through with the remote. Talk comes first.
 */
@Composable
fun TvHome(apps: List<AppTile>, voice: Voice.State, note: String, onTalk: () -> Unit, onOpen: (TvApp) -> Unit, onSettings: () -> Unit) {
  val c = Even.colors
  val talk = remember { FocusRequester() }
  LaunchedEffect(Unit) { talk.requestFocus() }
  val favourites = apps.filter { it.app.pkg in FAVOURITES }
  val rest = apps.filter { it.app.pkg !in FAVOURITES }
  // A TV draws a little past its edges: keep everything inside the safe area.
  Column(Modifier.fillMaxSize().background(c.page).padding(horizontal = 48.dp, vertical = 27.dp)) {
    StatusLcd(voice, note)
    Spacer(Modifier.height(18.dp))
    Shelf("Apps") {
      item { TalkTile(voice is Voice.State.Listening, onTalk, Modifier.focusRequester(talk)) }
      items(favourites, key = { it.app.pkg }) { AppTileView(it) { onOpen(it.app) } }
    }
    // The TV's own Settings isn't one of its apps: with Jarvis as home, this is the way in.
    Shelf("More") {
      item { IconTile(COG, "Settings", onSettings) }
      items(rest, key = { it.app.pkg }) { AppTileView(it) { onOpen(it.app) } }
    }
  }
}

@Composable
private fun StatusLcd(voice: Voice.State, note: String) {
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
        is Voice.State.Listening -> "LISTENING" to voice.words
        is Voice.State.Failed -> voice.why to ""
        Voice.State.Idle -> (if (note.isEmpty()) "READY" else "") to note
      }
  Row(Modifier.fillMaxWidth().height(150.dp).clip(Even.corner).background(c.hud).padding(20.dp)) {
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
      Lcd(time.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(), ink)
      DotMatrix(time.format(DateTimeFormatter.ofPattern("HH:mm")), color = ink, dot = 5.dp, ghost = ghost)
      Lcd(if (state.isEmpty()) "JARVIS" else "JARVIS: $state", ink)
    }
    Spacer(Modifier.width(40.dp))
    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.SpaceBetween) {
      Text(
          words.ifEmpty { if (voice == Voice.State.Idle && note.isEmpty()) "Press the mic and talk" else "" },
          style = TextStyle(fontSize = 22.sp, color = if (words.isEmpty()) ink.copy(alpha = 0.45f) else ink),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
      )
      val level = (voice as? Voice.State.Listening)?.level
      Dots(levelDots(level ?: 0f), if (level == null) ink.copy(alpha = 0.2f) else ink, dot = 2.5.dp, ghost = ghost)
    }
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

@Composable
private fun Shelf(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
  Text(title, style = TextStyle(fontSize = 16.sp, color = Even.colors.dim), modifier = Modifier.padding(top = 6.dp))
  // Room above and below for a chosen tile to grow into.
  LazyRow(contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), content = content)
}

/** An Even tile the remote can move to: it grows a little and gets a thin ring when chosen. */
@Composable
private fun FocusTile(onClick: () -> Unit, modifier: Modifier = Modifier, fill: Color = Even.colors.tile, content: @Composable () -> Unit) {
  var focused by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "focus")
  Box(
      modifier
          .size(TILE_W, TILE_H)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .clip(Even.corner)
          .background(fill)
          .border(BorderStroke(if (focused) 3.dp else 0.dp, if (focused) Even.colors.ink else Color.Transparent), Even.corner)
          .onFocusChanged { focused = it.isFocused }
          .clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun TalkTile(listening: Boolean, onTalk: () -> Unit, modifier: Modifier) {
  val c = Even.colors
  FocusTile(onTalk, modifier, fill = c.hud) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(Glyphs.Mic, null, tint = c.hudInk, modifier = Modifier.size(30.dp))
      Text(if (listening) "Listening" else "Talk", style = TextStyle(fontSize = 20.sp, color = c.hudInk))
    }
  }
}

@Composable
private fun IconTile(icon: ImageVector, label: String, onClick: () -> Unit) {
  FocusTile(onClick) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(icon, null, modifier = Modifier.size(30.dp))
      Text(label, style = TextStyle(fontSize = 20.sp))
    }
  }
}

@Composable
private fun AppTileView(tile: AppTile, onOpen: () -> Unit) {
  FocusTile(onOpen) {
    when {
      tile.banner != null -> Image(tile.banner, tile.app.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      else ->
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tile.icon?.let { Image(it, null, Modifier.size(44.dp)) }
            Text(tile.app.label, style = TextStyle(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
          }
    }
  }
}

private val TILE_W = 192.dp
private val TILE_H = 108.dp

/** A cog, in the line style of the phone's icons. */
private val COG: ImageVector =
    ImageVector.Builder("cog", 24.dp, 24.dp, 24f, 24f)
        .addPath(
            addPathNodes(
                "M10.3 4.3c.4-1.8 3-1.8 3.4 0a1.7 1.7 0 0 0 2.6 1.1c1.5-.9 3.3.8 2.4 2.4a1.7 1.7 0 0 0 1 2.5c1.8.4 1.8 3 0 3.4" +
                    "a1.7 1.7 0 0 0-1 2.6c.9 1.5-.8 3.3-2.4 2.4a1.7 1.7 0 0 0-2.6 1c-.4 1.8-3 1.8-3.4 0a1.7 1.7 0 0 0-2.5-1" +
                    "c-1.6.9-3.3-.9-2.4-2.4a1.7 1.7 0 0 0-1.1-2.6c-1.8-.4-1.8-3 0-3.4a1.7 1.7 0 0 0 1.1-2.5c-.9-1.6.8-3.3 2.4-2.4" +
                    "a1.7 1.7 0 0 0 2.5-1.1z M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z",
            ),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
        .build()
