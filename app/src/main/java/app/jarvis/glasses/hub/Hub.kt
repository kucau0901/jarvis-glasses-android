package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.jarvis.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What a person signed in to Jarvis can do beyond talking (docs/api.md, "People, not devices", and
 * the routes it names): Home panels, chat history, family messages, approvals, jobs and routines.
 * `/api/hub/…` answers only a person, so with a device token those calls fail with [NotAPerson].
 */
class Hub(private val client: JarvisClient) {
  /** `/api/hub/…` refused a device token: sign in with a code to use it. */
  class NotAPerson : Exception("Sign in with a code to see this")

  /** A locked session (423): someone's PIN is needed. */
  class Locked : Exception("This Jarvis profile is locked")

  suspend fun me(): Me = get("/api/hub/me")

  // Home panels

  suspend fun panels(): PanelsReply = get("/api/hub/panels")

  suspend fun act(panel: String, key: String, action: String, member: String? = null, value: String? = null, code: String? = null): String =
      post<ActReply>(
              "/api/hub/panels/act",
              buildJsonObject {
                put("panel", panel)
                put("key", key)
                put("action", action)
                member?.let { put("member", it) }
                value?.let { v -> v.toDoubleOrNull()?.let { put("value", it) } ?: put("value", v) }
                code?.let { put("code", it) }
              },
          )
          .text

  suspend fun tickNote(panel: String, key: String) {
    post<JsonObject>("/api/hub/panels/note", buildJsonObject { put("panel", panel); put("tick", key) })
  }

  // Chat history, shared with the web app's Chat tab

  suspend fun conversation(): List<Said> = get<Conversation>("/api/hub/conversation").said

  suspend fun keep(said: List<Said>) {
    if (said.isEmpty()) return
    post<JsonObject>(
        "/api/hub/conversation",
        buildJsonObject {
          put(
              "said",
              buildJsonArray {
                for (s in said.take(10)) {
                  add(
                      buildJsonObject {
                        put("id", s.id)
                        put("role", s.role)
                        put("text", s.text.take(4000))
                        s.links?.let { links ->
                          put("links", buildJsonArray { for (l in links.take(6)) add(buildJsonObject { put("url", l.url); put("title", l.title) }) })
                        }
                      },
                  )
                }
              },
          )
        },
    )
  }

  // Family messages

  suspend fun chats(): ChatList = get("/api/hub/chat")

  suspend fun messages(convo: String, since: Long = 0): List<ChatMessage> =
      get<Messages>("/api/hub/chat/messages?c=${enc(convo)}&since=$since").messages

  suspend fun send(convo: String, text: String): ChatMessage =
      post<Sent>("/api/hub/chat/messages", buildJsonObject { put("c", convo); put("text", text) }).message

  // Approvals

  suspend fun proposals(): Proposals = get("/api/hub/proposals")

  suspend fun answer(id: String, answer: String) {
    post<JsonObject>("/api/hub/proposals/answer", buildJsonObject { put("id", id); put("answer", answer) })
  }

  // Jobs and routines (device API, also open to a person)

  suspend fun jobs(): List<Job> = get<Jobs>("/api/v1/jobs").jobs

  suspend fun job(id: String): Job = get<OneJob>("/api/v1/jobs?id=${enc(id)}").job

  suspend fun startJob(task: String): Job = post<OneJob>("/api/v1/jobs", buildJsonObject { put("task", task) }).job

  suspend fun cancelJob(id: String) {
    post<JsonObject>("/api/v1/jobs/cancel", buildJsonObject { put("id", id) })
  }

  suspend fun routines(): List<Routine> = get<Routines>("/api/v1/routines").routines

  suspend fun setRoutine(id: String, enabled: Boolean) {
    send<JsonObject>("PATCH", "/api/v1/routines", buildJsonObject { put("id", id); put("enabled", enabled) })
  }

  suspend fun runRoutine(id: String) {
    post<JsonObject>("/api/v1/routines/run", buildJsonObject { put("id", id) })
  }

  /** A daily routine at [time] ("07:30"): say [text] as written, or ask Jarvis it ([ask]). */
  suspend fun addDaily(name: String, time: String, text: String, ask: Boolean) {
    post<JsonObject>(
        "/api/v1/routines",
        buildJsonObject {
          put("name", name)
          put("when", "daily")
          put("time", time)
          put(if (ask) "ask" else "say", text)
        },
    )
  }

  // Plumbing

  private suspend inline fun <reified T> get(path: String): T =
      withContext(Dispatchers.IO) { decode(call(client.request(path).get().build())) }

  private suspend inline fun <reified T> post(path: String, body: JsonObject): T = send("POST", path, body)

  private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject): T =
      withContext(Dispatchers.IO) { decode(call(client.request(path).method(method, body.toString().toRequestBody(JSON)).build())) }

  private fun call(req: Request): String =
      client.http.newCall(req).execute().use { res ->
        val text = res.body?.string().orEmpty()
        when {
          res.isSuccessful -> text
          res.code == 423 -> throw Locked()
          // "that is for a person, not a device"
          res.code == 403 && text.contains("for a person") -> throw NotAPerson()
          else -> throw JarvisClient.JarvisException(JarvisClient.errorOf(res.code, text))
        }
      }

  private inline fun <reified T> decode(text: String): T = JSON_IN.decodeFromString(text.ifEmpty { "{}" })

  private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

  companion object {
    private val JSON = "application/json".toMediaType()
    val JSON_IN = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    /** The last turns sent with a typed question: 20 within the last 3 hours, as the web app does. */
    fun transcript(history: List<Said>, now: Long): List<Turn> =
        history.filter { now - it.at < 3 * 3_600_000L || it.at == 0L }.takeLast(20).map { Turn(it.role, it.text) }
  }
}
