package app.jarvis.tv.jarvis

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * What the TV keeps about its Jarvis: where it is, who the TV is signed in as (a session token from
 * pairing, `jss1_…`), and the Cloudflare Access service token that lets it past the gateway in
 * front of `/api/hub/…` and `/api/auth/…`. App-private, and the app allows no backup.
 */
class TvPrefs internal constructor(private val store: SharedPreferences) {
  constructor(context: Context) : this(context.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE))

  /** Jarvis's address, without a trailing '/'; the default deployment when none was set. */
  var url: String
    get() = store.getString(URL, null)?.takeIf { it.isNotEmpty() } ?: DEFAULT_URL
    set(value) = put(URL, cleanUrl(value))

  var token: String
    get() = store.getString(TOKEN, null).orEmpty()
    set(value) = put(TOKEN, value.trim())

  /** Who the TV is signed in as, as Jarvis named them on approval. */
  var name: String
    get() = store.getString(NAME, null).orEmpty()
    set(value) = put(NAME, value.trim())

  var accessId: String
    get() = store.getString(ACCESS_ID, null).orEmpty()
    set(value) = put(ACCESS_ID, value.trim())

  var accessSecret: String
    get() = store.getString(ACCESS_SECRET, null).orEmpty()
    set(value) = put(ACCESS_SECRET, value.trim())

  /**
   * This TV as one of the person's screens (`origin.id` of each question): Jarvis tells it what was
   * said lately on their others, and leaves out what it said itself. Made once and kept.
   */
  val origin: String
    get() = store.getString(ORIGIN, null)?.takeIf { ORIGIN_SHAPE.matches(it) } ?: newOrigin().also { put(ORIGIN, it) }

  val signedIn: Boolean
    get() = token.isNotEmpty()

  /** Forgets the sign-in; the address, the gateway token and [origin] stay. */
  fun signOut() {
    store.edit().remove(TOKEN).remove(NAME).apply()
  }

  /** The Cloudflare Access headers for every request, when both halves are set. */
  fun gateway(): Map<String, String> = gatewayOf(accessId, accessSecret)

  private fun put(key: String, value: String) {
    store.edit().putString(key, value).apply()
  }

  companion object {
    const val DEFAULT_URL = "https://jarvis.example.com"

    private const val URL = "url"
    private const val TOKEN = "token"
    private const val NAME = "name"
    private const val ACCESS_ID = "accessId"
    private const val ACCESS_SECRET = "accessSecret"
    private const val ORIGIN = "origin"

    private val ORIGIN_SHAPE = Regex("t_[0-9a-f]{20}")

    /** An address as typed: no spaces, no trailing '/' ("https://jarvis.example.com/" is "https://jarvis.example.com"). */
    fun cleanUrl(raw: String): String = raw.trim().trimEnd('/')

    /** Cloudflare Access's two headers, or none: one half alone is never sent. */
    fun gatewayOf(id: String, secret: String): Map<String, String> {
      val i = id.trim()
      val s = secret.trim()
      return if (i.isEmpty() || s.isEmpty()) emptyMap() else mapOf("CF-Access-Client-Id" to i, "CF-Access-Client-Secret" to s)
    }

    /** "t_" and 20 lowercase hex digits. */
    fun newOrigin(random: java.util.Random = SecureRandom()): String =
        "t_" + (1..20).joinToString("") { Integer.toHexString(random.nextInt(16)) }
  }
}
