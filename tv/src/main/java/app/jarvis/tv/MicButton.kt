package app.jarvis.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * The remote's mic button, for Jarvis. The button sends ASSIST, which Android hands straight to
 * Google Assistant before any app sees it; an accessibility service is the one place that gets it
 * first. Anywhere on the TV, it brings Jarvis up listening; on Jarvis, it listens or finishes.
 * Switched on by hand, once, in the TV's Settings → Accessibility.
 */
class MicButton : AccessibilityService() {
  override fun onKeyEvent(event: KeyEvent): Boolean {
    if (event.keyCode !in MIC_KEYS) return false
    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
      // Jarvis in front hears the press directly: starting it again would pause it and lose the words.
      val jarvis = MainActivity.front?.get()
      if (jarvis != null) {
        jarvis.talk()
      } else {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.TALK)
                .putExtra(MainActivity.PRESSED_AT, SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
      }
    }
    return true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

  override fun onInterrupt() = Unit

  companion object {
    val MIC_KEYS = setOf(KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH)
  }
}
