package app.jarvis.tv

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import app.jarvis.tv.jarvis.TvPrefs

/**
 * Hands the TV what typing on a remote would take minutes to: Jarvis's Cloudflare Access service
 * token, and its address if it isn't the usual one. Started over adb from a computer, never shown:
 *
 *   adb shell am start -n app.jarvis.tv/.Setup --es access_id '…' --es access_secret '…' [--es url 'https://…']
 *
 * The manifest lets only holders of WRITE_SECURE_SETTINGS start it: the shell, the system, and
 * an app someone granted it over adb. In case that's one of the last, a new address signs the TV
 * out and forgets the Access token unless one comes with it, so nothing of Jarvis's is ever sent
 * to an address it didn't come from.
 */
class Setup : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = TvPrefs(this)
    val id = intent.getStringExtra("access_id")
    val secret = intent.getStringExtra("access_secret")
    val url = intent.getStringExtra("url")
    if (!url.isNullOrBlank() && TvPrefs.cleanUrl(url) != prefs.url) {
      prefs.signOut()
      prefs.accessId = ""
      prefs.accessSecret = ""
      prefs.url = url
    }
    if (id != null && secret != null) {
      prefs.accessId = id
      prefs.accessSecret = secret
    }
    Toast.makeText(this, if (id != null || url != null) "Jarvis: settings saved" else "Jarvis: nothing to save", Toast.LENGTH_SHORT).show()
    finish()
  }
}
