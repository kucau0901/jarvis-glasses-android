package app.jarvis.tv

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.jarvis.glasses.ui.EvenTheme
import app.jarvis.glasses.ui.Glyphs
import app.jarvis.tv.jarvis.Panel
import app.jarvis.tv.Voice.Companion.TAG
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Jarvis on the TV: the home screen, and talking. The remote's mic button (or the Talk tile)
 * listens once; "open Netflix" opens it here and now, anything else is for Jarvis.
 */
class MainActivity : ComponentActivity() {
  private lateinit var voice: Voice
  private var apps by mutableStateOf(emptyList<AppTile>())
  private var note by mutableStateOf("")

  /** What's on screen: Home, or a page over it that Back closes. */
  private sealed interface Screen {
    data object Home : Screen

    data object Drawer : Screen

    data object SignIn : Screen

    /** A thing's page. A car's tiles all carry the car's key, so its place in the panel says which. */
    data class Thing(val panel: String, val key: String, val index: Int) : Screen

    data class Notes(val panel: String) : Screen
  }

  private var screen by mutableStateOf<Screen>(Screen.Home)

  /** What the next words heard are for: Jarvis or an app, a new tile on a panel, a new panel, or a to-do. */
  private sealed interface Purpose {
    data object Ask : Purpose

    data class Add(val panel: Panel) : Purpose

    data object NewPanel : Purpose

    data class Note(val panel: Panel) : Purpose
  }

  private var purpose: Purpose = Purpose.Ask

  private lateinit var tv: TvJarvis

  /** Whether the recogniser service refused this app, so the system's own listening screen is used. */
  private var useSystemListener = false

  /** What to listen for once the mic is allowed: the first press asks for it. */
  private var afterMic: Pair<Purpose, String>? = null

