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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.jarvis.glasses.glasses.GlassesLink
import app.jarvis.glasses.ui.ChatScreen
import app.jarvis.glasses.ui.EvenTheme
import app.jarvis.glasses.ui.HomeScreen
import app.jarvis.glasses.ui.Hooks
import app.jarvis.glasses.ui.InboxScreen
import app.jarvis.glasses.ui.SettingsScreen
import app.jarvis.glasses.ui.Shell
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

  private var chosenTheme by mutableStateOf("system")

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

  private val photoPicker =
      registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::readPhoto) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    phonePermissions.launch(PHONE_PERMISSIONS)
    if (savedInstanceState == null && isVoiceInvocationsIntent(intent)) onHeyMeta()
    chosenTheme = jarvis.prefs.theme
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
            home = { HomeScreen(jarvis, this) },
            chat = { ChatScreen(jarvis, this) },
            inbox = { InboxScreen(jarvis) },
            settings = { SettingsScreen(jarvis, this) },
        )
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    jarvis.link.handle(this, intent)
    if (isVoiceInvocationsIntent(intent)) onHeyMeta()
  }

  override fun onResume() {
    super.onResume()
    refreshCamera()
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

  override fun talk() = JarvisService.start(this, JarvisService.ACTION_TALK)

  override fun pickPhoto(onPicked: (ByteArray) -> Unit) {
    onPhoto = onPicked
    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }

  companion object {
    private val PHONE_PERMISSIONS =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
  }
}
