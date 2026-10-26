package app.jarvis.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.live.LiveVoice
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import kotlinx.coroutines.launch

/**
 * Setting up (Jarvis address and device token, Meta AI registration, the glasses camera) and
 * watching the conversation. Talking itself needs no screen: "Hey Meta, open Jarvis", an alert,
 * or Talk on the notification.
 */
class MainActivity : ComponentActivity() {
  private var cameraAllowed by mutableStateOf(false)

  private val phonePermissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissions() }

  private val cameraPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { lifecycleScopeRefreshCamera() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    phonePermissions.launch(PHONE_PERMISSIONS)
    if (savedInstanceState == null && isVoiceInvocationsIntent(intent)) onHeyMeta()
    setContent { Screen() }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    if (isVoiceInvocationsIntent(intent)) onHeyMeta()
  }

  override fun onResume() {
    super.onResume()
    lifecycleScopeRefreshCamera()
  }

  private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  /** Bluetooth first: the toolkit must not boot before it (see [app.jarvis.glasses.glasses.GlassesLink]). */
  private fun onPermissions() {
    if (granted(Manifest.permission.BLUETOOTH_CONNECT)) jarvis.link.start(onHeyMeta = { jarvis.talk() })
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

  private fun lifecycleScopeRefreshCamera() {
    jarvis.scope.launch { cameraAllowed = jarvis.link.cameraAllowed() }
  }

  private fun ignoringBatteryLimits() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

  @Composable
  private fun Screen() {
    val app = jarvis
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
      Surface(Modifier.fillMaxSize()) {
        val connected by app.audio.connected.collectAsStateWithLifecycle()
        val registered by app.link.registered.collectAsStateWithLifecycle()
        val socket by app.alerts.state.collectAsStateWithLifecycle()
        val status by app.conversation.status.collectAsStateWithLifecycle()
        val note by app.conversation.note.collectAsStateWithLifecycle()
        val talked by app.conversation.turns.collectAsStateWithLifecycle()
        val liveState by app.live.state.collectAsStateWithLifecycle()
        val liveTurns by app.live.turns.collectAsStateWithLifecycle()
        val liveNote by app.live.note.collectAsStateWithLifecycle()
        val turns = if (liveState != LiveVoice.State.OFF) liveTurns else talked
        val idle = status == Conversation.Status.IDLE && liveState == LiveVoice.State.OFF
        var url by remember { mutableStateOf(app.prefs.url) }
        var token by remember { mutableStateOf(app.prefs.token) }
        var check by remember { mutableStateOf("") }
        var followUps by remember { mutableStateOf(app.prefs.followUps) }
        var speakAlerts by remember { mutableStateOf(app.prefs.speakAlerts) }
        var live by remember { mutableStateOf(app.prefs.live) }
        val scope = rememberCoroutineScope()

        LazyColumn(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item { Text("Jarvis on the glasses", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp)) }
          item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Line("Glasses headset", if (connected) "connected" else "not connected")
              Line("Meta AI", if (registered) "connected" else "not connected")
              Line("Glasses camera", if (cameraAllowed) "allowed" else "not allowed")
              Line("Alerts", socket.name.lowercase())
              Line("Now", if (liveState == LiveVoice.State.OFF) statusText(status) else "live, ${liveState.name.lowercase()}")
              if (liveNote.isNotEmpty()) Text("Live voice: $liveNote", style = MaterialTheme.typography.bodySmall)
              if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
            }
          }
          item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = { JarvisService.start(this@MainActivity, JarvisService.ACTION_TALK) }, enabled = idle) {
                Text("Talk")
              }
              OutlinedButton(
                  onClick = {
                    app.conversation.stop()
                    app.live.stop()
                  },
                  enabled = !idle,
              ) {
                Text("Stop")
              }
            }
          }
          item { Text("Jarvis", style = MaterialTheme.typography.titleMedium) }
          item {
            OutlinedTextField(url, { url = it }, label = { Text("Address (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          }
          item {
            OutlinedTextField(
                token,
                { token = it },
                label = { Text("Device token (jdv1_…)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
          }
          item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(
                  onClick = {
                    app.prefs.url = url
                    app.prefs.token = token
                    check = "Checking…"
                    scope.launch {
                      check = runCatching { app.client.check() }.fold({ "Connected" }, { it.message ?: "Couldn't reach Jarvis" })
                      if (app.prefs.isSetUp) {
                        app.alerts.stop()
                        app.alerts.start()
                      }
                    }
                  },
              ) {
                Text("Save")
              }
              Text(check, style = MaterialTheme.typography.bodySmall)
            }
          }
          item { Text("Glasses", style = MaterialTheme.typography.titleMedium) }
          item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { app.link.register(this@MainActivity) }) { Text("Connect Meta AI") }
              OutlinedButton(onClick = { cameraPermission.launch(Permission.CAMERA) }, enabled = registered) { Text("Allow camera") }
            }
          }
          item { Toggle("Listen for a follow-up after each answer", followUps) { followUps = it; app.prefs.followUps = it } }
          item { Toggle("Live voice (GPT-Live, billed by the minute)", live) { live = it; app.prefs.live = it } }
          item { Toggle("Say alerts in the glasses and listen for a reply", speakAlerts) { speakAlerts = it; app.prefs.speakAlerts = it } }
          if (!ignoringBatteryLimits()) {
            item {
              OutlinedButton(
                  onClick = {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                  },
              ) {
                Text("Let Jarvis run in the background")
              }
            }
          }
          if (turns.isNotEmpty()) item { Text("Conversation", style = MaterialTheme.typography.titleMedium) }
          items(turns) { t ->
            Text(
                (if (t.role == "user") "You: " else "Jarvis: ") + t.text,
                style = MaterialTheme.typography.bodyMedium,
            )
          }
          item { Text("") }
        }
      }
    }
  }

  @Composable
  private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium)
      Text(value, style = MaterialTheme.typography.bodyMedium)
    }
  }

  @Composable
  private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
      Switch(on, onChange)
    }
  }

  private fun statusText(s: Conversation.Status) =
      when (s) {
        Conversation.Status.IDLE -> "waiting"
        Conversation.Status.LISTENING -> "listening"
        Conversation.Status.THINKING -> "thinking"
        Conversation.Status.SPEAKING -> "speaking"
      }

  companion object {
    private val PHONE_PERMISSIONS =
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
  }
}
