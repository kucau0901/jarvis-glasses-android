package app.jarvis.tv.jarvis

import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The TV signed in to Jarvis as a person: pairing with a code, the Home panels, asking out loud,
 * and keeping what was said in the shared Chat history. Everything is read from [prefs] at each
 * call, so a new address or sign-in takes effect at once.
 *
 * Every call is cancellable: leaving a screen stops its request rather than leaving it to run out
 * (a reading Jarvis looks up can take a minute and a half). Failures are [JarvisException], with
 * the HTTP status, or 0 when Jarvis couldn't be reached.
 */
class Jarvis(private val prefs: TvPrefs, private val http: OkHttpClient = defaultHttp()) {
  /** What went wrong, in Jarvis's own words when it gave some. [status] 401: signed out; 423: the profile is locked; 0: not reached. */
  class JarvisException(message: String, val status: Int, cause: Throwable? = null) : Exception(message, cause) {
    val signedOut: Boolean
      get() = status == 401

    val locked: Boolean
      get() = status == 423
  }

  /** A pairing code to show, and the secret it is polled with (never shown, never logged). */
  data class PairCode(val code: String, val poll: String, val expiresAt: Long) {
    override fun toString() = "PairCode(code=$code, expiresAt=$expiresAt)"
  }

  sealed interface Paired {
    data class Approved(val token: String, val name: String) : Paired {
      override fun toString() = "Approved(name=$name)"
    }

    data object Expired : Paired
  }

  // Pairing (docs/api.md, "People, not devices"): the TV shows a code, and someone signed in to
  // Jarvis approves it from the web app's menu, Family, "Sign in another screen".

