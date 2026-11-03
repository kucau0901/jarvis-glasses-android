package app.jarvis.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Jarvis on the watch: opening it starts listening at once, from its icon, the Home key's double
 * press, or as the watch's assistant ([AssistActivity]). The words go to the phone app, which asks
 * Jarvis; the answer shows, and is said, and the watch listens again for a follow-up ([Talking])
 * until the conversation ends. Tap to ask again, or to stop.
 *
 * Below the Talk screen are Home and the approvals waiting ([WristScreens]), each a swipe back from
 * the last. The Tile and the complications open those straight away ([OPEN]), without listening.
 */
open class MainActivity : ComponentActivity() {
  private lateinit var ears: Ears
  private lateinit var talking: Talking

  private var phoneSays by mutableStateOf<PhoneSays>(PhoneSays.Checking)
  private var earsSay by mutableStateOf<String?>(null)
  private var speak by mutableStateOf(true)
  private var followUps by mutableStateOf(true)
  private var checking: Job? = null
  /** Where an intent asked to go, once the screens are there to go to. */
  private var goTo by mutableStateOf<String?>(null)

  private val mic =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else earsSay = "Allow the microphone for Jarvis in the watch's Settings"
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val app = watch
    speak = app.prefs.speak
    followUps = app.prefs.followUps
    ears =
        Ears(
            this,
            heard = { talking.heard(it) },
            refused = { earsSay = "The watch's speech recogniser won't listen for Jarvis" },
            // A follow-up that didn't come ends the conversation quietly, the answer left showing.
            missed = { if (talking.missed()) ears.stop() },
        )
    talking = Talking(lifecycleScope, app.asking.state, app.speaker.speaking, followUps = { app.prefs.followUps }, ask = app.asking::ask, listen = { ears.listen() })
    setContent {
      val heard by ears.state.collectAsStateWithLifecycle()
      val asked by app.asking.state.collectAsStateWithLifecycle()
      val speaking by app.speaker.speaking.collectAsStateWithLifecycle()
      val busy = heard is Ears.State.Listening || asked is Asking.State.Asked || speaking
      // Lit while it listens, thinks and speaks; the watch dims as usual otherwise.
      LaunchedEffect(busy) {
        if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      }
      val counts by app.wrist.counts.collectAsStateWithLifecycle()
      val following by talking.following.collectAsStateWithLifecycle()
      val nav = rememberSwipeDismissableNavController()
      LaunchedEffect(goTo) {
        val to = goTo ?: return@LaunchedEffect
        goTo = null
        nav.navigate(to) { popUpTo(TALK) { inclusive = to == TALK } }
      }
      SwipeDismissableNavHost(navController = nav, startDestination = TALK) {
        composable(TALK) {
          WatchScreen(
              heard = heard,
              asked = asked,
              speaking = speaking,
              phone = phoneSays,
              trouble = earsSay,
              speak = speak,
              following = following,
              followUps = followUps,
              onFollowUps = {
                app.prefs.followUps = it
                followUps = it
              },
              approvals = counts.approvals,
              onTalk = ::talk,
              onSpeak = {
                app.prefs.speak = it
                speak = it
              },
              onHome = { nav.navigate(HOME) },
              onApprovals = { nav.navigate(APPROVALS) },
          )
        }
        composable(HOME) { HomeScreen(app.wrist, onThing = { nav.navigate(thingRoute(it)) }) }
        composable(THING_ROUTE) { e -> ThingScreen(app.wrist, Uri.decode(e.arguments?.getString("id").orEmpty())) }
        composable(APPROVALS) { ApprovalsScreen(app.wrist) }
      }
    }
    if (savedInstanceState == null) opened(intent)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    opened(intent)
  }

  /** Opened to talk (the key, the icon, the Tile's Talk), or at one of the wrist's screens. */
  private fun opened(intent: Intent) {
    val open = intent.getStringExtra(OPEN)
    when {
      // Always a new question: not the button's "done" or "stop", whatever was going on.
      open == null -> {
        goTo = TALK
        fresh()
      }
      open == MENU -> goTo = TALK
      open == HOME || open == APPROVALS -> goTo = open
      open.startsWith(THING) -> goTo = thingRoute(open.removePrefix(THING))
    }
  }

  override fun onStart() {
    super.onStart()
    watch.screenStarted()
    checking =
        lifecycleScope.launch {
          phoneSays =
              when (val r = watch.asking.hello()) {
                null -> PhoneSays.OutOfReach
                else -> if (r.signedIn) PhoneSays.SignedIn(r.name) else PhoneSays.SignedOut
              }
          // The Talk screen's approvals, as they are now.
          if (phoneSays is PhoneSays.SignedIn) watch.wrist.fetchCounts()
        }
  }

  override fun onPause() {
    // The other screen of Jarvis's may be coming: one recogniser at a time.
    talking.end()
    ears.stop()
    super.onPause()
  }

  override fun onStop() {
    // A late answer would otherwise overwrite the next screen's own check.
    checking?.cancel()
    watch.screenStopped()
    super.onStop()
  }

  /**
   * The one button: listen; while listening for a question, done; while listening for a follow-up,
   * answering or speaking, stop, and the conversation with it.
   */
  private fun talk() {
    earsSay = null
    when {
      ears.state.value is Ears.State.Listening && talking.following.value -> {
        talking.end()
        ears.stop()
      }
      ears.state.value is Ears.State.Listening -> ears.finish()
      watch.asking.state.value is Asking.State.Asked || watch.speaker.speaking.value -> {
        talking.end()
        watch.asking.stop()
      }
      else -> fresh()
    }
  }

  /** A new opening question, once the microphone is allowed. */
  private fun fresh() {
    earsSay = null
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO) else listen()
  }

  private fun listen() {
    watch.asking.stop()
    talking.start()
  }

  companion object {
    /** The screen to open at, from the Tile or a complication: [HOME], [APPROVALS], or [THING] and its id. */
    const val OPEN = "open"
    const val HOME = "home"
    const val APPROVALS = "approvals"
    const val THING = "thing:"
    /** The Talk screen and the ways on from it, without listening. */
    const val MENU = "menu"

    private const val TALK = "talk"
    private const val THING_ROUTE = "thing/{id}"

    private fun thingRoute(id: String) = "thing/" + Uri.encode(id)
  }
}

/** What the phone app said of itself when the watch app opened ([Asking.hello]). */
sealed interface PhoneSays {
  data object Checking : PhoneSays

  data object OutOfReach : PhoneSays

  data object SignedOut : PhoneSays

  /** [name] is "" for a device token. */
  data class SignedIn(val name: String) : PhoneSays
}

/** Jarvis opened as the watch's assistant: the same screen, in a task of its own (the manifest says why). */
class AssistActivity : MainActivity()
