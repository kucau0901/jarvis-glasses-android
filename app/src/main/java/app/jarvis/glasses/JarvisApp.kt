package app.jarvis.glasses

import android.app.Application
import android.content.Context
import app.jarvis.glasses.audio.GlassesAudio
import app.jarvis.glasses.conversation.Conversation
import app.jarvis.glasses.glasses.GlassesLink
import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.AlertSocket
import app.jarvis.glasses.jarvis.JarvisClient
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
  lateinit var alerts: AlertSocket

  override fun onCreate() {
    super.onCreate()
    prefs = Prefs(this)
    audio = GlassesAudio(this)
    link = GlassesLink(this, scope)
    client = JarvisClient({ prefs.url }, { prefs.token })
    conversation = Conversation(scope, audio, audio, link, client, followUps = { prefs.followUps })
    alerts = AlertSocket(client, scope, ::onAlert)
    // Jarvis counts this app as a screen in front of someone only while the glasses are connected.
    scope.launch { audio.connected.collect { alerts.setVisible(it) } }
  }

  /**
   * Jarvis speaking first. Said in the glasses, then listened for a reply, when they are connected
   * and no conversation is going; otherwise not acknowledged, so Jarvis carries on to the phone's
   * notifications as it would without this app.
   */
  private fun onAlert(alert: Alert): Boolean {
    if (!prefs.speakAlerts || !alert.speak || !audio.connected.value) return false
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

  /** Say Jarvis's alerts in the glasses and listen for a reply. */
  var speakAlerts: Boolean
    get() = p.getBoolean("speakAlerts", true)
    set(v) = p.edit().putBoolean("speakAlerts", v).apply()

  val isSetUp: Boolean
    get() = url.startsWith("https://") && token.isNotEmpty()
}
