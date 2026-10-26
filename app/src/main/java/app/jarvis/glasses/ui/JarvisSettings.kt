package app.jarvis.glasses.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.GroupView
import app.jarvis.glasses.hub.SettingView
import app.jarvis.glasses.hub.SettingsReply
import kotlinx.coroutines.launch

/**
 * Jarvis's own settings, everything the web app's Settings panel holds, for a family admin: a list
 * of sections, each opened on its own, as the web app's section menu does.
 */
@Composable
fun JarvisSettings(app: JarvisApp, back: () -> Unit) {
  BackHandler(onBack = back)
  var data by remember { mutableStateOf<SettingsReply?>(null) }
  var problem by remember { mutableStateOf("") }
  var open by remember { mutableStateOf<String?>(null) }
  // Kept above the pages, so coming back from one finds the list where it was.
  val index = remember { LazyListState() }
  LaunchedEffect(Unit) { runCatching { app.admin.settings() }.onSuccess { data = it }.onFailure { problem = it.message ?: "Couldn't read Jarvis's settings" } }

  val d = data
  val group = d?.groups?.find { it.id == open }
  val shut = { open = null }
  when {
    d != null && group != null -> return GroupPage(app, d, group, onData = { data = it }, back = shut)
    open == "plan" -> return PlanPage(app, shut)
    open == "router" -> return RouterPage(app, shut)
    open == "usage" -> return UsagePage(app, shut)
    open == "mcp" -> return McpPage(app, shut)
    open == "export" -> return ExportPage(app, shut)
  }
  val c = Even.colors
  LazyColumn(Modifier.fillMaxSize(), state = index, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead("Jarvis settings", back) }
    if (problem.isNotEmpty()) item { Text(problem, style = MaterialTheme.typography.bodyMedium, color = c.danger, modifier = Modifier.padding(4.dp)) }
    if (d == null && problem.isEmpty()) item { Text("Loading…", style = MaterialTheme.typography.bodyMedium, color = c.dim, modifier = Modifier.padding(4.dp)) }
    if (d != null) {
      if (!d.storage) item { Text("Settings storage is not bound on this Jarvis, so nothing can be saved here.", style = MaterialTheme.typography.bodySmall, color = c.danger) }
      item {
        Section("Owner key") {
          Line("In force", if (d.owner.fingerprint.isNotEmpty()) "sha256 ${d.owner.fingerprint}" else "not set")
          Text(
              "The one setting that can't change here: it's set when Jarvis is deployed. To change it, run this, then enter the new key once on each screen that uses it. Device tokens keep working.",
              style = MaterialTheme.typography.bodySmall,
              color = c.dim,
          )
          Text(d.owner.howToChange, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
      }
      item { SectionRow("ChatGPT plan", "") { open = "plan" } }
      items(d.groups, key = { it.id }) { g -> SectionRow(g.title, stateOf(g, d)) { open = g.id } }
      item { SectionRow("Router model", "") { open = "router" } }
      item { SectionRow("MCP servers", "") { open = "mcp" } }
    }
    // Usage is each person's own, so it shows even to someone who isn't an admin.
    if (d != null || problem.isNotEmpty()) item { SectionRow("Usage and cost", "") { open = "usage" } }
    if (d != null) {
      item { SectionRow("Export your data", "") { open = "export" } }
      item { VersionLine(app) }
    }
  }
}

/** "on" set up, "need" required and missing, "off" optional and not set up, as the web app marks them. */
private fun stateOf(g: GroupView, d: SettingsReply): String {
  val settings = d.settings.filter { it.group == g.id }
  val has = { name: String -> d.settings.any { it.name == name && (it.source == "saved" || it.source == "deployment") } }
  val ready = if (g.id == "cameras") has("CAMERAS") || (has("HA_BASE_URL") && has("HA_TOKEN")) else g.configured
  return if (ready) "on" else if (settings.any { it.required }) "need" else "off"
}

@Composable
fun SectionRow(title: String, state: String, onClick: () -> Unit) {
  val c = Even.colors
  Tile(Modifier.fillMaxWidth(), onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
      val (label, color) =
          when (state) {
            "on" -> "set up" to c.on
            "need" -> "needs setting up" to c.danger
            "off" -> "not set up" to c.dim
            else -> "" to c.dim
          }
      if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
  }
}

@Composable
fun PageHead(title: String, back: () -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
    Text(title, style = MaterialTheme.typography.headlineSmall)
  }
}

private val SOURCE = mapOf("saved" to "saved here", "deployment" to "from deployment", "default" to "default", "unset" to "not set")

private val REDIRECT = mapOf("google" to "/api/google/callback", "spotify" to "/api/spotify/callback")

