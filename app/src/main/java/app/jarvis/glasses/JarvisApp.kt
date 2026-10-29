package app.jarvis.glasses

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import app.jarvis.glasses.audio.GlassesAudio
import app.jarvis.glasses.chat.ChatLog
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.g2.G2Alerts
import app.jarvis.glasses.g2.G2Link
import app.jarvis.glasses.glasses.GlassesLink
import app.jarvis.glasses.glasses.GlassesState
import app.jarvis.glasses.glasses.cameOff
import app.jarvis.glasses.glasses.folded
import app.jarvis.glasses.jarvis.Address
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.AlertSocket
import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.alerts.AlertNotifier
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
  lateinit var chat: ChatLog
  lateinit var conversation: Conversation
  lateinit var live: LiveVoice
  lateinit var alerts: AlertSocket
  lateinit var hub: Hub
  lateinit var admin: Admin
  lateinit var notifier: AlertNotifier
  lateinit var pairing: Pairing
  /** Even Realities G2 glasses, for alerts (Android 13 and later: the Bluetooth calls it makes). */
  var g2: G2Alerts? = null

  override fun onCreate() {
    super.onCreate()
    prefs = Prefs(this)
    audio = GlassesAudio(this)
    link = GlassesLink(this, scope)
    client = JarvisClient({ prefs.url }, { prefs.token }, { prefs.gatewayHeaders() }, { prefs.origin })
    hub = Hub(client)
    chat = ChatLog(scope, hub::conversation, hub::keep, hub::clearConversation, person = { prefs.token.startsWith("jss1_") })
    conversation = Conversation(scope, audio, audio, link, client, chat, followUps = { prefs.followUps })
    live = LiveVoice(this, client, audio, scope, quietMs = { prefs.liveQuietSeconds * 1000L })
    alerts = AlertSocket(client, scope, ::onAlert)
    admin = Admin(client)
    notifier = AlertNotifier(this, client, chat, scope)
    pairing = Pairing({ prefs.url }, { prefs.gatewayHeaders() }, client.http)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g2 = G2Alerts(scope, G2Link(this), client, chat, followUps = { prefs.followUps })
    // In front of someone: the glasses say an alert, or a notification shows it. Jarvis waits for
    // this app's word only when it is, so without it every alert also went out by push.
    scope.launch { audio.connected.collect { refreshPresence() } }
    // The glasses taken off or folded: a conversation in them ends at once, Talk's or Live's, and
    // nothing more is heard. Whoever took them off may be talking to someone else. Only the change
    // ends one (cameOff): Talk started with them already folded uses the phone, on purpose.
    scope.launch {
      var before = link.glasses.value
      // What the glasses say of themselves, as it changes: for checking what the toolkit reports.
      fun said(l: List<GlassesState>) = l.map { "${it.name}: ${it.link}, worn ${it.worn}, folded ${it.folded}" }
      link.glasses.collect { now ->
        if (said(now) != said(before)) Log.i(TAG, "glasses: ${said(now)}")
        if (cameOff(before, now) && (conversation.isBusy || live.state.value != LiveVoice.State.OFF)) {
          Log.i(TAG, "glasses taken off: the conversation ends")
          conversation.stop()
          live.stop()
        }
        before = now
      }
    }
    scope.launch { runCatching { chat.refresh() } }
  }

  /**
   * "Hey Meta, open Jarvis", Talk: a live session when Live voice is on, falling back to a
   * push-to-talk conversation when Jarvis can't open one (no OpenAI key, say). Either goes on from
   * Chat's conversation, read again meanwhile for what was said on the person's other screens.
   */
  fun talk() {
    if (conversation.isBusy || live.state.value != LiveVoice.State.OFF) return
    scope.launch { runCatching { chat.refresh() } }
    if (!prefs.live) {
      conversation.start()
      return
    }
    live.start(
        chat.recent(),
        onFail = { conversation.start() },
        onSaid = { chat.keep(listOf(it)) },
    )
  }

  /** Tells Jarvis whether this app can put an alert in front of someone right now. */
  fun refreshPresence() = alerts.setVisible(audio.connected.value || notifier.canShow())

  /**
   * Jarvis speaking first. It is in Chat at once, as Jarvis keeps it. When Jarvis says to talk
   * about it ([Alert.talk]: the person's choices in Family → You → Alerts) and this phone may say
   * alerts, it starts a conversation in the glasses: said into a live session already open, or as
   * the Live voice setting has it, a live session that opens by saying it, else turn by turn.
   * Otherwise, or when the glasses are busy, a notification to reply to. Either way it is
   * acknowledged as seen, so Jarvis doesn't also send it elsewhere; only when Android blocks this
   * app's notifications does Jarvis carry on to its other ways.
   */
  private fun onAlert(alert: Alert): Boolean {
    // First, so the conversation it starts has it (a state update: safe from the socket's thread).
    chat.arrived(alert)
    val talk = alert.talk && prefs.speakAlerts
    if (live.state.value == LiveVoice.State.LIVE) return (talk && live.tell(alert)) || notifier.show(alert)
    // Not into glasses that aren't being worn: on a G2's display when one is with the phone, else
    // a notification.
    if (!talk || !audio.connected.value || folded(link.glasses.value) || live.state.value != LiveVoice.State.OFF || conversation.isBusy) {
      return (prefs.g2 && g2?.show(alert) { notifier.show(it) } == true) || notifier.show(alert)
    }
    val started =
        if (prefs.live) {
          live.start(chat.recent(), onFail = { conversation.start(opening = alert) }, onSaid = { chat.keep(listOf(it)) }, opening = alert)
        } else conversation.start(opening = alert)
    return started || notifier.show(alert)
  }
}

private const val TAG = "JarvisApp"

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

  /**
   * This phone may say Jarvis's alerts in the glasses and listen for a reply: those Jarvis says to
   * talk about ([Alert.talk]), which the person chooses for all their screens in Jarvis.
   */
  var speakAlerts: Boolean
    get() = p.getBoolean("speakAlerts", true)
    set(v) = p.edit().putBoolean("speakAlerts", v).apply()

  /** Alerts are shown on Even Realities G2 glasses when they are with the phone (G2Alerts). */
  var g2: Boolean
    get() = p.getBoolean("g2", true)
    set(v) = p.edit().putBoolean("g2", v).apply()

  /** This phone's own id among the person's screens, made once: the `origin` of its questions. */
  val origin: String
    get() =
        p.getString("origin", null)
            ?: ("p_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)).also { p.edit().putString("origin", it).apply() }

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
