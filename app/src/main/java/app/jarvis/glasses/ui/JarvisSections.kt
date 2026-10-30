package app.jarvis.glasses.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.hub.AlertsOverview
import app.jarvis.glasses.hub.PlanView
import app.jarvis.glasses.hub.Probe
import app.jarvis.glasses.hub.RouterState
import app.jarvis.glasses.hub.Skill
import app.jarvis.glasses.hub.Usage
import app.jarvis.glasses.jarvis.Loopback
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// The sections of the web app's Settings beyond the settings themselves (src/app/ui/Settings.ts,
// ChatGptPlan.ts, AlertsPanel.ts, Export.ts in jarvis-voice-agent), with the same words.

/** A page of Jarvis settings: a back arrow, a title, then [content]. */
@Composable
private fun SubPage(title: String, back: () -> Unit, content: LazyListScope.() -> Unit) {
  BackHandler(onBack = back)
  LazyColumn(Modifier.fillMaxSize(), state = remember { LazyListState() }, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    item { PageHead(title, back) }
    content()
  }
}

@Composable
private fun Note(text: String, bad: Boolean = false) {
  val c = Even.colors
  if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = if (bad) c.danger else c.dim)
}

private fun hhmm(at: Long) = SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(at))

private fun whenOf(at: Long) = SimpleDateFormat("EEE H:mm", Locale.getDefault()).format(Date(at))

/* ---------- ChatGPT plan ---------------------------------------------------- */

@Composable
fun PlanPage(app: JarvisApp, back: () -> Unit) {
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  var plan by remember { mutableStateOf<PlanView?>(null) }
  var say by remember { mutableStateOf("") }
  var bad by remember { mutableStateOf(false) }
  var paste by remember { mutableStateOf("") }
  var pick by remember { mutableStateOf("") }
  var sure by remember { mutableStateOf(false) }
  var waiting by remember { mutableStateOf<Job?>(null) }

  fun note(t: String, wrong: Boolean = false) {
    say = t
    bad = wrong
  }

  fun load() = scope.launch { runCatching { app.admin.plan() }.onSuccess { plan = it; pick = it.model.orEmpty() }.onFailure { note(it.message ?: "", true) } }

  fun finish(address: String) =
      scope.launch {
        note("Connecting…")
        runCatching { app.admin.planFinish(address) }.onSuccess { note("Connected."); paste = ""; load() }.onFailure { note(it.message ?: "Not connected", true); load() }
      }

  LaunchedEffect(Unit) { load() }

  SubPage("ChatGPT plan", back) {
    val p = plan
    if (p == null) {
      item { Note(say.ifEmpty { "Loading…" }, bad) }
      return@SubPage
    }
    item {
      Section("ChatGPT plan") {
        if (p.connected) {
          Text("Connected${p.email?.let { " as $it" }.orEmpty()}. Questions use your plan first.", style = MaterialTheme.typography.bodyMedium)
          p.limitedUntil?.let { Note("The plan's limit was reached: ${if (p.key) "the API key is used" else "questions wait"} until ${hhmm(it)}, then the plan is tried again.") }
          if (!p.key) Note("Live voice needs an OpenAI key; push-to-talk and typing work on the plan.")
        } else if (!p.key) {
          Note("Jarvis has neither a ChatGPT plan nor an OpenAI key yet: connect one to ask it anything.")
        }
        p.problem?.let { Note(it, bad = true) }
      }
    }
    if (!p.connected || p.problem != null) {
      item {
        Section("Connect") {
          Note("1. Press Connect ChatGPT. A ChatGPT sign-in opens in the browser.\n2. Sign in and allow Jarvis to use your plan.\n3. The browser comes back to this app by itself.")
          OutlinedButton(
              onClick = {
                waiting?.cancel()
                note("Opening the ChatGPT sign-in…")
                waiting =
                    scope.launch {
                      runCatching {
                            val url = app.admin.planStart()
                            load()
                            // Listen before the browser can get there; the sign-in lasts ten minutes.
                            val caught = scope.launch {
                              val address = Loopback.await(Loopback.CHATGPT_PORT, 10 * 60_000)
                              if (address != null) finish(address) else note("The sign-in ran out: press Connect again.", true)
                            }
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            note("Finish signing in in the browser.")
                            caught.join()
                          }
                          .onFailure { note(it.message ?: "Couldn't start the sign-in", true) }
                    }
              },
          ) {
            Text("Connect ChatGPT")
          }
          if (p.pending) {
            Note("If the browser ends on a page that can't be reached instead, copy its whole address and paste it here.")
            OutlinedTextField(paste, { paste = it }, placeholder = { Text("http://127.0.0.1:1455/auth/callback?code=…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { if (paste.isBlank()) note("Paste the address the sign-in ended on first.", true) else finish(paste.trim()) }) { Text("Finish") }
          }
        }
      }
    }
    if (p.connected) {
      item {
        Section("Model for questions on the plan") {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = pick.isEmpty(), onClick = { pick = "" }, label = { Text("The router model") })
            for (m in p.models) FilterChip(selected = pick == m.id, onClick = { pick = m.id }, label = { Text(m.name) })
          }
          p.modelsError?.let { Note("Its models could not be listed: $it", bad = true) }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                  note(if (pick.isEmpty()) "Saving…" else "Trying it on your plan…")
                  scope.launch {
                    runCatching { app.admin.planModel(pick.ifEmpty { null }) }
                        .onSuccess { r ->
                          if (r.saved == false) note("Not changed: ${r.error ?: r.problem ?: "it did not work on your plan"}", true)
                          else note(if (pick.isEmpty()) "Questions on the plan use the router model." else "$pick works on your plan, and is in use.")
                          load()
                        }
                        .onFailure { note("Not changed: ${it.message}", true) }
                  }
                },
            ) {
              Text("Use this model")
            }
            OutlinedButton(
                onClick = {
                  if (!sure) {
                    sure = true
                    return@OutlinedButton
                  }
                  sure = false
                  scope.launch { runCatching { app.admin.planDisconnect() }.onSuccess { note("Disconnected."); load() }.onFailure { note(it.message ?: "", true) } }
                },
            ) {
              Text(if (sure) "Disconnect for good?" else "Disconnect")
            }
          }
        }
      }
    }
    item { Note(say, bad) }
  }
}

