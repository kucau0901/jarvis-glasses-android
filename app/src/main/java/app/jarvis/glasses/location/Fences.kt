package app.jarvis.glasses.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.jarvis.glasses.hub.Place
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The saved places as Android's geofences (Google Play services): one per place, by its slug, for
 * going in and coming out, told to [GeofenceReceiver]. The phone's own system does the watching;
 * this app is woken only when one is crossed. They are registered only while the switch is on for a
 * signed-in person ([on]) and location is allowed all the time; otherwise there are none.
 */
class Fences(private val context: Context, private val on: () -> Boolean, private val places: () -> List<Place>) {
  private val client by lazy { LocationServices.getGeofencingClient(context) }
  private val _note = MutableStateFlow("")

  /** Why Android refused the fences, for Settings to say; empty when it took them. */
  val note: StateFlow<String> = _note

  private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

  /** Geofences need the precise position, and with the app closed, "Allow all the time". */
  fun allowed(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) && granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

  private fun active() = on() && allowed()

  /**
   * All of them again from the kept list, or none when the switch is off, the person signed out or
   * the permission went: after a restart, an update, and whenever one of those changes. [done] when
   * Android has answered.
   */
  fun refresh(done: () -> Unit = {}) {
    client.removeGeofences(intent()).addOnCompleteListener {
      val list = if (active()) places() else emptyList()
      if (list.isEmpty()) {
        _note.value = ""
        done()
      } else add(list, done)
    }
  }

  /** The list changed ([Places.sync]): only what differs. */
  fun apply(change: FenceChange) {
    if (!active()) return
    if (change.remove.isNotEmpty()) client.removeGeofences(change.remove)
    if (change.add.isNotEmpty()) add(change.add) {}
  }

  // Checked by active(), which every caller passes first.
  @SuppressLint("MissingPermission")
  private fun add(list: List<Place>, done: () -> Unit) {
    val fences = list.mapNotNull(::fence)
    if (fences.isEmpty()) return done()
    // No initial trigger: a place saved while standing in it, or put back after a restart at home, is not an arrival.
    val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofences(fences).build()
    client.addGeofences(request, intent()).addOnCompleteListener { t ->
      _note.value = if (t.isSuccessful) "" else why(t.exception)
      if (!t.isSuccessful) Log.w(TAG, "geofences refused", t.exception)
      done()
    }
  }

  /** A position Android won't take (Jarvis checks them, so not expected) is left out rather than failing the rest. */
  private fun fence(p: Place): Geofence? =
      runCatching {
            Geofence.Builder()
                .setRequestId(p.slug)
                .setCircularRegion(p.lat, p.lng, p.radius.toFloat().coerceIn(RADIUS_MIN, RADIUS_MAX))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .setNotificationResponsiveness(RESPONSIVENESS_MS)
                .build()
          }
          .getOrNull()

  // Mutable, as Android fills in which fence was crossed; the intent names its receiver.
  private fun intent(): PendingIntent =
      PendingIntent.getBroadcast(context, 0, Intent(context, GeofenceReceiver::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  private fun why(e: Exception?): String =
      when ((e as? ApiException)?.statusCode) {
        GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE -> "Android isn't watching places: turn on Location in the phone's settings, then open this page again."
        GeofenceStatusCodes.GEOFENCE_INSUFFICIENT_LOCATION_PERMISSION -> "Android wants location allowed all the time for this."
        null -> e?.message ?: "Android didn't take the places"
        else -> "Android didn't take the places (${GeofenceStatusCodes.getStatusCodeString((e as ApiException).statusCode)})"
      }

  private companion object {
    const val TAG = "Fences"
    /** As Jarvis keeps a radius (lib/spots.ts). */
    const val RADIUS_MIN = 100f
    const val RADIUS_MAX = 1000f
    /** How late Android may tell of a crossing, to spare the battery: half a minute. */
    const val RESPONSIVENESS_MS = 30_000
  }
}
