package app.jarvis.wear

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The watch app's one set of parts, shared by its two screens (the app's, and the assistant's). */
class WatchApp : Application() {
  val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  lateinit var prefs: WatchPrefs
  lateinit var phone: DataLayerPhone
  lateinit var speaker: Speaker
  lateinit var asking: Asking
  lateinit var wrist: Wrist

  override fun onCreate() {
    super.onCreate()
    prefs = WatchPrefs(this)
    phone = DataLayerPhone(this)
    speaker = Speaker(this)
    asking = Asking(scope, phone, origin = { prefs.origin }, speak = { prefs.speak }, play = speaker::play, say = speaker::say, hush = speaker::stop)
    wrist = Wrist(phone, prefs, changed = { Faces.refresh(this) })
  }

  // Jarvis's screens on show: the app's and the assistant's, each in its own task. Going from one
  // to the other starts the next before the last stops, so the phone is listened to, and a question
  // kept, until neither is.
  private var started = 0

  fun screenStarted() {
    if (started++ == 0) phone.open()
  }

  /** Away from Jarvis altogether: nothing more said, and replies no longer listened for. */
  fun screenStopped() {
    if (--started > 0) return
    started = 0
    asking.stop()
    phone.close()
  }
}

val Context.watch: WatchApp
  get() = applicationContext as WatchApp

class WatchPrefs(context: Context) : Wrist.Keep {
  private val p = context.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

  override var things: String?
    get() = p.getString("things", null)
    set(v) = p.edit().putString("things", v).apply()

  override var approvals: Int
    get() = p.getInt("approvals", 0)
    set(v) = p.edit().putInt("approvals", v).apply()

  override var jobs: Int
    get() = p.getInt("jobs", 0)
    set(v) = p.edit().putInt("jobs", v).apply()

  /** One per line: a thing's id is its panel, key and name, which hold no line breaks. */
  override var pinned: List<String>
    get() = p.getString("pinned", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    set(v) = p.edit().putString("pinned", v.joinToString("\n")).apply()

  /** Answers said aloud, as well as shown. */
  var speak: Boolean
    get() = p.getBoolean("speak", true)
    set(v) = p.edit().putBoolean("speak", v).apply()

  /** This watch's own id among the person's screens, made once: the `origin` of its questions. */
  val origin: String
    get() =
        p.getString("origin", null)
            ?: ("w_" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)).also { p.edit().putString("origin", it).apply() }
}
