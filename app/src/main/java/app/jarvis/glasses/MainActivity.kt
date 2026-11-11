package app.jarvis.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.glasses.GlassesLink
import app.jarvis.glasses.ui.ChatScreen
import app.jarvis.glasses.ui.EvenTheme
import app.jarvis.glasses.ui.HomeScreen
import app.jarvis.glasses.ui.Hooks
import app.jarvis.glasses.ui.InboxScreen
import app.jarvis.glasses.ui.LiveWave
import app.jarvis.glasses.ui.SettingsScreen
import app.jarvis.glasses.ui.Shell
import app.jarvis.glasses.ui.Tab
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's one screen: four tabs (Home, Chat, Inbox, Settings) in the Even Realities style.
 * Talking needs no screen: "Hey Meta, open Jarvis", an alert, or Talk on the notification.
 */
class MainActivity : ComponentActivity(), Hooks {
  override var cameraAllowed by mutableStateOf(false)
    private set

  override var backgroundAllowed by mutableStateOf(true)
    private set

  override var locationAllowed by mutableStateOf(false)
    private set

  override var locationAlwaysAllowed by mutableStateOf(false)
    private set

  private var chosenTheme by mutableStateOf("system")

  /** Where a notification asked to open: Chat, or the Inbox at a conversation. Counted, so the same place opens again. */
  private var opening by mutableStateOf(Opening(null, null, 0))

  data class Opening(val tab: Tab?, val convo: String?, val n: Int)

  override var theme: String
    get() = chosenTheme
    set(v) {
      chosenTheme = v
      jarvis.prefs.theme = v
    }

  private var onPhoto: ((ByteArray) -> Unit)? = null

  private val phonePermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissions() }

  private val cameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { refreshCamera() }

  // Location is not asked at first launch: only from Settings → Location, when its switch goes on.
  private val locationPermission =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked(Manifest.permission.ACCESS_FINE_LOCATION) }

  private val locationAlwaysPermission =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { asked(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }

  private val photoPicker =
      registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::readPhoto) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    phonePermissions.launch(PHONE_PERMISSIONS)
    if (savedInstanceState == null && isVoiceInvocationsIntent(intent)) onHeyMeta()
    if (savedInstanceState == null) opened(intent)
    chosenTheme = jarvis.prefs.theme
    locationAllowed = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    locationAlwaysAllowed = locationAllowed && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    setContent {
      val dark = if (theme == "system") isSystemInDarkTheme() else theme == "dark"
      LaunchedEffect(dark) {
        // Status bar icons follow the app's theme, not the phone's.
        val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
      }
      EvenTheme(dark) {
        Shell(
            this,
            open = opening.tab,
            openCount = opening.n,
            home = { HomeScreen(jarvis, this) },
            chat = { ChatScreen(jarvis, this) },
            inbox = { InboxScreen(jarvis, opening.convo, onOpened = { opening = opening.copy(convo = null) }) },
            settings = { SettingsScreen(jarvis, this) },
            // Over every tab while Jarvis is in a conversation; Chat places it above its message box.
            floating = { tab -> if (tab != Tab.CHAT) LiveWave(jarvis, Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    jarvis.link.handle(this, intent)
    if (isVoiceInvocationsIntent(intent)) onHeyMeta()
    opened(intent)
  }

  /** A notification's tap: to Chat, or the Inbox and the conversation it came from. */
  private fun opened(intent: Intent) {
    // Jarvis reopened from Recents comes with the intent that first opened it: not a tap now.
    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
    val tab =
        when (intent.getStringExtra(OPEN)) {
          OPEN_CHAT -> Tab.CHAT
          OPEN_INBOX -> Tab.INBOX
          else -> return
        }
    opening = Opening(tab, intent.getStringExtra(OPEN_CONVO), opening.n + 1)
  }

  override fun onResume() {
    super.onResume()
    refreshCamera()
    refreshLocation()
    backgroundAllowed = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    // Notifications may have been switched on or off in Android's settings meanwhile.
    jarvis.refreshPresence()
  }

  private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  /** Bluetooth first: the toolkit must not boot before it (see [app.jarvis.glasses.glasses.GlassesLink]). */
  private fun onPermissions() {
    if (granted(Manifest.permission.BLUETOOTH_CONNECT)) {
      jarvis.link.start(onHeyMeta = { jarvis.talk() })
      jarvis.link.handle(this, intent)
    }
    if (granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.BLUETOOTH_CONNECT)) JarvisService.start(this)
  }

  /** "Hey Meta, open Jarvis" with the app closed or behind: it may come with the phone locked. */
  private fun onHeyMeta() {
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    if (granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
      JarvisService.start(this, JarvisService.ACTION_TALK)
    }
  }

  private fun refreshCamera() {
    jarvis.scope.launch { cameraAllowed = jarvis.link.cameraAllowed() }
  }

  /** Allowed or taken away, here or in Android's settings meanwhile: the places' fences follow. */
  private fun refreshLocation() {
    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val always = fine && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    if (fine == locationAllowed && always == locationAlwaysAllowed) return
    locationAllowed = fine
    locationAlwaysAllowed = always
    jarvis.fences.refresh()
  }

  /** Refused, and Android won't ask again (or only approximate was given): its settings page for this app is where it is changed. */
  private fun asked(permission: String) {
    refreshLocation()
    if (!granted(permission) && !shouldShowRequestPermissionRationale(permission)) {
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }
  }

  private fun readPhoto(uri: Uri) {
    val done = onPhoto ?: return
    jarvis.scope.launch {
      val jpeg =
          withContext(Dispatchers.Default) {
            runCatching {
                  val bitmap =
                      ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                      }
                  GlassesLink.jpeg(bitmap)
                }
                .getOrNull()
          }
      if (jpeg != null) done(jpeg)
    }
  }

  override fun connectMetaAi() = jarvis.link.register(this)

  override fun disconnectMetaAi() = jarvis.link.unregister(this)

  override fun allowCamera() = cameraPermission.launch(Permission.CAMERA)

  override fun allowBackground() {
    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
  }

  override fun allowLocation() = locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

  // Android 11 and later show no dialog for this: it opens the app's location page, where "Allow all the time" is.
  override fun allowLocationAlways() = locationAlwaysPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

  override fun talk() = JarvisService.start(this, JarvisService.ACTION_TALK)

  override fun pickPhoto(onPicked: (ByteArray) -> Unit) {
    onPhoto = onPicked
    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }

  companion object {
    /** Which tab a notification opens ([OPEN_CHAT], [OPEN_INBOX]), and in the Inbox, which conversation. */
    const val OPEN = "open"
    const val OPEN_CHAT = "chat"
    const val OPEN_INBOX = "inbox"
    const val OPEN_CONVO = "convo"

    private val PHONE_PERMISSIONS =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
  }
}