  private val askMic =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val then = afterMic
        afterMic = null
        if (!ok) note = "Jarvis needs the mic to hear you" else if (then != null) listen(then.first, then.second)
      }

  /** OK is held down, and a page opened under it must not take its release (or repeats) as a press. */
  private var okHeld = false
  private var swallowOk = false

  private val systemListener =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val words = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        Log.i(TAG, "system listener heard \"$words\"")
        if (!words.isNullOrBlank()) heard(words)
      }

  /** The mic button was pressed (MicButton) while another app was in front: listen once this screen is up. */
  private var talkOnResume = false

  private val packagesChanged =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    voice = Voice(this, heard = ::heard, refused = ::refused)
    tv = TvJarvis(this, lifecycleScope)
    talkOnResume = pressedJustNow(intent)
    loadApps()
    registerReceiver(
        packagesChanged,
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addAction(Intent.ACTION_PACKAGE_CHANGED)
          addDataScheme("package")
        },
    )
    // Home has nowhere to go back to: Back closes the page over it, or stops listening and Jarvis talking.
    onBackPressedDispatcher.addCallback(this) {
      if (screen == Screen.SignIn) tv.stopPairing()
      if (screen != Screen.Home) {
        screen = Screen.Home
      } else {
        voice.stop()
        tv.quiet()
        note = ""
      }
    }
    setContent {
      val state by voice.state.collectAsStateWithLifecycle()
      // The Even Realities app's look is its light one.
      EvenTheme(dark = false) { Screens(state) }
    }
  }

  @Composable
  private fun Screens(state: Voice.State) {
    Box(Modifier.fillMaxSize()) {
      Page(state)
      (screen as? Screen.Thing)?.let { Sheet(it) }
      // Away from Home, the panel isn't there: what's heard and what happened shows in a strip instead.
      if (screen != Screen.Home) {
        val line =
            when (state) {
              is Voice.State.Listening -> "LISTENING  " + state.words.ifEmpty { note }
              is Voice.State.Failed -> state.why
              Voice.State.Idle -> note.ifEmpty { tv.notice }.ifEmpty { tv.working }
            }
        StatusStrip(line, Modifier.align(if (screen is Screen.Thing) Alignment.TopCenter else Alignment.BottomCenter))
      }
    }
  }

  @Composable
  private fun Page(state: Voice.State) {
    when (val s = screen) {
      // A thing's sheet rises over Home, which stays behind it as on the phone.
      Screen.Home,
      is Screen.Thing ->
          TvHome(
              apps.map { it.app.pkg }.toSet(),
              state,
              note.ifEmpty { tv.notice },
              onTalk = ::talk,
              onOpen = ::open,
              onDrawer = { screen = Screen.Drawer },
              answer = { AnswerCard(tv.heard, tv.working, tv.answer, tv.picture) },
          ) {
            if (tv.signedIn) {
              jarvisPanels(
                  tv.panels,
                  tv.readings,
                  tv.busy.keys.toSet(),
                  // OK opens the thing, as a tap does on the phone: its buttons are in the sheet, so nothing works by accident.
                  onPress = { p, r -> screen = if (r.kind == "note") Screen.Notes(p.id) else Screen.Thing(p.id, r.key, p.rows.indexOf(r)) },
                  onMore = { p, r ->
                    if (okHeld) swallowOk = true
                    screen = if (r.kind == "note") Screen.Notes(p.id) else Screen.Thing(p.id, r.key, p.rows.indexOf(r))
                  },
                  onAdd = { p -> listen(Purpose.Add(p), "Say what to add to ${p.title}") },
                  onNewPanel = { listen(Purpose.NewPanel, "Say what the new panel should have") },
              )
            } else {
              item { Title("Jarvis") }
              item {
                IconTile(Glyphs.Chat, "Sign in", Modifier.fillMaxWidth(1f / 6)) {
                  screen = Screen.SignIn
                  tv.pair()
                }
              }
            }
          }
      Screen.Drawer -> AppDrawer(apps.filter { a -> HOME_APPS.none { it.pkg == a.app.pkg } }, onOpen = ::open, onSettings = ::openSettings)
      Screen.SignIn -> {
        PairScreen(tv.pairCode, if (tv.signedIn) tv.prefs.name else null, tv.pairError, onRetry = tv::pair)
        LaunchedEffect(tv.signedIn) {
          if (tv.signedIn) {
            delay(2_000)
            screen = Screen.Home
          }
        }
      }
      is Screen.Notes -> {
        val panel = tv.panels.firstOrNull { it.id == s.panel }
        if (panel == null) {
          LaunchedEffect(Unit) { screen = Screen.Home }
        } else {
          NotesPage(
              panel,
              onTick = { key -> tv.note(panel, tick = key) },
              onAdd = { listen(Purpose.Note(panel), "Say the to-do") },
              onClear = { tv.note(panel, clear = true) },
          )
        }
      }
    }
  }

  /** A thing's controls in a sheet from the bottom, over Home dimmed behind. */
  @Composable
  private fun BoxScope.Sheet(s: Screen.Thing) {
    val panel = tv.panels.firstOrNull { it.id == s.panel }
    val row = panel?.rows?.getOrNull(s.index)?.takeIf { it.key == s.key } ?: panel?.rows?.firstOrNull { it.key == s.key }
    if (panel == null || row == null) {
      LaunchedEffect(Unit) { screen = Screen.Home }
    } else {
      Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
      ThingDetail(
          panel,
          row,
          tv.readings[thingKey(panel, row)],
          tv.busy[thingKey(panel, row)] == true,
          onAct = { action, member, value -> tv.act(panel, row, action, member, value) },
          onRemoveTile = {
            tv.removeTile(panel, row)
            screen = Screen.Home
          },
          onRemovePanel = {
            tv.removePanel(panel)
            screen = Screen.Home
          },
          onClose = { screen = Screen.Home },
          // The remote stays in the sheet: Back or Close leaves it, not moving up past its top.
          Modifier.align(Alignment.BottomCenter).focusGroup().focusProperties { onExit = { cancelFocusChange() } },
      )
    }
  }

  override fun onDestroy() {
    unregisterReceiver(packagesChanged)
    voice.stop()
    tv.release()
    super.onDestroy()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    // Before Android 10 the screen is always paused first, so onResume follows.
    if (pressedJustNow(intent)) talkOnResume = true
  }

  override fun onResume() {
    super.onResume()
    front = WeakReference(this)
    tv.startPolling()
    if (talkOnResume) {
      talkOnResume = false
      talk()
    }
  }

  override fun onPause() {
    if (front?.get() === this) front = null
    voice.stop()
    tv.stopPolling()
    super.onPause()
  }

  /** The apps, read away from the main thread: their banners take a while to draw on this TV. */
  private fun loadApps() {
    lifecycleScope.launch { apps = withContext(Dispatchers.Default) { installedApps(packageManager, packageName) } }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode in OK_KEYS) {
      if (event.action == KeyEvent.ACTION_DOWN) okHeld = true
      if (event.action == KeyEvent.ACTION_UP) okHeld = false
      if (swallowOk) {
        if (event.action == KeyEvent.ACTION_UP) swallowOk = false
        return true
      }
    }
    return super.dispatchKeyEvent(event)
  }

  // Without MicButton switched on, the mic button never gets here; the Talk tile and a search key do.
  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (keyCode in MicButton.MIC_KEYS) {
      if (event.repeatCount == 0) talk()
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  /** Listens; pressed again while listening, finishes and acts on what was said so far. */
  fun talk() = listen(Purpose.Ask)

  /** Listens for [why], with [prompt] on the panel until words come. Jarvis stops talking first. */
  private fun listen(why: Purpose, prompt: String = "") {
    if (voice.state.value is Voice.State.Listening) return voice.finish()
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      afterMic = why to prompt
      return askMic.launch(Manifest.permission.RECORD_AUDIO)
    }
    purpose = why
    tv.interrupt()
    note = prompt
    if (useSystemListener || !voice.available) return listenWithSystem()
    voice.listen()
  }

  private fun listenWithSystem() {
    val ask =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Jarvis")
    try {
      systemListener.launch(ask)
    } catch (e: ActivityNotFoundException) {
      note = "This TV has nothing to listen with"
    }
  }

  private fun heard(words: String) {
    val why = purpose
    purpose = Purpose.Ask
    note = ""
    when (why) {
      is Purpose.Add -> tv.ask("Add this to my \u201C${why.panel.title}\u201D Home panel: $words", shown = words)
      Purpose.NewPanel -> tv.ask("Make me a new Home panel: $words", shown = words)
      is Purpose.Note -> tv.note(why.panel, add = words)
      Purpose.Ask -> {
        val app = appAsked(words, askable(apps))
        if (app != null) open(app.pkg) else tv.ask(words)
      }
    }
  }

  private fun open(pkg: String) {
    val name = HOME_APPS.firstOrNull { it.pkg == pkg }?.name ?: apps.firstOrNull { it.app.pkg == pkg }?.app?.label ?: pkg
    val go = launchIntent(packageManager, pkg) ?: return run { note = "Can't open $name" }
    note = "Opening $name"
    screen = Screen.Home
    startActivity(go)
  }

  private fun openSettings() {
    try {
      startActivity(Intent(Settings.ACTION_SETTINGS))
    } catch (e: ActivityNotFoundException) {
      note = "Can't open Settings"
    }
  }

  /** The recogniser refused this app outright, and won't change its mind: use the system's screen. */
  private fun refused() {
    useSystemListener = true
    listenWithSystem()
  }

  companion object {
    private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    /** Opens Jarvis listening: the mic button, from anywhere on the TV. */
    const val TALK = "app.jarvis.tv.TALK"

    /** When the mic button was pressed, in SystemClock.elapsedRealtime. */
    const val PRESSED_AT = "app.jarvis.tv.PRESSED_AT"

    /** This screen while it's in front, so the mic button talks to it rather than starting it again. */
    var front: WeakReference<MainActivity>? = null
      private set

    /**
     * Whether [intent] is a mic press from just now. Android keeps the intent that first started
     * the screen and uses it again if the screen is remade later; that one mustn't listen again.
     */
    private fun pressedJustNow(intent: Intent?): Boolean =
        intent?.action == TALK && SystemClock.elapsedRealtime() - intent.getLongExtra(PRESSED_AT, 0) < 5_000
  }
}
