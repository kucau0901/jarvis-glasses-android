package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Jarvis's own settings, as the web app's Settings panel shows them (src/app/ui/Services.ts and
// routes/settings.ts in jarvis-voice-agent). Only a family admin or the owner key may use these;
// anyone else gets [Admin.NotAdmin], and the app hides the page.

@Serializable
data class SettingView(
    val name: String,
    val group: String,
    val kind: String,
    val label: String,
    val help: String = "",
    val required: Boolean = false,
    val options: List<String>? = null,
    val source: String = "unset",
    val display: String = "",
    val withheld: String? = null,
)

@Serializable
data class GroupView(
    val id: String,
    val title: String,
    val intro: String = "",
    val testable: Boolean = false,
    val configured: Boolean = false,
    val linked: Boolean? = null,
)

@Serializable data class OwnerKey(val fingerprint: String = "", val howToChange: String = "")

@Serializable
data class SettingsReply(
    val owner: OwnerKey = OwnerKey(),
    val storage: Boolean = true,
    val groups: List<GroupView> = emptyList(),
    val settings: List<SettingView> = emptyList(),
    val errors: Map<String, String>? = null,
    val telegram: String? = null,
)

@Serializable data class TestReply(val ok: Boolean = false, val detail: String? = null, val error: String? = null)

@Serializable data class AuthUrl(val url: String? = null, val error: String? = null)

// Router model (routes/router.ts)

@Serializable data class Fallback(val model: String = "", val fellBackTo: String = "", val at: Long = 0, val status: Int? = null, val message: String = "")

@Serializable
data class Probe(val ok: Boolean = false, val model: String = "", val stage: String? = null, val status: Int? = null, val error: String? = null, val toolMs: Long? = null, val chainMs: Long? = null)

@Serializable
data class RouterState(
    val model: String = "",
    val source: String = "default",
    val default: String = "",
    val envModel: String? = null,
    val listed: Boolean? = null,
    val candidates: List<String> = emptyList(),
    val candidatesError: String? = null,
    val fallback: Fallback? = null,
    val saved: Boolean? = null,
    val probe: Probe? = null,
    val error: String? = null,
)

// MCP servers (routes/mcp.ts): each server kept whole as JSON, so fields the app doesn't edit survive a save.

@Serializable data class Tool(val name: String, val description: String = "")

@Serializable
data class McpList(val servers: List<JsonObject> = emptyList(), val source: String = "saved", val liveTools: List<Tool> = emptyList(), val liveError: String? = null)

@Serializable data class McpSaved(val ok: Boolean = false, val servers: Int = 0, val retype: List<String> = emptyList())

@Serializable
data class McpTest(val ok: Boolean = false, val transport: String? = null, val ms: Long? = null, val toolCount: Int? = null, val tools: List<String> = emptyList(), val error: String? = null)

// Skills (routes/skills.ts): outside services Jarvis was asked, in chat, to send requests to. Never carries a key.

@Serializable
data class Skill(
    val id: String,
    val name: String = "",
    val url: String = "",
    val `when`: String = "",
    val later: Boolean = false,
    val keyHeader: String? = null,
    val fields: Map<String, String> = emptyMap(),
    val hasKey: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Its bot's own address, to ask Jarvis and message whoever made it. */
    val address: SkillAddress? = null,
)

/** A bot's own address, never its token: a hint of it ("jbt1_ab12…wxyz"), who made it, what it may reach, and when. */
@Serializable data class SkillAddress(val hint: String = "", val person: String = "", val reach: List<String> = emptyList(), val at: Long = 0)

/** The skills, and what a bot's address may be ticked to reach. */
@Serializable data class SkillList(val skills: List<Skill> = emptyList(), val reach: List<String> = emptyList())

@Serializable data class SkillSaved(val skill: Skill)

/** A new address, shown this once: the base URL with [token] as its Bearer key, or [url] alone. Jarvis keeps only its digest. */
@Serializable data class MadeAddress(val skill: Skill, val token: String, val base: String, val url: String)

// ChatGPT plan (routes/chatgpt.ts)

@Serializable data class PlanModel(val id: String, val name: String)

@Serializable
data class PlanView(
    val connected: Boolean = false,
    val pending: Boolean = false,
    val email: String? = null,
    val model: String? = null,
    val limitedUntil: Long? = null,
    val problem: String? = null,
    val key: Boolean = false,
    val models: List<PlanModel> = emptyList(),
    val modelsError: String? = null,
    val saved: Boolean? = null,
    val error: String? = null,
)

@Serializable data class StartUrl(val url: String = "")

// Where alerts go (routes/alerts.ts, handleAlertsAdmin)

@Serializable data class PushSub(val id: String, val label: String = "", val who: String = "", val service: String = "", val createdAt: Long = 0, val okAt: Long? = null, val failures: Int = 0)

@Serializable data class LiveScreen(val who: String = "", val label: String = "", val visible: Boolean = false, val since: Long = 0)

@Serializable data class AlertBrief(val id: String = "", val at: Long = 0, val title: String? = null, val text: String? = null, val source: String = "")

@Serializable data class Attempt(val channel: String = "", val ok: Boolean = false, val detail: String = "")

@Serializable data class RecentAlert(val alert: AlertBrief = AlertBrief(), val hidden: Boolean = false, val attempts: List<Attempt> = emptyList(), val deliveredBy: String? = null)

@Serializable data class AlertsOverview(val push: List<PushSub> = emptyList(), val live: List<LiveScreen> = emptyList(), val recent: List<RecentAlert> = emptyList())

// Usage and cost (lib/usage.ts)

@Serializable data class Cost(val router: Double = 0.0, val live: Double = 0.0, val jobs: Double = 0.0) {
  val total get() = router + live + jobs
}

@Serializable
data class Totals(val questions: Int = 0, val byHouse: Int = 0, val cost: Cost = Cost(), val liveSeconds: Double = 0.0, val jobs: Int = 0, val unpriced: Long = 0)

@Serializable
data class UsageEntry(val at: Long = 0, val surface: String = "", val by: String = "", val ms: Long = 0, val hops: Int? = null, val seconds: Double? = null, val ask: String = "")

@Serializable
data class Usage(
    val month: String = "",
    val total: Totals = Totals(),
    val today: Totals = Totals(),
    val byPerson: Map<String, Totals>? = null,
    val budget: Double? = null,
    val medianMs: Double? = null,
    val slowest: List<UsageEntry> = emptyList(),
    val recent: List<UsageEntry> = emptyList(),
    val pricesAsOf: String = "",
)

@Serializable data class Member(val id: String, val name: String, val first: Boolean = false)

@Serializable data class Members(val members: List<Member> = emptyList())

@Serializable data class Latest(val version: String, val url: String)

@Serializable data class VersionInfo(val version: String = "", val repo: String? = null, val latest: Latest? = null, val update: Boolean = false, val error: String? = null)

class Admin(private val client: JarvisClient) {
  /** Jarvis said only a family admin (or the owner key) may do this. */
  class NotAdmin : Exception("Only a family admin can change Jarvis's settings")

  /** Every setting, where it comes from, secrets masked. */
  suspend fun settings(): SettingsReply = decode(call("GET", "/api/settings", null).second)

  /**
   * Saves [changes]: a value to set, or null to clear one (the deployment value or default applies
   * again). A refusal comes back with [SettingsReply.errors] set, by setting name.
   */
  suspend fun save(changes: Map<String, String?>): SettingsReply {
    val body = buildJsonObject {
      put("changes", JsonObject(changes.mapValues { (_, v) -> v?.let { JsonPrimitive(it) } ?: JsonNull }))
    }
    return decode(call("PUT", "/api/settings", body, okAlso = 422).second)
  }

  /** A real check of a group, of [values] not yet saved if any. */
  suspend fun test(group: String, values: Map<String, String>): TestReply =
      decode(call("POST", "/api/settings/test", buildJsonObject { put("group", group); put("values", JsonObject(values.mapValues { JsonPrimitive(it.value) })) }, okAlso = 400).second)

  /** The sign-in page that links a Google or Spotify account, to open in the browser. */
  suspend fun linkUrl(group: String): String {
    val r: AuthUrl = decode(call("GET", "/api/$group/auth", null, okAlso = 400).second)
    return r.url ?: throw JarvisClient.JarvisException(r.error ?: "Jarvis gave no sign-in page")
  }

  // Router model

  suspend fun router(): RouterState = decode(call("GET", "/api/router", null).second)

  suspend fun testRouter(model: String): Probe = decode(call("POST", "/api/router/test", buildJsonObject { put("model", model) }, okAlso = 400).second)

  /** Tests [model] and switches to it only if it passes; [RouterState.saved] says which. */
  suspend fun useRouter(model: String): RouterState = decode(call("PUT", "/api/router", buildJsonObject { put("model", model) }, okAlso = 422).second)

  suspend fun resetRouter(): RouterState = decode(call("DELETE", "/api/router", null).second)

  // MCP servers

  suspend fun mcp(): McpList = decode(call("GET", "/api/mcp", null).second)

  suspend fun saveMcp(servers: List<JsonObject>): McpSaved = decode(call("PUT", "/api/mcp", buildJsonObject { put("servers", JsonArray(servers)) }).second)

  suspend fun testMcp(server: JsonObject): McpTest = decode(call("POST", "/api/mcp/test", server, okAlso = 400).second)

  // Skills: added and changed in chat; only the key and its bot's address are set here, and Jarvis says why it refuses one.

  suspend fun skills(): SkillList = decode(call("GET", "/api/skills", null).second)

  suspend fun skillKey(id: String, key: String): Skill = decode<SkillSaved>(call("PUT", "/api/skills", buildJsonObject { put("id", id); put("key", key) }).second).skill

  suspend fun removeSkill(id: String) {
    call("DELETE", "/api/skills?id=${URLEncoder.encode(id, "UTF-8")}", null)
  }

  /** An address of its own for its bot, reaching [reach], as whoever makes it; one made before stops. */
  suspend fun skillAddress(id: String, reach: List<String>): MadeAddress =
      decode(call("PATCH", "/api/skills", buildJsonObject { put("id", id); put("address", true); put("reach", reachOf(reach)) }).second)

  /** Its bot's address stopped. */
  suspend fun stopSkillAddress(id: String): Skill = decode<SkillSaved>(call("PATCH", "/api/skills", buildJsonObject { put("id", id); put("address", false) }).second).skill

  /** What its bot's address may reach, ticked again. */
  suspend fun skillReach(id: String, reach: List<String>): Skill = decode<SkillSaved>(call("PATCH", "/api/skills", buildJsonObject { put("id", id); put("reach", reachOf(reach)) }).second).skill

  private fun reachOf(reach: List<String>) = JsonArray(reach.map(::JsonPrimitive))

  // ChatGPT plan

  suspend fun plan(): PlanView = decode(call("GET", "/api/chatgpt", null).second)

  /** Starts a sign-in: the ChatGPT page to open, which ends on http://127.0.0.1:1455/auth/callback. */
  suspend fun planStart(): String = decode<StartUrl>(call("POST", "/api/chatgpt/start", null).second).url

  /** Finishes it with the address the sign-in ended on (or just its query). */
  suspend fun planFinish(address: String): PlanView = decode(call("POST", "/api/chatgpt/finish", buildJsonObject { put("address", address.take(4000)) }).second)

  /** A model for questions on the plan, tried on it first; null goes back to the router model. */
  suspend fun planModel(model: String?): PlanView =
      decode(call("PUT", "/api/chatgpt/model", buildJsonObject { put("model", model?.let { JsonPrimitive(it) } ?: JsonNull) }, okAlso = 422).second)

  suspend fun planDisconnect(): PlanView = decode(call("DELETE", "/api/chatgpt", null).second)

  // Alerts, usage, export, version

  suspend fun alerts(): AlertsOverview = decode(call("GET", "/api/alerts", null).second)

  suspend fun removePush(id: String) {
    call("DELETE", "/api/alerts?sub=$id", null)
  }

  suspend fun usage(): Usage = decode(call("GET", "/api/usage", null).second)

  suspend fun members(): Members = decode(call("GET", "/api/hub/members", null).second)

  /** Everything of the asker's, as one JSON document. */
  suspend fun export(): String = call("GET", "/api/export", null).second

  suspend fun version(): VersionInfo = decode(call("GET", "/api/version", null).second)

  private suspend fun call(method: String, path: String, body: JsonObject?, okAlso: Int = 0): Pair<Int, String> =
      withContext(Dispatchers.IO) {
        // OkHttp wants a body on every POST and PUT, even one that carries nothing.
        val payload = body?.toString()?.toRequestBody(JSON) ?: if (method == "POST" || method == "PUT") "".toRequestBody(JSON) else null
        val req: Request = client.request(path).method(method, payload).build()
        client.http.newCall(req).execute().use { res ->
          val text = res.body?.string().orEmpty()
          when {
            res.isSuccessful || res.code == okAlso -> res.code to text
            res.code == 403 && (text.contains("admin") || text.contains("owner")) -> throw NotAdmin()
            res.code == 403 && text.contains("for a person") -> throw Hub.NotAPerson()
            res.code == 423 -> throw Hub.Locked()
            else -> throw JarvisClient.JarvisException(JarvisClient.errorOf(res.code, text))
          }
        }
      }

  private inline fun <reified T> decode(text: String): T = Hub.JSON_IN.decodeFromString(text.ifEmpty { "{}" })

  companion object {
    private val JSON = "application/json".toMediaType()
  }
}