/** One section: its settings, each kept as is unless something is typed or chosen. */
@Composable
private fun GroupPage(app: JarvisApp, d: SettingsReply, g: GroupView, onData: (SettingsReply) -> Unit, back: () -> Unit) {
  BackHandler(onBack = back)
  val c = Even.colors
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  val typed = remember(g.id) { mutableStateMapOf<String, String>() }
  var result by remember(g.id) { mutableStateOf("") }
  var bad by remember(g.id) { mutableStateOf(false) }
  var errors by remember(g.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
  val settings = d.settings.filter { it.group == g.id }

  fun say(text: String, wrong: Boolean = false) {
    result = text
    bad = wrong
  }

  fun changes() = typed.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }

  LazyColumn(Modifier.fillMaxSize(), state = remember { LazyListState() }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead(g.title, back) }
    item {
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (g.intro.isNotEmpty()) Text(g.intro, style = MaterialTheme.typography.bodySmall, color = c.dim)
          REDIRECT[g.id]?.let { path ->
            Text("Redirect URI to register with the provider:", style = MaterialTheme.typography.bodySmall, color = c.dim)
            Text(app.prefs.url + path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
          }
        }
      }
    }
    if (g.id == "alerts") item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { AlertsOverviewBlock(app) } }
    items(settings, key = { it.name }) { s ->
      Field(
          s,
          typed[s.name].orEmpty(),
          error = errors[s.name],
          onType = { typed[s.name] = it },
          onClear = {
            scope.launch {
              runCatching { app.admin.save(mapOf(s.name to null)) }
                  .onSuccess { r ->
                    onData(r)
                    say(r.telegram?.let { "Cleared, but Telegram would not send messages here: $it" } ?: "Cleared.", r.telegram != null)
                  }
                  .onFailure { say(it.message ?: "Not cleared", true) }
            }
          },
      )
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (g.testable) {
            OutlinedButton(
                onClick = {
                  val values = changes()
                  say(if (values.isNotEmpty()) "Testing what you typed, before saving…" else "Testing…")
                  scope.launch { runCatching { app.admin.test(g.id, values) }.onSuccess { r -> say(r.detail ?: r.error ?: "", !r.ok) }.onFailure { say(it.message ?: "Test failed", true) } }
                },
            ) {
              Text("Test")
            }
          }
          OutlinedButton(
              onClick = {
                val ch = changes()
                if (ch.isEmpty()) return@OutlinedButton say("Nothing typed to save.", true)
                say("Saving…")
                scope.launch {
                  runCatching { app.admin.save(ch) }
                      .onSuccess { r ->
                        val errs = r.errors
                        if (errs != null) {
                          errors = errs
                          say(errs.values.joinToString(" · "), true)
                        } else {
                          errors = emptyMap()
                          typed.clear()
                          onData(r)
                          say(r.telegram?.let { "Saved, but Telegram would not send messages here: $it" } ?: "Saved. Takes effect within about 15 seconds.", r.telegram != null)
                        }
                      }
                      .onFailure { say(it.message ?: "Not saved", true) }
                }
              },
          ) {
            Text("Save")
          }
          g.linked?.let { linked ->
            if (g.configured) {
              OutlinedButton(
                  onClick = {
                    say("Opening the sign-in page…")
                    scope.launch {
                      runCatching { app.admin.linkUrl(g.id) }
                          .onSuccess { url ->
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            say("Finish signing in in the browser, then press Test here.")
                          }
                          .onFailure { say(it.message ?: "Couldn't open the sign-in page", true) }
                    }
                  },
              ) {
                Text(if (linked) "Re-link" else "Link")
              }
            }
          }
        }
        g.linked?.let { Text(if (it) "Account linked" else "No account linked", style = MaterialTheme.typography.bodySmall, color = if (it) c.on else c.dim) }
        if (result.isNotEmpty()) Text(result, style = MaterialTheme.typography.bodySmall, color = if (bad) c.danger else c.dim)
      }
    }
  }
}

/** One setting: what is in force and where from, then a field to replace it (blank keeps it). */
@Composable
private fun Field(s: SettingView, value: String, error: String?, onType: (String) -> Unit, onClear: () -> Unit) {
  val c = Even.colors
  Tile(Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.label + if (s.required) " *" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(SOURCE[s.source] ?: s.source, style = MaterialTheme.typography.labelSmall, color = c.dim, modifier = Modifier.clip(Even.corner).background(c.chip).padding(horizontal = 8.dp, vertical = 3.dp))
      }
      val shown = if (s.kind == "bool") mapOf("1" to "on", "0" to "off")[s.display] ?: s.display else s.display
      if (shown.isNotEmpty()) Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = if (s.kind == "secret") FontFamily.Monospace else null, color = c.dim)
      s.withheld?.let { Text("Not in use: $it.", style = MaterialTheme.typography.bodySmall, color = c.danger) }
      when (s.kind) {
        "bool", "enum" -> {
          val opts = if (s.kind == "bool") listOf("on" to "1", "off" to "0") else s.options.orEmpty().map { it to it }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = value.isEmpty(), onClick = { onType("") }, label = { Text("unchanged") })
            for ((label, v) in opts) FilterChip(selected = value == v, onClick = { onType(v) }, label = { Text(label) })
          }
        }
        else ->
            OutlinedTextField(
                value,
                onType,
                placeholder = { Text(if (s.source == "unset") "Enter a value" else "Enter a new value to replace it") },
                singleLine = true,
                visualTransformation = if (s.kind == "secret") PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType =
                            when (s.kind) {
                              "number" -> KeyboardType.Number
                              "url" -> KeyboardType.Uri
                              "secret" -> KeyboardType.Password
                              else -> KeyboardType.Text
                            },
                        autoCorrectEnabled = false,
                    ),
                isError = error != null,
                modifier = Modifier.fillMaxWidth(),
            )
      }
      error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
      if (s.help.isNotEmpty()) Text(s.help, style = MaterialTheme.typography.bodySmall, color = c.dim)
      if (s.source == "saved") {
        Text(
            if (s.kind == "secret") "Clear" else "Reset",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(Even.corner).clickable(onClick = onClear).padding(vertical = 4.dp),
        )
      }
    }
  }
}
