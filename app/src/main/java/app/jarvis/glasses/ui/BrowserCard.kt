package app.jarvis.glasses.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.jarvis.glasses.chat.BrowserHand
import app.jarvis.glasses.chat.BrowserInput
import app.jarvis.glasses.chat.BrowserScreen
import app.jarvis.glasses.hub.Hub
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The browser Jarvis works in, handed over in Chat ([app.jarvis.glasses.chat.Shown.Browser]), as the
 * web app's card and Browser panel have it (ui/BrowserView.ts and ui/Browser.ts in
 * jarvis-voice-agent): its screen to watch and to press, a box for typing (a phone's keyboard sends
 * no keys to a picture), Back and scrolling, and Full screen with the phone on its side.
 *
 * One card is live at a time, the newest: there is one browser, and each card would show the same
 * screen. What is typed goes to the browser only, never into the conversation.
 */
internal object BrowserCards {
  /** The card that is live, by its key; null once it is closed. */
  var live by mutableStateOf<String?>(null)
    private set

  /** Over everything, the phone on its side. Kept here, not in the card: turning the phone starts the screen again. */
  var full by mutableStateOf(false)

  /** The screen as last fetched, and its picture: shared by the card and full screen, so neither starts blank. */
  var screen by mutableStateOf<BrowserScreen?>(null)
  var frame by mutableStateOf<ImageBitmap?>(null)

  /** Why the screen could not be fetched, in Jarvis's words (no browser set up, or it does not answer). */
  var screenWhy by mutableStateOf<String?>(null)

  /** Why a press or what was typed did not go. */
  var inputWhy by mutableStateOf("")

  /** Fetching stopped after failing; Try again starts it. */
  var gaveUp by mutableStateOf(false)
  var again by mutableIntStateOf(0)

  private val known = HashSet<String>()

  fun knows(key: String) = key in known

  /** A card drawn for the first time takes over from the one before it. */
  fun arrived(key: String) {
    if (known.add(key)) open(key)
  }

  fun open(key: String) {
    live = key
    screenWhy = null
    inputWhy = ""
    gaveUp = false
    again++
  }

  fun close() {
    live = null
    full = false
  }

  /** A card drawn while its answer came ([from]) is now under the answer ([to]): it goes on as it was. */
  fun moved(from: String, to: String) {
    if (known.remove(from)) known.add(to)
    if (live == from) live = to
  }

  /** With the chat: a signed-in page's picture shouldn't outlive it on a shared phone. */
  fun forget() {
    close()
    known.clear()
    screen = null
    frame = null
    screenWhy = null
    inputWhy = ""
  }
}

/** About twice a second, as Jarvis asks; quicker while a finger drags, so a slider is seen to follow it. */
private const val SCREEN_MS = 500L
private const val DRAG_MS = 120L
private const val FAILED_MS = 3_000L

/** A browser that fails this many times in a row is given up on: one that isn't set up won't appear by asking. */
private const val GIVE_UP = 3

/** The picture's shape before the first one comes. */
private const val SHAPE = 16f / 10f

@Composable
internal fun BrowserCard(hub: Hub, key: String) {
  val h = Hark.colors
  LaunchedEffect(key) { BrowserCards.arrived(key) }
  // A card not seen before is about to take over (the effect above): drawn live at once.
  val live = BrowserCards.live == key || !BrowserCards.knows(key)
  Column(
      Modifier.padding(top = 6.dp).fillMaxWidth().clip(Hark.bubble).background(h.bubble).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("Browser", style = MaterialTheme.typography.labelSmall, color = h.dim)
    when {
      !live -> {
        Text("Closed. Open it again here, or ask Jarvis to show the browser.", style = MaterialTheme.typography.bodySmall, color = h.dim)
        Row { HarkButton("Open again", solid = false) { BrowserCards.open(key) } }
      }
      // Full screen has it, and fetches it: not twice.
      BrowserCards.full -> Text("In full screen.", style = MaterialTheme.typography.bodySmall, color = h.dim)
      else -> {
        val hand = rememberHand(hub)
        Watch(hub, hand)
        Where()
        Picture(hand, Modifier.fillMaxWidth().aspectRatio(shape()))
        Status()
        Keys(hand)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HarkButton("Full screen", solid = true) { BrowserCards.full = true }
          HarkButton("Close", solid = false) { BrowserCards.close() }
        }
      }
    }
  }
}