/* ---------- Router model ---------------------------------------------------- */

private fun describe(p: Probe): String {
  fun s(ms: Long?) = ms?.let { String.format(Locale.US, "%.1f", it / 1000.0) } ?: "?"
  if (p.ok) return "${p.model} passed: tool call in ${s(p.toolMs)}s, follow-up in ${s(p.chainMs)}s."
  val where = when (p.stage) { "tool" -> "calling a tool"; "chain" -> "following up after a tool"; else -> "the first call" }
  return "${p.model} failed at $where${p.status?.let { " ($it)" }.orEmpty()}: ${p.error ?: "no detail"}"
}

@Composable
fun RouterPage(app: JarvisApp, back: () -> Unit) {
  val scope = rememberCoroutineScope()
  var state by remember { mutableStateOf<RouterState?>(null) }
  var pick by remember { mutableStateOf("") }
  var say by remember { mutableStateOf("") }
  var bad by remember { mutableStateOf(false) }
  var busy by remember { mutableStateOf(false) }

  fun note(t: String, wrong: Boolean = false) {
    say = t
    bad = wrong
  }

  fun run(start: String, work: suspend () -> Unit) {
    busy = true
    note(start)
    scope.launch {
      runCatching { work() }.onFailure { note(it.message ?: "", true) }
      busy = false
    }
  }

  LaunchedEffect(Unit) { runCatching { app.admin.router() }.onSuccess { state = it; pick = it.model }.onFailure { note(it.message ?: "", true) } }

  SubPage("Router model", back) {
    item {
      Section("Router model") {
        Note("Decides what each question needs: which tool, which system. The voice is GPT-Live whichever model this is. A model is tested before it is used, and if OpenAI later refuses it, questions fall back to the default rather than failing.")
        val s = state
        if (s != null) {
          val how = when (s.source) { "ui" -> " — chosen here"; "env" -> " — set by the ROUTER_MODEL secret"; else -> " — the default" }
          Text("In use: ${s.model}$how", style = MaterialTheme.typography.bodyMedium)
          if (s.listed == false) Note("${s.model} is not on your OpenAI account any more.", bad = true)
          s.fallback?.let { f ->
            Note("OpenAI refused ${f.model} on ${whenOf(f.at)}${f.status?.let { " ($it)" }.orEmpty()}, so questions are going to ${f.fellBackTo} instead. It said: ${f.message}", bad = true)
          }
          s.candidatesError?.let { Note("Could not list your OpenAI models: $it", bad = true) }
          val choices = (listOf(s.model) + s.candidates).distinct()
          FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in choices) {
              val tags = listOfNotNull("in use".takeIf { m == s.model }, "default".takeIf { m == s.default })
              FilterChip(selected = pick == m, onClick = { pick = m }, enabled = !busy, label = { Text(if (tags.isEmpty()) m else "$m (${tags.joinToString(", ")})") })
            }
          }
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { run("Testing $pick…") { val p = app.admin.testRouter(pick); note(describe(p), !p.ok) } }) { Text("Test") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                  run("Testing $pick before switching…") {
                    val r = app.admin.useRouter(pick)
                    r.error?.let { throw Exception(it) }
                    state = r
                    val d = r.probe?.let(::describe).orEmpty()
                    if (r.saved == true) note("Now using $pick. $d Takes effect within a minute.") else note("Not switched. $d", true)
                  }
                },
            ) {
              Text("Use this model")
            }
            OutlinedButton(
                enabled = !busy && s.source == "ui",
                onClick = { run("Resetting…") { val r = app.admin.resetRouter(); state = r; pick = r.model; note("Back to ${r.model}. Takes effect within a minute.") } },
            ) {
              Text("Reset to ${s.envModel ?: s.default}")
            }
          }
        }
        Note(say, bad)
      }
    }
  }
}

