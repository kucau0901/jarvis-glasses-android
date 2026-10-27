package app.jarvis.glasses.jarvis

import app.jarvis.glasses.conversation.Brain
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The device API of a Jarvis deployment (docs/api.md in the jarvis-voice-agent repository),
 * with a device token holding `ask`, `voice` and `alerts`.
 *
 * A question is two calls: [transcribe] hears the recording (so the app can tell "stop" or "what am
 * I looking at" before paying for an answer), then [ask] sends the words, and a photo when one was
 * taken, and streams the answer back sentence by sentence. Hearing is paid once either way: a text
 * question skips Jarvis's own hearing.
 */
class JarvisClient(
    private val baseUrl: () -> String,
    private val token: () -> String,
    /** Extra headers for a Jarvis behind Cloudflare Access (its service token); empty for most. */
    private val gateway: () -> Map<String, String> = { emptyMap() },
    /** This phone's own id, sent as the `origin` of each question; blank for none. */
    private val originId: () -> String = { "" },
    val http: OkHttpClient = defaultHttp(),
) : Brain {
  class JarvisException(message: String) : IOException(message)

  /** What was heard in a WAV recording; blank when nothing was. */
  override suspend fun transcribe(wav: ByteArray): String =
      withContext(Dispatchers.IO) {
        val body =
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("audio", "question.wav", wav.toRequestBody(WAV))
                .addFormDataPart("options", """{"transcribe":true}""")
                .build()
        val req = request("/api/v1/voice").post(body).header("Accept", "application/json").build()
        http.newCall(req).execute().use { res ->
          val text = res.body?.string().orEmpty()
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, text))
          val o = SseParser.json.parseToJsonElement(text).jsonObject
          (o["transcript"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        }
      }

  /**
   * Asks [text] with the conversation so far, streaming events to [onEvent] as they come: progress,
   * then the answer, then its audio a sentence at a time. Returns the answer.
   */
  override suspend fun ask(
      text: String,
      context: List<Turn>,
      photoJpeg: ByteArray?,
      onEvent: suspend (VoiceEvent) -> Unit,
  ): VoiceEvent.Answer =
      withContext(Dispatchers.IO) {
        var answer: VoiceEvent.Answer? = null
        stream("/api/v1/voice", askBody(text, context, photoJpeg, origin())) { o ->
          val e = VoiceEvent.from(o) ?: return@stream
          if (e is VoiceEvent.Answer) answer = e
          onEvent(e)
        }
        answer ?: throw JarvisException("Jarvis's answer ended early")
      }

  /**
   * The work behind a live session's delegation: the conversation so far to `/api/v1/stream`,
   * whose events ([LiveRelay] reads them) are streamed to [onEvent].
   */
  suspend fun delegate(delegationId: String, transcript: List<Turn>, onEvent: suspend (JsonObject) -> Unit) =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject {
          put("delegationId", delegationId)
          put("transcript", turnsJson(transcript))
          origin()?.let { put("origin", it) }
        }
        stream("/api/v1/stream", body, onEvent)
      }

  /**
   * A typed question, as the web app's Chat tab asks it: the conversation so far, a photo if
   * attached, streamed back as events (progress, result with links, draft, error).
   */
  suspend fun chat(transcript: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (JsonObject) -> Unit) =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject {
          put("delegationId", "typed")
          put("transcript", turnsJson(transcript))
          put("surface", "chat")
          origin()?.let { put("origin", it) }
          if (photoJpeg != null) {
            put("images", JsonArray(listOf(JsonPrimitive("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(photoJpeg)))))
          }
        }
        stream("/api/v1/stream", body, onEvent)
      }

  /**
   * Which of the person's screens asked (lib/shared.ts in Jarvis): Jarvis tells this phone what was
   * said lately on their others, and leaves out what it said itself. Whose they are is the sign-in's.
   */
  private fun origin(): JsonObject? =
      originId().takeIf { it.isNotBlank() }?.let { id -> buildJsonObject { put("id", id); put("label", "Phone") } }

  /** Sends a Gmail draft Jarvis wrote (its `draft` event); needs `mail`. */
  suspend fun sendDraft(id: String) =
      withContext(Dispatchers.IO) {
        val req = request("/api/google/drafts/send").post(buildJsonObject { put("id", id) }.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res ->
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
        }
      }

  /** A GPT-Live session's answer, and with an opening, the event that has Jarvis say it ([liveSession]). */
  data class LiveAnswer(val sdp: String, val first: String? = null)

  /**
   * Opens a GPT-Live session: Jarvis sends this offer to OpenAI with its own key and returns the
   * answer's SDP, so no key reaches the phone. Needs `OPENAI_API_KEY` on the Jarvis side.
   *
   * With [opening], a session opened to say that alert and hear the reply: Jarvis puts it last in
   * the history and answers with `first`, the event to send on `oai-events` once it opens (GPT-Live
   * cannot speak first from the server; docs/api.md, "Live voice", in jarvis-voice-agent).
   */
  suspend fun liveSession(offerSdp: String, history: List<Turn>, opening: Alert? = null): LiveAnswer =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject {
          put("sdp", offerSdp)
          put("history", turnsJson(history))
          put("client", "mobile")
          if (opening != null) put("opening", buildJsonObject { put("id", opening.id); put("title", opening.title); put("text", opening.text) })
        }
        val req = request("/api/session").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res ->
          val text = res.body?.string().orEmpty()
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, text))
          val o = SseParser.json.parseToJsonElement(text).jsonObject
          val sdp = (o["sdp"] as? JsonPrimitive)?.contentOrNull ?: throw JarvisException("Jarvis sent no session")
          LiveAnswer(sdp, (o["first"] as? JsonObject)?.toString())
        }
      }

  /** How long a live session was open, for Jarvis's Usage page (it bills by the minute). */
  suspend fun reportLive(seconds: Int) {
    withContext(Dispatchers.IO) {
      val body = buildJsonObject { put("seconds", seconds) }
      runCatching { http.newCall(request("/api/v1/usage/live").post(body.toString().toRequestBody(JSON)).build()).execute().close() }
    }
  }

  private suspend fun stream(path: String, body: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
    val req = request(path).post(body.toString().toRequestBody(JSON)).header("Accept", "text/event-stream").build()
    http.newCall(req).execute().use { res ->
      if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
      val frames = mutableListOf<JsonObject>()
      val parser = SseParser(frames::add)
      val source = res.body?.source() ?: throw JarvisException("Jarvis sent no answer")
      while (true) {
        val line = source.readUtf8Line()
        if (line == null) parser.flush() else parser.line(line)
        for (f in frames) onEvent(f)
        frames.clear()
        if (line == null) break
      }
    }
  }

  /**
   * Jarvis's voice for [text] (an alert), as mp3; null when Settings says the device speaks
   * itself (`204`).
   */
  override suspend fun speech(text: String): ByteArray? =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("text", text.take(1000)) }
        val req = request("/api/tts").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { res ->
          if (res.code == 204) return@use null
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
          res.body?.bytes()
        }
      }

  /** Checks the address and token: `GET /api/v1/voice` needs `voice` and answers with the speech settings. */
  suspend fun check() =
      withContext(Dispatchers.IO) {
        http.newCall(request("/api/v1/voice").get().build()).execute().use { res ->
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
        }
      }

  internal fun request(path: String): Request.Builder {
    if (!Address.usable(baseUrl())) throw JarvisException("Set your Jarvis address in Settings")
    val b = Request.Builder().url(baseUrl().trimEnd('/') + path).header("Authorization", "Bearer ${token()}")
    for ((k, v) in gateway()) b.header(k, v)
    return b
  }

  companion object {
    private val JSON = "application/json".toMediaType()
    private val WAV = "audio/wav".toMediaType()

    fun defaultHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // An answer that uses tools can take a while before its first frame.
            .readTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

    /** The JSON body of a text question: `{text, context, reply, format, images?}`. */
    fun askBody(text: String, context: List<Turn>, photoJpeg: ByteArray?, origin: JsonObject? = null): JsonObject = buildJsonObject {
      put("text", text)
      put("context", turnsJson(context))
      put("reply", "audio")
      put("format", "mp3")
      if (origin != null) put("origin", origin)
      if (photoJpeg != null) {
        put(
            "images",
            JsonArray(listOf(JsonPrimitive("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(photoJpeg)))),
        )
      }
    }

    private fun turnsJson(turns: List<Turn>) = buildJsonArray {
      for (t in turns) add(buildJsonObject { put("role", t.role); put("text", t.text) })
    }

    /** Jarvis's own words for a failure when it gave some (`{"error": …}`), else the status. */
    fun errorOf(code: Int, body: String): String {
      val said =
          runCatching { SseParser.json.parseToJsonElement(body).jsonObject }
              .getOrNull()
              ?.let { (it["error"] as? JsonPrimitive)?.contentOrNull ?: (it["text"] as? JsonPrimitive)?.contentOrNull }
      return when (code) {
        401 -> "Jarvis didn't accept the device token"
        403 -> "The device token lacks a scope this needs (ask, voice, alerts)"
        423 -> "That Jarvis profile is locked"
        429 -> "Too many requests for this device just now"
        else -> said ?: "Jarvis answered $code"
      }
    }
  }
}
