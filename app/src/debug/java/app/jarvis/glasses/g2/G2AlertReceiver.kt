package app.jarvis.glasses.g2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.jarvis.glasses.jarvis
import app.jarvis.glasses.jarvis.Alert

/**
 * Debug builds only: an alert shown on G2 glasses as if Jarvis had sent it, to try the display and
 * a reply without waiting for a real one:
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --es text "'The gate is open.'"`.
 * Only the shell can send it (the receiver needs DUMP). The reply is a real question to Jarvis.
 */
class G2AlertReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.jarvis
    val text = intent.getStringExtra("text") ?: "This is a test alert."
    val alert = Alert("test-${System.currentTimeMillis()}", intent.getStringExtra("title") ?: "Jarvis", text, speak = true, urgent = false)
    val shown = app.g2?.show(alert) { Log.i("G2", "test alert not shown: the G2 couldn't be signed in to") } == true
    Log.i("G2", "test alert ${if (shown) "shown" else "not shown: no G2 with the phone, or one already showing"}")
  }
}
