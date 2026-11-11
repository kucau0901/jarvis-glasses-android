package app.jarvis.glasses.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.jarvis.glasses.jarvis
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A saved place was crossed. Jarvis is told that and nothing else: `arrived:<slug>` or
 * `left:<slug>` with the place's name, and the bare word too when "Offer something useful when I
 * arrive" is on ([crossings]). The position Android measured is not read. What Jarvis has to say
 * comes back as an alert, like any other.
 *
 * The app is kept up until it is sent (goAsync), for at most [KEEP_MS]; what wasn't sent by then
 * waits in the [Outbox].
 */
class GeofenceReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val event = GeofencingEvent.fromIntent(intent) ?: return
    if (event.hasError()) {
      // Location switched off in Android's settings takes the fences with it; Settings → Location puts them back.
      Log.w(TAG, "geofencing: ${GeofenceStatusCodes.getStatusCodeString(event.errorCode)}")
      return
    }
    val arrived =
        when (event.geofenceTransition) {
          Geofence.GEOFENCE_TRANSITION_ENTER -> true
          Geofence.GEOFENCE_TRANSITION_EXIT -> false
          else -> return
        }
    val app = context.jarvis
    // Switched off or signed out since: these fences should not be there.
    if (!app.tellsPlaces) return app.fences.refresh()
    val crossed = crossings(arrived, event.triggeringGeofences.orEmpty().map { it.requestId }, app.places.list.value, app.prefs.placeOffers, System.currentTimeMillis())
    if (crossed.isEmpty()) return
    val pending = goAsync()
    val finished = AtomicBoolean(false)
    val done = { if (finished.compareAndSet(false, true)) pending.finish() }
    val sending = app.scope.launch { try { app.outbox.send(crossed) } finally { done() } }
    app.scope.launch {
      delay(KEEP_MS)
      // Cut short: what is unsent is kept already, for the next chance.
      sending.cancel()
      done()
    }
  }

  private companion object {
    const val TAG = "GeofenceReceiver"
    const val KEEP_MS = 50_000L
  }
}
