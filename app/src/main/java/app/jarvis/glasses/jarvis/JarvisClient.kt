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
        val body = askBody(text, context, photoJpeg)
        val req =
            request("/api/v1/voice")
                .post(body.toString().toRequestBody(JSON))
                .header("Accept", "text/event-stream")
                .build()
        var answer: VoiceEvent.Answer? = null
        http.newCall(req).execute().use { res ->
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
          val events = mutableListOf<VoiceEvent>()
          val parser = SseParser { o -> VoiceEvent.from(o)?.let(events::add) }
          val source = res.body?.source() ?: throw JarvisException("Jarvis sent no answer")
          while (true) {
            val line = source.readUtf8Line() ?: break
            parser.line(line)
            for (e in events) {
              if (e is VoiceEvent.Answer) answer = e
              onEvent(e)
            }
            events.clear()
          }
          parser.flush()
          for (e in events) {
            if (e is VoiceEvent.Answer) answer = e
            onEvent(e)
          }
        }
        answer ?: throw JarvisException("Jarvis's answer ended early")
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

  internal fun request(path: String): Request.Builder =
      Request.Builder().url(baseUrl().trimEnd('/') + path).header("Authorization", "Bearer ${token()}")

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
    fun askBody(text: String, context: List<Turn>, photoJpeg: ByteArray?): JsonObject = buildJsonObject {
      put("text", text)
      put(
          "context",
          buildJsonArray {
            for (t in context) add(buildJsonObject { put("role", t.role); put("text", t.text) })
          },
      )
      put("reply", "audio")
      put("format", "mp3")
      if (photoJpeg != null) {
        put(
            "images",
            JsonArray(listOf(JsonPrimitive("data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(photoJpeg)))),
        )
      }
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
