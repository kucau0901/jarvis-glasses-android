package app.jarvis.glasses.jarvis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Signing the app in as a person (docs/api.md, "People, not devices"): the app asks for a code,
 * shows it, and someone already signed in to Jarvis approves it. The session token (`jss1_…`) then
 * acts as that person within their role: Home panels, chat history, messages and approvals, which
 * a device token never reaches.
 */
class Pairing(
    private val baseUrl: () -> String,
    private val gateway: () -> Map<String, String>,
    private val http: OkHttpClient,
) {
  data class Code(val code: String, val poll: String, val expiresAt: Long)

  sealed interface Outcome {
    data class Approved(val token: String, val name: String) : Outcome

    data object Expired : Outcome
  }

  suspend fun start(label: String): Code =
      withContext(Dispatchers.IO) {
        val o = post("/api/auth/pair/start", buildJsonObject { put("label", label) })
        Code(o.str("code") ?: error("no code"), o.str("poll") ?: error("no poll"), (o["expiresAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L)
      }

  /** Polls every few seconds until the code is approved (said once) or runs out (ten minutes). */
  suspend fun await(code: Code, every: Long = 3_000): Outcome {
    while (true) {
      val o = withContext(Dispatchers.IO) { post("/api/auth/pair/poll", buildJsonObject { put("poll", code.poll) }) }
      when (o.str("status")) {
        "approved" -> return Outcome.Approved(o.str("token") ?: error("approved without a token"), o.str("name").orEmpty())
        "expired" -> return Outcome.Expired
      }
      delay(every)
    }
  }

  private fun post(path: String, body: JsonObject): JsonObject {
    val b = Request.Builder().url(baseUrl().trimEnd('/') + path).post(body.toString().toRequestBody(JSON))
    for ((k, v) in gateway()) b.header(k, v)
    http.newCall(b.build()).execute().use { res ->
      val text = res.body?.string().orEmpty()
      if (!res.isSuccessful) throw JarvisClient.JarvisException(JarvisClient.errorOf(res.code, text))
      return SseParser.json.parseToJsonElement(text).jsonObject
    }
  }

  private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

  companion object {
    private val JSON = "application/json".toMediaType()
  }
}
