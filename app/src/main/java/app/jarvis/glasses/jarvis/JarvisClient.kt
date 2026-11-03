package app.jarvis.glasses.jarvis

import app.jarvis.glasses.conversation.Brain
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import okhttp3.Response

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
        exchange(req) { res ->
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
      openMic: Boolean,
      onEvent: suspend (VoiceEvent) -> Unit,
  ): VoiceEvent.Answer = ask(text, context, photoJpeg, null, openMic, onEvent = onEvent)

  /**
   * [chars] set: the answer is for a small display rather than spoken, written to fit that many
   * characters, and comes as text only. Without it the question was heard by the glasses'
   * microphone; [openMic] when it opened again after an answer, for a follow-up ([askBody]).
   * [from]: asked on another of the person's screens through this phone (the watch, [origin]).
   */
  suspend fun ask(
      text: String,
      context: List<Turn>,
      photoJpeg: ByteArray?,
      chars: Int?,
      openMic: Boolean = false,
      from: JsonObject? = null,
      onEvent: suspend (VoiceEvent) -> Unit,
  ): VoiceEvent.Answer =
      withContext(Dispatchers.IO) {
        var answer: VoiceEvent.Answer? = null
        stream("/api/v1/voice", askBody(text, context, photoJpeg, from ?: origin(), chars, openMic)) { o ->
          val e = VoiceEvent.from(o) ?: return@stream
          if (e is VoiceEvent.Answer) answer = e
          onEvent(e)
        }
        answer ?: throw JarvisException("Jarvis's answer ended early")
      }

  /**
   * The work behind a live session's delegation: the conversation so far to `/api/v1/stream`,
   * whose events ([LiveRelay] reads them) are streamed to [onEvent]. Said as heard by an open
   * microphone (`openMic`): when nothing in it was said to Jarvis, its result says `notForMe`.
   */
  suspend fun delegate(delegationId: String, transcript: List<Turn>, onEvent: suspend (JsonObject) -> Unit) =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject {
          put("delegationId", delegationId)
          put("transcript", turnsJson(transcript))
          put("openMic", true)
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
  private fun origin(): JsonObject? = originId().takeIf { it.isNotBlank() }?.let { origin(it, "Phone") }

  /** Sends a Gmail draft Jarvis wrote (its `draft` event); needs `mail`. */
  suspend fun sendDraft(id: String) =
      withContext(Dispatchers.IO) {
        val req = request("/api/google/drafts/send").post(buildJsonObject { put("id", id) }.toString().toRequestBody(JSON)).build()
        exchange(req) { res ->
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
   *
   * [glasses]: heard through the glasses, whose microphone hears everyone nearby. A Jarvis that
   * knows them ([knowsGlasses]) is told so (`client: "glasses"`), and the session stays silent
   * unless spoken to; any other is told "mobile", as an older one takes what it doesn't know for a
   * car.
   */
  suspend fun liveSession(offerSdp: String, history: List<Turn>, opening: Alert? = null, glasses: Boolean = false): LiveAnswer =
      withContext(Dispatchers.IO) {
        val body = buildJsonObject {
          put("sdp", offerSdp)
          put("history", turnsJson(history))
          put("client", if (glasses && knowsGlasses()) "glasses" else "mobile")
          if (opening != null) put("opening", buildJsonObject { put("id", opening.id); put("title", opening.title); put("text", opening.text) })
        }
        val req = request("/api/session").post(body.toString().toRequestBody(JSON)).build()
        exchange(req) { res ->
          val text = res.body?.string().orEmpty()
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, text))
          val o = SseParser.json.parseToJsonElement(text).jsonObject
          val sdp = (o["sdp"] as? JsonPrimitive)?.contentOrNull ?: throw JarvisException("Jarvis sent no session")
          LiveAnswer(sdp, (o["first"] as? JsonObject)?.toString())
        }
      }

  // What /api/health said of the version, by address: asked once, and again only after no answer.
  @Volatile private var health: Pair<String, String>? = null

  /** Whether this Jarvis is [GLASSES_SINCE] or later, so knows a live session's `client: "glasses"`. */
  internal suspend fun knowsGlasses(): Boolean {
    val url = baseUrl()
    val version =
        health?.takeIf { it.first == url }?.second
            ?: try {
              // Not long: a live session waits on it.
              withTimeoutOrNull(HEALTH_MS) {
                exchange(request("/api/health").get().build()) { res ->
                  val text = res.body?.string().orEmpty()
                  when {
                    // A Jarvis from before it.
                    res.code == 404 -> ""
                    // No answer from Jarvis itself (it restarting, a gateway's error page): asked again next time.
                    !res.isSuccessful -> null
                    else -> runCatching { SseParser.json.parseToJsonElement(text).jsonObject }.getOrNull()?.let { (it["version"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
                  }
                }
              }
            } catch (_: IOException) {
              null
            }?.also { health = url to it }
    return atLeast(version.orEmpty(), GLASSES_SINCE)
  }

  /** How long a live session was open, for Jarvis's Usage page (it bills by the minute). */
  suspend fun reportLive(seconds: Int) {
    withContext(Dispatchers.IO) {
      val body = buildJsonObject { put("seconds", seconds) }
      runCatching { exchange(request("/api/v1/usage/live").post(body.toString().toRequestBody(JSON)).build()) {} }
    }
  }

  private suspend fun stream(path: String, body: JsonObject, onEvent: suspend (JsonObject) -> Unit) {
    val req = request(path).post(body.toString().toRequestBody(JSON)).header("Accept", "text/event-stream").build()
    exchange(req) { res ->
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
        exchange(req) { res ->
          if (res.code == 204) return@exchange null
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
          res.body?.bytes()
        }
      }

  /** Checks the address and token: `GET /api/v1/voice` needs `voice` and answers with the speech settings. */
  suspend fun check() =
      withContext(Dispatchers.IO) {
        exchange(request("/api/v1/voice").get().build()) { res ->
          if (!res.isSuccessful) throw JarvisException(errorOf(res.code, res.body?.string().orEmpty()))
        }
      }

  /**
   * [req] sent and its answer read, the call cancelled as soon as the coroutine is: a blocking read
   * doesn't see cancellation by itself, so a question asked before the glasses came off was read to
   * its end and answered, with Jarvis never hung up on.
   */
  private suspend fun <T> exchange(req: Request, read: suspend (Response) -> T): T = coroutineScope {
    val call = http.newCall(req)
    // Undispatched, so it is waiting before the call goes out, even if cancelled already.
    val watch = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
    try {
      call.execute().use { read(it) }
    } catch (e: IOException) {
      // The cancelled call's own failure is the cancellation, not Jarvis out of reach.
      ensureActive()
      throw e
    } finally {
      watch.cancel()
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
    /** The first Jarvis with `client: "glasses"` and `openMic`. */
    const val GLASSES_SINCE = "2.8.0"
    private const val HEALTH_MS = 3_000L

    /** One of the person's screens, as a question's `origin` names it: its id, and what to call it. */
    fun origin(id: String, label: String): JsonObject = buildJsonObject { put("id", id); put("label", label) }

    /** Whether [version] ("2.8.0", "v2.10", "2.8.0-rc.1") is [least] or later; false when it can't be read. */
    fun atLeast(version: String, least: String): Boolean {
      fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() }
      val have = parts(version)
      val want = parts(least)
      if (have.any { it == null }) return false
      for (i in 0 until maxOf(have.size, want.size)) {
        val a = have.getOrNull(i) ?: 0
        val b = want.getOrNull(i) ?: 0
        if (a != b) return a > b
      }
      return true
    }

    fun defaultHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // An answer that uses tools can take a while before its first frame.
            .readTimeout(120, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

    /**
     * The JSON body of a text question: `{text, context, reply, format, openMic?, images?}`. A
     * spoken follow-up says `openMic` ([openMic]): the microphone opened again after an answer and
     * hears everyone nearby, so Jarvis answers only what was said to it, and otherwise
     * [VoiceEvent.Answer.notForMe]. The opening question and a reply to an alert don't: Jarvis takes
     * them as said to it. With [chars] (the G2) Jarvis assumes `openMic` already.
     */
    fun askBody(text: String, context: List<Turn>, photoJpeg: ByteArray?, origin: JsonObject? = null, chars: Int? = null, openMic: Boolean = false): JsonObject = buildJsonObject {
      put("text", text)
      put("context", turnsJson(context))
      if (chars != null) {
        put("reply", "text")
        put("chars", chars)
      } else {
        put("reply", "audio")
        put("format", "mp3")
        if (openMic) put("openMic", true)
      }
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
