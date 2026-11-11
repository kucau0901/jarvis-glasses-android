package app.jarvis.glasses.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.jarvis.glasses.jarvis
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android forgets an app's geofences when the phone restarts, and may when the app is updated:
 * they are put back from the list kept on the phone ([Places]), with no network and no position
 * read. With the switch off there are none to put back, and this does nothing.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val app = context.jarvis
    if (!app.tellsPlaces) return
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    val done = { if (finished.compareAndSet(false, true)) pending.finish() }
    app.fences.refresh(done)
    app.scope.launch {
      delay(KEEP_MS)
      done()
    }
  }

  private companion object {
    /** A receiver for these has ten seconds. */
    const val KEEP_MS = 8_000L
  }
}