/**
 * Full screen: the picture as large as the phone on its side allows, its controls beside it, over
 * everything. Drawn by Chat itself rather than the card, so it outlasts the card scrolling away and
 * the screen starting again when the phone turns.
 */
@Composable
internal fun BrowserFullScreen(hub: Hub) {
  if (!BrowserCards.full) return
  val activity = LocalContext.current.activity()
  DisposableEffect(activity) {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    // Turning restarts the screen with full screen still on: only leaving it turns the phone back.
    onDispose { if (!BrowserCards.full) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
  }
  val exit = { BrowserCards.full = false }
  Dialog(onDismissRequest = exit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    val h = Hark.colors
    // The status and navigation bars away while it is up: a phone on its side has little height to spare.
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window) {
      val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
      bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      bars?.hide(WindowInsetsCompat.Type.systemBars())
      onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    val hand = rememberHand(hub)
    Watch(hub, hand)
    Row(
        Modifier.fillMaxSize().background(Even.colors.page).windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
        Picture(hand, Modifier.aspectRatio(shape(), matchHeightConstraintsFirst = true))
      }
      // The typing box first: the keyboard covers the bottom of a phone on its side.
      Column(Modifier.width(230.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Keys(hand)
        Where()
        Status()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          HarkButton("Exit full screen", solid = true, onClick = exit)
          HarkButton("Close", solid = false) { BrowserCards.close() }
        }
        Text("What you type goes to the browser only.", style = MaterialTheme.typography.labelSmall, color = h.dim)
      }
    }
  }
}

private fun shape(): Float = BrowserCards.screen?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height } ?: SHAPE

@Composable
private fun rememberHand(hub: Hub): BrowserHand {
  val scope = rememberCoroutineScope()
  return remember(hub) { BrowserHand(scope, hub::browserInput) { BrowserCards.inputWhy = it } }
}

/**
 * The picture, again and again while this is drawn and the app in front (GET /api/browser/screen):
 * each asked for once what was pressed has gone, so it shows what the press did. Nothing is fetched
 * for a card scrolled away, closed, or behind another app.
 */
@Composable
private fun Watch(hub: Hub, hand: BrowserHand) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(hand, BrowserCards.again) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      // Counted afresh each time the app comes back, as a camera's are.
      var failures = 0
      BrowserCards.gaveUp = false
      while (failures < GIVE_UP) {
        hand.flush()
        hand.settled()
        try {
          val s = hub.browserScreen()
          val picture = withContext(Dispatchers.Default) { Base64.getDecoder().decode(s.jpeg).let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() }
          BrowserCards.screen = s
          if (picture != null) BrowserCards.frame = picture
          BrowserCards.screenWhy = if (picture == null) "Jarvis sent no picture of the browser" else null
          failures = 0
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          failures++
          BrowserCards.screenWhy = e.message ?: "The browser's screen didn't come"
        }
        delay(if (failures > 0) FAILED_MS else if (hand.held) DRAG_MS else SCREEN_MS)
      }
      BrowserCards.gaveUp = true
    }
  }
}