/* ---------- Usage and cost -------------------------------------------------- */

private fun money(d: Double) =
    when {
      d == 0.0 -> "$0"
      d < 0.01 -> "under 1¢"
      d < 10 -> String.format(Locale.US, "$%.2f", d)
      else -> String.format(Locale.US, "$%.0f", d)
    }

private fun minutes(s: Double) = if (s < 90) "${s.toInt()} s" else "${(s / 60).toInt()} min"

private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

@Composable
fun UsagePage(app: JarvisApp, back: () -> Unit) {
  var usage by remember { mutableStateOf<Usage?>(null) }
  var names by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
  var problem by remember { mutableStateOf("") }
  LaunchedEffect(Unit) {
    runCatching { app.admin.usage() }.onSuccess { usage = it }.onFailure { problem = "Could not load usage: ${it.message}" }
    // Names are a nicety; the totals stand without them.
    runCatching { app.admin.members() }.onSuccess { m -> names = m.members.associate { (if (it.first) "owner" else it.id) to it.name } }
  }
  SubPage("Usage and cost", back) {
    item {
      Section("Usage and cost") {
        Note("What Jarvis has cost you this month, and how it answered. Estimates at OpenAI's published prices; OpenAI's own usage page is the bill. Hearing and speaking are not counted yet.")
        Note(problem, bad = true)
        val u = usage ?: return@Section
        val t = u.total
        val month = runCatching { LocalDate.parse("${u.month}-01").format(DateTimeFormatter.ofPattern("MMMM yyyy")) }.getOrDefault(u.month)
        Line(month, "")
        Text(
            if (t.questions > 0) "${plural(t.questions, "question")}, ${t.byHouse} answered by Home Assistant for nothing (${100 * t.byHouse / t.questions}%)." else "No questions yet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val parts = mutableListOf("${money(t.cost.router)} answering")
        if (t.liveSeconds > 0) parts += "${money(t.cost.live)} live (${minutes(t.liveSeconds)})"
        if (t.jobs > 0) parts += "${money(t.cost.jobs)} in ${plural(t.jobs, "job")}"
        Text(
            "Cost so far: about ${money(t.cost.total)}: ${parts.joinToString(", ")}." + if (t.unpriced > 0) " Plus ${t.unpriced} tokens from a model with no listed price." else "",
            style = MaterialTheme.typography.bodyMedium,
        )
        u.budget?.let { b ->
          val resets = runCatching { LocalDate.parse("${u.month}-01").plusMonths(1).format(DateTimeFormatter.ofPattern("d MMMM")) }.getOrDefault("")
          Text("Budget: ${(100 * t.cost.total / b).toInt()}% of ${money(b)} used; it resets on $resets. You are told at 80% and at 100%.", style = MaterialTheme.typography.bodyMedium)
        }
        val people = u.byPerson.orEmpty().entries.toList()
        if (people.size > 1 || (people.size == 1 && people[0].key != "owner")) {
          val each =
              people.sortedByDescending { it.value.cost.total }.joinToString("; ") { (who, p) ->
                "${names[who] ?: if (who == "owner") "you" else "someone removed"} ${money(p.cost.total)} (${plural(p.questions, "question")})"
              }
          Text("By person: $each.", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Today: ${plural(u.today.questions, "question")}, about ${money(u.today.cost.total)}.", style = MaterialTheme.typography.bodyMedium)
        u.medianMs?.let { m ->
          val n = u.recent.count { it.seconds == null && it.surface != "job" && it.surface != "image" }
          Text(String.format(Locale.US, "Typical answer: %.1f s, over the last %d questions.", m / 1000, n), style = MaterialTheme.typography.bodyMedium)
        }
        if (u.slowest.isNotEmpty()) {
          Text("Slowest recently", style = MaterialTheme.typography.titleMedium)
          for (e in u.slowest) {
            val who = if (e.by == "home-assistant") "Home Assistant" else "the AI" + (e.hops?.let { ", ${plural(it, "step")}" } ?: "")
            Note(String.format(Locale.US, "%.1f s · %s · \"%s\" · %s", e.ms / 1000.0, e.surface, e.ask, who))
          }
        }
        if (u.pricesAsOf.isNotEmpty()) Note("Prices as of ${u.pricesAsOf}.")
      }
    }
  }
}

/* ---------- MCP servers ----------------------------------------------------- */

private fun JsonObject.text(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.with(k: String, v: kotlinx.serialization.json.JsonElement?) = JsonObject(if (v == null) this - k else this + (k to v))

@Composable
fun McpPage(app: JarvisApp, back: () -> Unit) {
  val scope = rememberCoroutineScope()
  val c = Even.colors
  val servers = remember { mutableStateListOf<JsonObject>() }
  val results = remember { mutableStateListOf<String>() }
  var top by remember { mutableStateOf("") }
  var source by remember { mutableStateOf("") }
  var say by remember { mutableStateOf("") }
  var bad by remember { mutableStateOf(false) }

  fun note(t: String, wrong: Boolean = false) {
    say = t
    bad = wrong
  }

  suspend fun load() {
    top = "Connecting to each server…"
    runCatching { app.admin.mcp() }
        .onSuccess { r ->
          servers.clear()
          servers.addAll(r.servers)
          results.clear()
          results.addAll(r.servers.map { "" })
          source = r.source
          top = r.liveError?.let { "Could not reach a server: $it" } ?: "${plural(r.liveTools.size, "tool")} available: ${r.liveTools.joinToString(", ") { it.name }}"
        }
        .onFailure { top = it.message ?: "" }
  }

  LaunchedEffect(Unit) { load() }

  SubPage("MCP servers", back) {
    item {
      Section("MCP servers") {
        Note("Tools Jarvis can call directly. A header written as \${NAME} is filled from a Worker secret and never stored here.")
        if (source == "default") Note("These are the built-in defaults from the repo; nothing has been saved here. To turn a server off, switch it off and save — removing every server brings the defaults back.")
        Note(top, bad = top.startsWith("Could not"))
      }
    }
    itemsIndexed(servers) { i, s ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.text("label").ifEmpty { "New server" }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch((s["enabled"] as? JsonPrimitive)?.contentOrNull != "false", { on -> servers[i] = s.with("enabled", JsonPrimitive(on)) })
          }
          OutlinedTextField(s.text("label"), { servers[i] = s.with("label", JsonPrimitive(it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          OutlinedTextField(s.text("url"), { servers[i] = s.with("url", JsonPrimitive(it)) }, label = { Text("Address (https://…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          val headers = (s["headers"] as? JsonObject) ?: JsonObject(emptyMap())
          OutlinedTextField(
              headers.text("Authorization"),
              { v -> servers[i] = s.with("headers", headers.with("Authorization", v.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive))) },
              label = { Text("Authorization (optional)") },
              singleLine = true,
              visualTransformation = if (headers.text("Authorization").contains("\${")) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
              modifier = Modifier.fillMaxWidth(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                  results[i] = "Connecting…"
                  scope.launch {
                    val body = JsonObject(mapOf("label" to JsonPrimitive(s.text("label")), "url" to JsonPrimitive(s.text("url")), "headers" to headers))
                    runCatching { app.admin.testMcp(body) }
                        .onSuccess { r ->
                          val allowed = (s["allowedTools"] as? kotlinx.serialization.json.JsonArray)?.map { (it as JsonPrimitive).content }
                          results[i] =
                              if (!r.ok) "Failed: ${r.error}"
                              else "Reachable: ${r.toolCount} tools offered over ${r.transport} in ${r.ms}ms. " +
                                  (if (allowed != null) "Jarvis is allowed ${allowed.size} of them: ${allowed.joinToString(", ")}." else "No allowlist, so Jarvis may use all of them: ${r.tools.joinToString(", ")}.")
                        }
                        .onFailure { results[i] = "Failed: ${it.message}" }
                  }
                },
            ) {
              Text("Test")
            }
            OutlinedButton(onClick = { servers.removeAt(i); results.removeAt(i) }) { Text("Remove") }
          }
          results.getOrNull(i)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Failed")) c.danger else c.dim) }
        }
      }
    }
    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
              onClick = {
                servers.add(JsonObject(mapOf("label" to JsonPrimitive(""), "url" to JsonPrimitive(""), "enabled" to JsonPrimitive(true), "headers" to JsonObject(emptyMap()))))
                results.add("")
              },
          ) {
            Text("Add server")
          }
          OutlinedButton(
              onClick = {
                note("Saving…")
                scope.launch {
                  runCatching { app.admin.saveMcp(servers.toList()) }
                      .onSuccess { r ->
                        if (r.retype.isNotEmpty()) note("Saved, but not the token for ${r.retype.joinToString(", ")}: a saved token is only sent to the address it was saved for, so type it again for the new one.", true)
                        else note("Saved.")
                        load()
                      }
                      .onFailure { note(it.message ?: "Not saved", true) }
                }
              },
          ) {
            Text("Save")
          }
        }
        Note(say, bad)
      }
    }
  }
}

/* ---------- Skills ------------------------------------------------------------ */

/** "on" every skill has its key, "need" one waits for it, "off" none, as the web app marks the section. */
fun skillsState(all: List<Skill>?) = when {
  all == null -> ""
  all.isEmpty() -> "off"
  all.all { it.hasKey } -> "on"
  else -> "need"
}

@Composable
fun SkillsPage(app: JarvisApp, back: () -> Unit) {
  val scope = rememberCoroutineScope()
  val c = Even.colors
  var skills by remember { mutableStateOf<List<Skill>?>(null) }
  val typed = remember { mutableStateMapOf<String, String>() }
  // The skill whose Remove was pressed once, waiting for the second press.
  var sure by remember { mutableStateOf<String?>(null) }
  var say by remember { mutableStateOf("") }
  var bad by remember { mutableStateOf(false) }

  fun note(t: String, wrong: Boolean = false) {
    say = t
    bad = wrong
  }

  suspend fun load() {
    runCatching { app.admin.skills() }.onSuccess { skills = it.skills }.onFailure { note("Could not read the skills: ${it.message}", true) }
  }

  LaunchedEffect(Unit) { load() }

  SubPage("Skills", back) {
    item {
      Section("Skills") {
        Note(
            "Outside services Jarvis sends requests to: a bot, an automation, your own code. Add one in chat, with its address: " +
                "\"add a skill: Nova, my Grok bot, for the house and the car, at https://…\". Its key goes here, never in chat, and is " +
                "never shown again; a skill is used once it has one. One that answers later sends its answer to POST /api/v1/notify, " +
                "with an alerts-only token from Devices. MCP servers added in chat are under MCP servers.",
        )
      }
    }
    val all = skills
    if (all == null) item { Note(say.ifEmpty { "Loading…" }, bad) }
    else if (all.isEmpty()) item { Note("None yet.") }
    items(all.orEmpty(), key = { it.id }) { s ->
      Tile(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (s.hasKey) "key set" else "needs its key", style = MaterialTheme.typography.labelSmall, color = if (s.hasKey) c.on else c.danger)
          }
          if (s.`when`.isNotEmpty()) Text(s.`when`, style = MaterialTheme.typography.bodyMedium)
          val facts = listOf(s.url, if (s.later) "answers later, as a message" else "answers at once") + listOfNotNull(s.keyHeader?.let { "key also in $it" }) + s.fields.map { (k, v) -> "$k: $v" }
          Note(facts.joinToString(" · "))
          OutlinedTextField(
              typed[s.id].orEmpty(),
              { typed[s.id] = it },
              placeholder = { Text(if (s.hasKey) "A new key, to replace it" else "Its key") },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
              modifier = Modifier.fillMaxWidth(),
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                  note("Saving…")
                  scope.launch {
                    runCatching { app.admin.skillKey(s.id, typed[s.id].orEmpty().trim()) }
                        .onSuccess {
                          typed.remove(s.id)
                          note("${s.name}'s key is saved.")
                          load()
                        }
                        .onFailure { note(it.message ?: "Not saved", true) }
                  }
                },
            ) {
              Text("Save key")
            }
            OutlinedButton(
                onClick = {
                  if (sure != s.id) {
                    sure = s.id
                    return@OutlinedButton
                  }
                  sure = null
                  scope.launch {
                    runCatching { app.admin.removeSkill(s.id) }
                        .onSuccess {
                          typed.remove(s.id)
                          note("${s.name} is removed.")
                          load()
                        }
                        .onFailure { note(it.message ?: "Not removed", true) }
                  }
                },
            ) {
              Text(if (sure == s.id) "Remove it?" else "Remove")
            }
          }
        }
      }
    }
    if (all != null) item { Note(say, bad) }
  }
}

