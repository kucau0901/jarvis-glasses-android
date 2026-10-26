package app.jarvis.glasses

import android.app.Application
import android.content.Context
import app.jarvis.glasses.audio.GlassesAudio
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.glasses.GlassesLink
import app.jarvis.glasses.jarvis.Address
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.AlertSocket
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.hub.Admin
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.jarvis.Pairing
import app.jarvis.glasses.live.LiveVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The app's one set of parts, shared by the screen and the service. */
class JarvisApp : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  lateinit var prefs: Prefs
  lateinit var audio: GlassesAudio
  lateinit var link: GlassesLink
  lateinit var client: JarvisClient
  lateinit var conversation: Conversation
  lateinit var live: LiveVoice
  lateinit var alerts: AlertSocket
  lateinit var hub: Hub
  lateinit var admin: Admin
  lateinit var pairing: Pairing

  override fun onCreate() {
    super.onCreate()
    prefs = Prefs(this)
    audio = GlassesAudio(this)
    link = GlassesLink(this, scope)
    client = JarvisClient({ prefs.url }, { prefs.token }, { prefs.gatewayHeaders() })
    conversation = Conversation(scope, audio, audio, link, client, followUps = { prefs.followUps })
    live = LiveVoice(this, client, audio, scope, quietMs = { prefs.liveQuietSeconds * 1000L })
    alerts = AlertSocket(client, scope, ::onAlert)
    hub = Hub(client)
    admin = Admin(client)
    pairing = Pairing({ prefs.url }, { prefs.gatewayHeaders() }, client.http)
    // Jarvis counts this app as a screen in front of someone only while the glasses are connected.
    scope.launch { audio.connected.collect { alerts.setVisible(it) } }
  }

  /**
   * "Hey Meta, open Jarvis", Talk: a live session when Live voice is on, falling back to a
   * push-to-talk conversation when Jarvis can't open one (no OpenAI key, say).
   */
  fun talk() {
    if (conversation.isBusy || live.state.value != LiveVoice.State.OFF) return
    if (!prefs.live) {
      conversation.start()
      return
    }
    live.start(
        conversation.recent(),
        onFail = { conversation.start() },
        onEnd = conversation::adopt,
    )
  }

  /**
   * Jarvis speaking first. Said in the glasses, then listened for a reply, when they are connected
   * and nothing else is going; otherwise not acknowledged, so Jarvis carries on to the phone's
   * notifications as it would without this app.
   */
  private fun onAlert(alert: Alert): Boolean {
    if (!prefs.speakAlerts || !alert.speak || !audio.connected.value) return false
    if (live.state.value != LiveVoice.State.OFF) return false
    return conversation.start(opening = alert)
  }
}

val Context.jarvis: JarvisApp
  get() = applicationContext as JarvisApp

/** What the owner sets on the phone: the Jarvis address and its device token, and two switches. */
class Prefs(context: Context) {
  private val p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

  var url: String
    get() = p.getString("url", "").orEmpty()
    set(v) = p.edit().putString("url", v.trim().trimEnd('/')).apply()

  var token: String
    get() = p.getString("token", "").orEmpty()
    set(v) = p.edit().putString("token", v.trim()).apply()

  /** After an answer, listen again for a follow-up. */
  var followUps: Boolean
    get() = p.getBoolean("followUps", true)
    set(v) = p.edit().putBoolean("followUps", v).apply()

  /** Talk through a GPT-Live session instead of question by question (needs Jarvis's OpenAI key, billed by the minute). */
  var live: Boolean
    get() = p.getBoolean("live", false)
    set(v) = p.edit().putBoolean("live", v).apply()

  /** Seconds of quiet before a live session hangs up (it bills by the minute). */
  var liveQuietSeconds: Int
    get() = p.getInt("liveQuietSeconds", 10)
    set(v) = p.edit().putInt("liveQuietSeconds", v).apply()

  /** "system", "light" or "dark": the app's look, chosen in Settings. */
  var theme: String
    get() = p.getString("theme", "system").orEmpty()
    set(v) = p.edit().putString("theme", v).apply()

  /** Say Jarvis's alerts in the glasses and listen for a reply. */
  var speakAlerts: Boolean
    get() = p.getBoolean("speakAlerts", true)
    set(v) = p.edit().putBoolean("speakAlerts", v).apply()

  /** Who the app is signed in as, after pairing ("" for a device token). */
  var signedInAs: String
    get() = p.getString("signedInAs", "").orEmpty()
    set(v) = p.edit().putString("signedInAs", v).apply()

  /** Cloudflare Access service token, for a Jarvis behind Access; both empty otherwise. */
  var accessId: String
    get() = p.getString("accessId", "").orEmpty()
    set(v) = p.edit().putString("accessId", v.trim()).apply()

  var accessSecret: String
    get() = p.getString("accessSecret", "").orEmpty()
    set(v) = p.edit().putString("accessSecret", v.trim()).apply()

  fun gatewayHeaders(): Map<String, String> =
      if (accessId.isEmpty() || accessSecret.isEmpty()) emptyMap()
      else mapOf("CF-Access-Client-Id" to accessId, "CF-Access-Client-Secret" to accessSecret)

  val isSetUp: Boolean
    get() = Address.usable(url) && token.isNotEmpty()
}
