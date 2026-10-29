package app.jarvis.glasses.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.jarvis.Address
import app.jarvis.glasses.jarvis.Pairing
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Setting up: which Jarvis and how the app signs in, the glasses, and how it talks. */
@Composable
fun SettingsScreen(app: JarvisApp, hooks: Hooks) {
  var jarvisPage by remember { mutableStateOf(false) }
  if (jarvisPage) return JarvisSettings(app, back = { jarvisPage = false })
  val connected by app.audio.connected.collectAsStateWithLifecycle()
  val registered by app.link.registered.collectAsStateWithLifecycle()
  val metaProblem by app.link.problem.collectAsStateWithLifecycle()
  val socket by app.alerts.state.collectAsStateWithLifecycle()
  val liveNote by app.live.note.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()

  var url by remember { mutableStateOf(app.prefs.url) }
  var token by remember { mutableStateOf(app.prefs.token) }
  var accessId by remember { mutableStateOf(app.prefs.accessId) }
  var accessSecret by remember { mutableStateOf(app.prefs.accessSecret) }
  var who by remember { mutableStateOf(app.prefs.signedInAs) }
  var check by remember { mutableStateOf("") }
  var code by remember { mutableStateOf<Pairing.Code?>(null) }
  var pairing by remember { mutableStateOf<Job?>(null) }
  var showToken by remember { mutableStateOf(app.prefs.token.startsWith("jdv1_")) }
  var showAccess by remember { mutableStateOf(app.prefs.accessId.isNotEmpty()) }
  var followUps by remember { mutableStateOf(app.prefs.followUps) }
  var speakAlerts by remember { mutableStateOf(app.prefs.speakAlerts) }
  var g2 by remember { mutableStateOf(app.prefs.g2) }
  var live by remember { mutableStateOf(app.prefs.live) }
  var quiet by remember { mutableStateOf(app.prefs.liveQuietSeconds) }

  fun saveAddress() {
    app.prefs.url = url
    app.prefs.accessId = accessId
    app.prefs.accessSecret = accessSecret
  }

  fun checkNow() {
    check = "Checking…"
    scope.launch {
      check = runCatching { app.client.check() }.fold({ "Connected" }, { it.message ?: "Couldn't reach Jarvis" })
      if (app.prefs.isSetUp) app.alerts.restart()
    }
  }

  LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { Heading("Settings") }

    item {
      Section("Jarvis") {
        OutlinedTextField(url, { url = it }, label = { Text("Address") }, placeholder = { Text("https://jarvis.example.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (url.isNotBlank() && !Address.usable(url)) {
          Text("Use https://, or http:// only for an address on your home network.", style = MaterialTheme.typography.bodySmall, color = Even.colors.danger)
        }
        Line("Signed in", who.ifEmpty { if (app.prefs.token.startsWith("jdv1_")) "as a device" else "no" })
        val c = code
        if (c != null) {
          Text("Approve this code in Jarvis", style = MaterialTheme.typography.bodyMedium)
          Text(c.code, fontFamily = FontFamily.Monospace, fontSize = 34.sp, letterSpacing = 6.sp)
          Text(
              "In Jarvis on another screen where you're signed in: menu → Family → Sign in another screen, type this code, then Sign it in. It lasts ten minutes.",
              style = MaterialTheme.typography.bodySmall,
              color = Even.colors.dim,
          )
          OutlinedButton(onClick = { pairing?.cancel(); code = null }) { Text("Cancel") }
        } else {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = Address.usable(url),
                onClick = {
                  saveAddress()
                  check = ""
                  pairing =
                      scope.launch {
                        runCatching {
                              val started = app.pairing.start("Jarvis glasses app")
                              code = started
                              app.pairing.await(started)
                            }
                            .onSuccess { outcome ->
                              code = null
                              when (outcome) {
                                is Pairing.Outcome.Approved -> {
                                  app.prefs.token = outcome.token
                                  token = outcome.token
                                  who = outcome.name.ifEmpty { "you" }
                                  app.prefs.signedInAs = who
                                  checkNow()
                                }
                                Pairing.Outcome.Expired -> check = "The code ran out; try again."
                              }
                            }
                            .onFailure { e ->
                              code = null
                              if (e !is kotlinx.coroutines.CancellationException) check = e.message ?: "Couldn't reach Jarvis"
                            }
                      }
                },
            ) {
              Text(if (who.isEmpty()) "Sign in with a code" else "Sign in again")
            }
            OutlinedButton(onClick = { showToken = !showToken }) { Text("Device token") }
          }
        }
        if (showToken) {
          OutlinedTextField(
              token,
              { token = it },
              label = { Text("Device token (jdv1_…)") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
          )
        }
        Text(
            "Behind Cloudflare Access",
            style = MaterialTheme.typography.bodyMedium,
            color = Even.colors.dim,
            modifier = Modifier.clickable { showAccess = !showAccess }.padding(vertical = 4.dp),
        )
        if (showAccess) {
          Text("Only if your Jarvis sits behind Cloudflare Access: its service token.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
          OutlinedTextField(accessId, { accessId = it }, label = { Text("Client ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(
              accessSecret,
              { accessSecret = it },
              label = { Text("Client secret") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
          )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedButton(
              onClick = {
                saveAddress()
                if (token != app.prefs.token) {
                  app.prefs.token = token
                  who = ""
                  app.prefs.signedInAs = ""
                }
                checkNow()
              },
          ) {
            Text("Save")
          }
          Text(check, style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
        }
        Line("Alerts", socket.name.lowercase())
      }
    }

    if (app.prefs.token.startsWith("jss1_")) {
      item { SectionRow("Jarvis settings", "") { jarvisPage = true } }
    }

    item {
      Section("Theme") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          for ((key, label) in listOf("system" to "System", "light" to "Light", "dark" to "Dark")) {
            FilterChip(selected = hooks.theme == key, onClick = { hooks.theme = key }, label = { Text(label) })
          }
        }
      }
    }

    item {
      Section("Glasses") {
        Line("Headset", if (connected) "connected" else "not connected")
        Line("Meta AI", if (!registered) "not connected" else if (app.link.devMode) "connected (developer mode)" else "connected")
        if (metaProblem.isNotEmpty()) Text(metaProblem, style = MaterialTheme.typography.bodySmall, color = Even.colors.danger)
        Line("Camera", if (hooks.cameraAllowed) "allowed" else "not allowed")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (registered) OutlinedButton(onClick = hooks::disconnectMetaAi) { Text("Disconnect Meta AI") }
          else OutlinedButton(onClick = hooks::connectMetaAi) { Text("Connect Meta AI") }
          OutlinedButton(onClick = hooks::allowCamera, enabled = registered) { Text("Allow camera") }
        }
      }
    }

    item {
      Section("Voice") {
        Toggle("Listen for a follow-up after each answer", followUps) { followUps = it; app.prefs.followUps = it }
        Toggle("Live voice (GPT-Live, billed by the minute)", live) { live = it; app.prefs.live = it }
        if (live) {
          Text("Hang up after this much quiet", style = MaterialTheme.typography.bodyMedium)
          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (s in listOf(8, 10, 15, 20, 30)) {
              FilterChip(selected = quiet == s, onClick = { quiet = s; app.prefs.liveQuietSeconds = s }, label = { Text("$s s") })
            }
          }
          if (liveNote.isNotEmpty()) Text("Live voice: $liveNote", style = MaterialTheme.typography.bodySmall, color = Even.colors.danger)
        }
        Toggle("This phone may say alerts in the glasses and listen for a reply", speakAlerts) { speakAlerts = it; app.prefs.speakAlerts = it }
        Text(
            "Only those Jarvis talks to you about (Your alerts, below). Off, this phone shows them as notifications.",
            style = MaterialTheme.typography.bodySmall,
            color = Even.colors.dim,
        )
        if (app.g2 != null) {
          Toggle("Show alerts on Even G2 glasses", g2) { g2 = it; app.prefs.g2 = it }
          Text(
              "When the G2 is with this phone and Ray-Ban glasses aren't in use. Tap or swipe up to reply, and the microphone opens; swipe down to close.",
              style = MaterialTheme.typography.bodySmall,
              color = Even.colors.dim,
          )
        }
      }
    }

    // A person's own choices, kept by Jarvis: a device token has none.
    if (app.prefs.token.startsWith("jss1_")) {
      item { AlertChoices(app) }
    }

    if (!hooks.backgroundAllowed) {
      item {
        Section("Phone") {
          Text("Android may stop Jarvis in the background, and alerts would wait.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
          OutlinedButton(onClick = hooks::allowBackground) { Text("Let Jarvis run in the background") }
        }
      }
    }
  }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
fun Line(label: String, value: String) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Text(value, style = MaterialTheme.typography.bodyMedium, color = Even.colors.dim)
  }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
    Switch(on, onChange)
  }
}