/* ---------- Where alerts went (drawn inside the Alerts section) -------------- */

@Composable
fun AlertsOverviewBlock(app: JarvisApp) {
  val scope = rememberCoroutineScope()
  var o by remember { mutableStateOf<AlertsOverview?>(null) }
  var say by remember { mutableStateOf("") }
  var tick by remember { mutableStateOf(0) }
  LaunchedEffect(tick) { runCatching { app.admin.alerts() }.onSuccess { o = it }.onFailure { say = "Could not load where alerts have gone." } }
  val v = o
  Section("Jarvis open right now") {
    if (v != null) {
      if (v.live.isEmpty()) Note("No screen has Jarvis open.")
      for (s in v.live) Line(s.label, if (s.visible) "in front of someone" else "in the background")
    } else Note(say, bad = say.isNotEmpty())
  }
  if (v == null) return
  Section("Notifications go to") {
    if (v.push.isEmpty()) Note("No device has turned notifications on.")
    for (p in v.push) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(p.label, style = MaterialTheme.typography.bodyMedium)
          val last = p.okAt?.let { "last delivered ${whenOf(it)}" } ?: "nothing sent yet"
          Note((if (p.who != "owner") "device · " else "") + last + if (p.failures > 0) " · ${p.failures} refused" else "")
        }
        OutlinedButton(onClick = { scope.launch { runCatching { app.admin.removePush(p.id) }.onSuccess { tick++ }.onFailure { say = "Not removed: ${it.message}" } } }) { Text("Remove") }
      }
    }
    Note(say, bad = true)
  }
  Section("Recent alerts") {
    if (v.recent.isEmpty()) Note("None yet. Press Test below to send one.")
    for (r in v.recent.take(5)) {
      val text = if (r.hidden) "someone else's, not shown" else (r.alert.text ?: r.alert.title).orEmpty().let { if (it.length > 70) it.take(70) + "…" else it }
      val how = r.deliveredBy?.let { "by $it" } ?: if (r.attempts.isNotEmpty()) "not delivered" else "nowhere to send it"
      Text("${whenOf(r.alert.at)} · $text", style = MaterialTheme.typography.bodyMedium)
      Note(how)
    }
  }
}

