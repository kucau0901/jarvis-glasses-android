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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.jarvis.glasses.ui.EvenTheme
import app.jarvis.tv.Voice.Companion.TAG
import java.lang.ref.WeakReference
import kotlinx.coroutines.Dispatchers
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

  /** Whether the recogniser service refused this app, so the system's own listening screen is used. */
  private var useSystemListener = false

  private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) talk() else note = "Jarvis needs the mic to hear you" }

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
    // The home screen has nowhere to go back to: Back only stops listening and clears the panel.
    onBackPressedDispatcher.addCallback(this) {
      voice.stop()
      note = ""
    }
    setContent {
      val state by voice.state.collectAsStateWithLifecycle()
      EvenTheme(dark = true) { TvHome(apps, state, note, onTalk = ::talk, onOpen = ::open, onSettings = ::openSettings) }
    }
  }

  override fun onDestroy() {
    unregisterReceiver(packagesChanged)
    voice.stop()
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
    if (talkOnResume) {
      talkOnResume = false
      talk()
    }
  }

  override fun onPause() {
    if (front?.get() === this) front = null
    voice.stop()
    super.onPause()
  }

  /** The apps, read away from the main thread: their banners take a while to draw on this TV. */
  private fun loadApps() {
    lifecycleScope.launch { apps = withContext(Dispatchers.Default) { installedApps(packageManager, packageName) } }
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
  fun talk() {
    if (voice.state.value is Voice.State.Listening) return voice.finish()
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return askMic.launch(Manifest.permission.RECORD_AUDIO)
    note = ""
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
    val app = appAsked(words, apps.map { it.app })
    if (app != null) return open(app)
    // Jarvis's part comes next: until this TV is paired, say what was heard.
    note = "“$words” — Jarvis isn't connected yet"
  }

  private fun open(app: TvApp) {
    val go = launchIntent(packageManager, app.pkg) ?: return run { note = "Can't open ${app.label}" }
    note = "Opening ${app.label}"
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