  suspend fun pairStart(): PairCode {
    val o = obj(exec(builder("/api/auth/pair/start", signed = false).post(body(buildJsonObject { put("label", "Jarvis TV") })).build()))
    return PairCode(
        o.str("code") ?: throw JarvisException("Jarvis sent no code", 200),
        o.str("poll") ?: throw JarvisException("Jarvis sent no code", 200),
        (o["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
    )
  }

  /**
   * Polls every [every] ms until [code] is approved, which signs the TV in ([prefs] keeps the token
   * and name), or runs out. A dropped connection, a busy server (429, Jarvis allows 20 polls a
   * minute from one address) or a passing 5xx is tried again until the code would have expired, as
   * the web does: the person may be approving it right then.
   */
  suspend fun pairAwait(code: PairCode, every: Long = 4_000): Paired {
    while (true) {
      val o =
          try {
            obj(exec(builder("/api/auth/pair/poll", signed = false).post(body(buildJsonObject { put("poll", code.poll) })).build()))
          } catch (e: JarvisException) {
            if (e.status != 0 && e.status != 429 && e.status < 500) throw e
            if (code.expiresAt > 0 && System.currentTimeMillis() > code.expiresAt) return Paired.Expired
            null
          }
      when (o?.str("status")) {
        "approved" -> {
          val token = o.str("token")?.takeIf { it.isNotBlank() } ?: throw JarvisException("Jarvis approved the code but sent no sign-in", 200)
          val name = o.str("name").orEmpty()
          prefs.token = token
          prefs.name = name
          return Paired.Approved(token, name)
        }
        "expired" -> return Paired.Expired
      }
      delay(every)
    }
  }

  // Home panels. Nothing pushes a change: poll [panels] every 20 s or so while Home is up.

  suspend fun panels(): PanelsReply = decode(exec(builder("/api/hub/panels").get().build()))

  /**
   * A tile's reading. [cached]: the one Jarvis kept, at once (`stale` when it is due again);
   * without, Jarvis reads it now, which for one it looks up can take a minute and a half.
   */
  suspend fun read(panel: String, key: String, cached: Boolean): Reading =
      decode(exec(builder("/api/hub/panels/read?panel=${enc(panel)}&key=${enc(key)}" + if (cached) "&cached=1" else "").get().build()))

  /** Presses one of a thing's buttons; [value] as words, the way the web sends it ("007" stays "007"). Returns what Jarvis said. */
  suspend fun act(panel: String, key: String, action: String, member: String? = null, value: String? = null): String =
      decode<ActReply>(exec(builder("/api/hub/panels/act").post(body(actBody(panel, key, action, member, value))).build())).text

  /** Takes [panel] off Home, for this person on every screen. Returns its title. */
  suspend fun removePanel(panel: String): String =
      obj(exec(builder("/api/hub/panels/remove").post(body(buildJsonObject { put("panel", panel) })).build())).str("removed").orEmpty()

  /** A note panel: [add] a line, [tick] one (its row key), or [clear] the ticked ones. */
  suspend fun note(panel: String, add: String? = null, tick: String? = null, clear: Boolean = false) {
    val b = buildJsonObject {
      put("panel", panel)
      add?.let { put("add", it.trim().take(120)) }
      tick?.let { put("tick", it) }
      if (clear) put("clear", true)
    }
    exec(builder("/api/hub/panels/note").post(body(b)).build())
  }

  // Asking

  /**
   * Asks [text] with the conversation so far ([context]), as events while they come: what Jarvis
   * does, what it shows, the answer, then the answer spoken. Cold: each collect asks again, and
   * stopping the collect hangs up. A failure before the answer starts is a [JarvisException]; an
   * answer that ends without its words ends with a [VoiceEvent.Result] saying so.
   */
  fun ask(text: String, context: List<Turn>): Flow<VoiceEvent> =
      flow {
            val req = builder("/api/v1/voice").post(body(askBody(text, context, prefs.origin))).header("Accept", "text/event-stream").build()
            var reached = false
            var answered = false
            coroutineScope {
              val call = http.newCall(req)
              val hangUp = hangUpOnCancel(call)
              try {
                call.execute().use { res ->
                  failure(res)?.let { throw it }
                  reached = true
                  val source = res.body?.source() ?: throw JarvisException("Jarvis sent no answer", res.code)
                  val frames = ArrayList<JsonObject>()
                  val parser = SseParser(frames::add)
                  while (true) {
                    val line = source.readUtf8Line()
                    if (line == null) parser.flush() else parser.line(line)
                    for (f in frames) {
                      val e = VoiceEvent.from(f) ?: continue
                      if (e is VoiceEvent.Result) answered = true
                      emit(e)
                    }
                    frames.clear()
                    if (line == null) break
                  }
                }
              } catch (e: IOException) {
                ensureActive()
                // Lost during the spoken part: the answer is in, and its sentences so far still play.
                if (!answered) throw JarvisException(if (reached) "Lost Jarvis mid-answer" else CANT_REACH, 0, e)
              } finally {
                hangUp.cancel()
              }
            }
            if (!answered) emit(VoiceEvent.Result("Jarvis's answer ended early.", true))
          }
          .flowOn(Dispatchers.IO)

  /** Keeps a spoken exchange in the person's Chat history, which the voice route never writes. Best effort: a failure is dropped. */
  suspend fun remember(user: String, assistant: String) {
    val said = buildJsonArray {
      if (user.isNotBlank()) add(buildJsonObject { put("id", saidId()); put("role", "user"); put("text", user.trim().take(4000)) })
      if (assistant.isNotBlank()) add(buildJsonObject { put("id", saidId()); put("role", "assistant"); put("text", assistant.trim().take(4000)) })
    }
    if (said.isEmpty()) return
    try {
      exec(builder("/api/hub/conversation").post(body(buildJsonObject { put("said", said) })).build())
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // The answer was heard; a lost copy in Chat isn't worth a word on the TV.
    }
  }

  // Pictures for a Display, from Jarvis's own proxies so no Maps key or Home Assistant token reaches the TV

  /** Where [d]'s picture is, [w]×[h] for a map; null for a page, the browser ([browserScreen]) or "close", which have none. */
  fun pictureUrl(d: VoiceEvent.Display, w: Int = 960, h: Int = 540): String? {
    val path =
        when (d.kind) {
          "map", "streetview" -> {
            val lat = d.lat ?: return null
            val lng = d.lng ?: return null
            val at = "lat=${"%.5f".format(Locale.US, lat)}&lng=${"%.5f".format(Locale.US, lng)}"
            val type = if (d.kind == "map") "&maptype=${enc(d.maptype ?: "roadmap")}" else ""
            "/api/map?kind=${d.kind}&$at&sv=$MAP_STYLE&w=$w&h=$h$type"
          }
          "camera" -> "/api/camera?entity=${enc(d.entity ?: return null)}&h=$h"
          "image" -> "/api/image?id=${enc(d.id ?: return null)}"
          else -> return null
        }
    return prefs.url + path
  }

  /**
   * The screen of the browser Jarvis works in, as it is now (GET /api/browser/screen; docs/api.md,
   * "The browser's screen"): asked for about twice a second while it is on the TV. To look at only:
   * the TV sends the browser nothing, a phone presses and types. Needs the `browser` scope; Jarvis
   * says in words when no browser is set up (404) or it does not answer (503).
   */
  suspend fun browserScreen(): BrowserScreen = decode(exec(builder("/api/browser/screen").get().build()))

  /** The sign-in and gateway headers, for loading a picture some other way. Secret: never log them. */
  fun headers(): Map<String, String> = buildMap {
    put("Authorization", "Bearer ${prefs.token}")
    putAll(prefs.gateway())
  }

  /** A picture from [url] ([pictureUrl]), with the sign-in. */
  suspend fun picture(url: String): ByteArray {
    val u = url.toHttpUrlOrNull() ?: throw JarvisException("That picture's address isn't right", 0)
    if (!sameJarvis(u)) throw JarvisException("That picture isn't Jarvis's", 0)
    val b = Request.Builder().url(u).get()
    for ((k, v) in headers()) b.header(k, v)
    return exec(b.build()) { it.body?.bytes() }?.takeIf { it.isNotEmpty() } ?: throw JarvisException("Jarvis sent no picture", 200)
  }

  // Plumbing

  private fun builder(path: String, signed: Boolean = true): Request.Builder {
    val base = prefs.url
    if (!usable(base)) throw JarvisException("Jarvis's address must start with https://", 0)
    val url = (base + path).toHttpUrlOrNull() ?: throw JarvisException("Jarvis's address isn't right", 0)
    val b = Request.Builder().url(url)
    if (signed) b.header("Authorization", "Bearer ${prefs.token}")
    for ((k, v) in prefs.gateway()) b.header(k, v)
    return b
  }

  /** The sign-in only ever goes to the Jarvis in [prefs]. */
  private fun sameJarvis(u: HttpUrl): Boolean {
    val base = prefs.url.toHttpUrlOrNull() ?: return false
    return u.scheme == base.scheme && u.host == base.host && u.port == base.port
  }

  private suspend fun exec(req: Request): String = exec(req) { it.body?.string().orEmpty() }

  /** Runs [req] on the IO threads, hanging up if the caller is cancelled; [read] gets a successful reply. */
  private suspend fun <T> exec(req: Request, read: (Response) -> T): T =
      withContext(Dispatchers.IO) {
        val call = http.newCall(req)
        val hangUp = hangUpOnCancel(call)
        try {
          call.execute().use { res ->
            failure(res)?.let { throw it }
            read(res)
          }
        } catch (e: IOException) {
          ensureActive()
          throw JarvisException(CANT_REACH, 0, e)
        } finally {
          hangUp.cancel()
        }
      }

  /** A blocking call isn't interrupted by cancellation: this cancels [call] when the scope is. */
  private fun CoroutineScope.hangUpOnCancel(call: okhttp3.Call) =
      launch(start = CoroutineStart.UNDISPATCHED) {
        try {
          awaitCancellation()
        } finally {
          call.cancel()
        }
      }

  private fun failure(res: Response): JarvisException? {
    if (res.isSuccessful) return null
    val body = runCatching { res.body?.string() }.getOrNull().orEmpty()
    return JarvisException(errorOf(res.code, body, res.header("Location"), hasAccess = prefs.gateway().isNotEmpty()), res.code)
  }

  private inline fun <reified T> decode(text: String): T =
      try {
        JSON.decodeFromString(text.ifEmpty { "{}" })
      } catch (e: SerializationException) {
        throw JarvisException("Jarvis sent something the TV can't read", 200, e)
      } catch (e: IllegalArgumentException) {
        throw JarvisException("Jarvis sent something the TV can't read", 200, e)
      }

  private fun obj(text: String): JsonObject =
      runCatching { JSON.parseToJsonElement(text.ifEmpty { "{}" }).jsonObject }.getOrElse { throw JarvisException("Jarvis sent something the TV can't read", 200, it) }

  companion object {
    /** The web's map style (MAP_STYLE_VERSION in Jarvis), part of the address so all screens share a cached map. */
    const val MAP_STYLE = 3

    private const val CANT_REACH = "Can't reach Jarvis"
    private val JSON_TYPE = "application/json".toMediaType()
    private val ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val random = SecureRandom()

    val JSON = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    fun defaultHttp(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // An answer that uses tools can take a while before its first frame.
            .readTimeout(120, TimeUnit.SECONDS)
            // Cloudflare Access answers a missing service token with a redirect to its sign-in page,
            // which would otherwise arrive as a 200 of HTML.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    /**
     * Which addresses the TV will send its sign-in to: any https, and plain http only on the home
     * network (a Jarvis in Docker at 192.168.x.x:8787), never across the internet in the clear.
     */
    fun usable(url: String): Boolean {
      val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
      val host = uri.host?.lowercase() ?: return false
      return when (uri.scheme?.lowercase()) {
        "https" -> true
        "http" -> isLocal(host)
        else -> false
      }
    }

    private fun isLocal(host: String): Boolean {
      if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
      val parts = host.split('.').map { it.toIntOrNull() ?: return false }
      if (parts.size != 4 || parts.any { it !in 0..255 }) return false
      val (a, b) = parts
      return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }

    /** Jarvis's own words for a failure when it gave some (`{"error": …}`), else what the status means. */
    fun errorOf(code: Int, body: String, location: String? = null, hasAccess: Boolean = true): String {
      val said =
          runCatching { (JSON.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
      if (said != null) return said
      return when {
        code == 401 -> "The TV is signed out of Jarvis"
        code == 423 -> "This Jarvis profile is locked"
        code == 429 -> "Too many requests just now"
        code in 300..399 && location.orEmpty().contains("cloudflareaccess.com") ->
            if (hasAccess) "Cloudflare Access turned the TV away: check its access id and secret"
            else "Jarvis is behind Cloudflare Access, and this TV has no token for it yet: send it the phone's from the Mac"
        code == 403 -> "Jarvis said no (check the Cloudflare Access id and secret)"
        else -> "Jarvis answered $code"
      }
    }

    /** The question as `/api/v1/voice` takes it, spoken back as mp3, with this TV as the screen asking. */
    fun askBody(text: String, context: List<Turn>, origin: String): JsonObject = buildJsonObject {
      put("text", text)
      put("context", buildJsonArray { for (t in context) add(buildJsonObject { put("role", t.role); put("text", t.text) }) })
      put("reply", "audio")
      put("format", "mp3")
      put("screen", true)
      put("origin", buildJsonObject { put("id", origin); put("label", "TV") })
    }

    fun actBody(panel: String, key: String, action: String, member: String?, value: String?): JsonObject = buildJsonObject {
      put("panel", panel)
      put("key", key)
      put("action", action)
      member?.let { put("member", it) }
      value?.let { put("value", it) }
    }

    /** An id for a line of Chat: "t" and 20 letters or digits. */
    fun saidId(): String = "t" + (1..20).map { ALNUM[random.nextInt(ALNUM.length)] }.joinToString("")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun body(o: JsonObject) = o.toString().toRequestBody(JSON_TYPE)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
  }
}