/* ---------- Export ------------------------------------------------------------ */

/** Saves the export into Downloads, as the web app downloads it. */
private fun saveToDownloads(ctx: Context, name: String, json: String) {
  val values = ContentValues().apply {
    put(MediaStore.Downloads.DISPLAY_NAME, name)
    put(MediaStore.Downloads.MIME_TYPE, "application/json")
  }
  val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads isn't available")
  ctx.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
}

@Composable
fun ExportPage(app: JarvisApp, back: () -> Unit) {
  val scope = rememberCoroutineScope()
  val ctx = LocalContext.current
  var say by remember { mutableStateOf("") }
  var bad by remember { mutableStateOf(false) }
  SubPage("Export your data", back) {
    item {
      Section("Export your data") {
        Note("Your memory, specialties, past attempts, routines, panels, conversation and jobs, and the household's settings without any key or token, as one JSON file.")
        OutlinedButton(
            onClick = {
              say = "Gathering it…"
              bad = false
              scope.launch {
                runCatching {
                      val json = app.admin.export()
                      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveToDownloads(ctx, "jarvis-export-${LocalDate.now()}.json", json) }
                    }
                    .onSuccess { say = "Saved to Downloads." }
                    .onFailure { say = "Could not export: ${it.message}"; bad = true }
              }
            },
        ) {
          Text("Download")
        }
        Note(say, bad)
      }
    }
  }
}

/** "Jarvis 2.4.0 · up to date", or a newer release's link, as the web app's settings menu ends. */
@Composable
fun VersionLine(app: JarvisApp) {
  val ctx = LocalContext.current
  var v by remember { mutableStateOf<app.jarvis.glasses.hub.VersionInfo?>(null) }
  LaunchedEffect(Unit) { v = runCatching { app.admin.version() }.getOrNull() }
  val info = v ?: return
  val latest = info.latest
  val text =
      "Jarvis ${info.version}" +
          when {
            info.update && latest != null -> " · ${latest.version} is out — what changed"
            info.repo != null && latest != null && info.error == null -> " · up to date"
            else -> ""
          }
  Text(
      text,
      style = MaterialTheme.typography.bodySmall,
      color = Even.colors.dim,
      modifier =
          if (info.update && latest != null) Modifier.clickable { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.url))) } else Modifier,
  )
}