/** The page's title, or its address, above the picture. */
@Composable
private fun Where() {
  val heading = BrowserCards.screen?.heading.orEmpty()
  if (heading.isNotEmpty()) Text(heading, style = MaterialTheme.typography.bodyMedium, color = Hark.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** What went wrong, in Jarvis's words, and Try again once fetching has stopped. */
@Composable
private fun Status() {
  val why = BrowserCards.screenWhy ?: BrowserCards.inputWhy.ifEmpty { null }
  if (why == null && BrowserCards.frame != null) return
  Text(why ?: "Getting the browser's screen…", style = MaterialTheme.typography.bodySmall, color = if (why != null) Even.colors.danger else Hark.colors.dim)
  if (BrowserCards.gaveUp) Row { HarkButton("Try again", solid = false) { BrowserCards.screenWhy = null; BrowserCards.again++ } }
}

/**
 * The browser's screen, pressed as it is touched: a finger down, its moves and its lifting go to
 * the browser as the mouse, at the place on the browser's own screen under the finger
 * ([BrowserScreen.at]). The touch is this picture's alone, so a drag moves the page's slider and
 * not the conversation; a second finger lets go.
 */
@Composable
private fun Picture(hand: BrowserHand, modifier: Modifier) {
  val frame = BrowserCards.frame
  Box(
      modifier.clip(RoundedCornerShape(10.dp)).background(Hark.colors.bubbleYou).pointerInput(hand) {
        // The screen as it is when touched, not as it was when this began.
        fun at(p: Offset) = BrowserCards.screen?.at(p.x, p.y, size.width, size.height)
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val first = at(down.position) ?: return@awaitEachGesture
          down.consume()
          hand.down(first)
          try {
            while (true) {
              val event = awaitPointerEvent()
              val mine = event.changes.firstOrNull { it.id == down.id }
              event.changes.forEach { it.consume() }
              if (mine == null || event.changes.size > 1) {
                hand.cancel()
                break
              }
              if (!mine.pressed) {
                hand.up(at(mine.position))
                break
              }
              at(mine.position)?.let(hand::move)
            }
          } finally {
            // The picture went from under the finger (scrolled off, closed).
            hand.cancel()
          }
        }
      },
  ) {
    if (frame != null) Image(frame, contentDescription = "The browser's screen", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
  }
}

/**
 * Typing and moving about: a box whose words go to the page on Send (a phone has no keys to press
 * on a picture), Enter and Backspace as keys, Back, and the wheel up and down. Hidden as typed
 * until Show, as the web's box is: it is often a password.
 */
@Composable
private fun Keys(hand: BrowserHand) {
  val h = Hark.colors
  var text by remember { mutableStateOf("") }
  var show by remember { mutableStateOf(false) }
  val type = {
    if (text.isNotEmpty()) {
      hand.send(listOf(BrowserInput.text(text)))
      text = ""
    }
  }
  val wheel = { dy: Int -> BrowserCards.screen?.let { hand.send(listOf(BrowserInput.wheel(it.middle, dy))) } }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(h.bubbleYou).padding(horizontal = 14.dp, vertical = 10.dp)) {
      if (text.isEmpty()) Text("Type here, then Send", style = MaterialTheme.typography.bodyMedium, color = h.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
      BasicTextField(
          text,
          { text = it },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(color = h.ink),
          cursorBrush = SolidColor(h.ink),
          visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
          // Never learnt by the keyboard, shown or not.
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Send),
          // The keyboard's own Send is the page's Enter too, as on the web.
          keyboardActions = KeyboardActions(onSend = { type(); hand.send(listOf(BrowserInput.key(BrowserInput.ENTER))) }),
          modifier = Modifier.fillMaxWidth(),
      )
    }
    HarkButton("Send", solid = true, onClick = type)
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    HarkButton(if (show) "Hide" else "Show", solid = false) { show = !show }
    HarkButton("Enter", solid = false) { hand.send(listOf(BrowserInput.key(BrowserInput.ENTER))) }
    HarkButton("Backspace", solid = false) { hand.send(listOf(BrowserInput.key(BrowserInput.BACKSPACE))) }
    HarkButton("Back", solid = false) { hand.send(listOf(BrowserInput.back)) }
    HarkButton("Up", solid = false) { wheel(-BrowserInput.WHEEL) }
    HarkButton("Down", solid = false) { wheel(BrowserInput.WHEEL) }
  }
}

private fun Context.activity(): Activity? {
  var c: Context? = this
  while (c is ContextWrapper) {
    if (c is Activity) return c
    c = c.baseContext
  }
  return null
}
