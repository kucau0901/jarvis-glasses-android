package app.jarvis.glasses.location

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.IOException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** Where the phone is, asked once, for "Save where I am now as…": the only time the app reads a position. */
object Here {
  class Fix(val lat: Double, val lng: Double)

  /** Fails in words the person can act on. The caller has checked that location is allowed. */
  @SuppressLint("MissingPermission")
  suspend fun find(context: Context): Fix {
    val stop = CancellationTokenSource()
    val at =
        try {
          withTimeoutOrNull(WAIT_MS) { LocationServices.getFusedLocationProviderClient(context).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, stop.token).await() }
        } catch (e: com.google.android.gms.common.api.ApiException) {
          throw IOException("Android couldn't say where the phone is: ${e.message}")
        } finally {
          stop.cancel()
        }
            ?: throw IOException("Couldn't find where the phone is. Check that Location is on, and try again.")
    // A place is at least 100 m across: a fix vaguer than that would put it somewhere else.
    if (!at.hasAccuracy() || at.accuracy > GOOD_M) throw IOException("The phone isn't sure enough of where it is (within ${at.accuracy.toInt()} m). Try again outdoors, or nearer a window.")
    return Fix(at.latitude, at.longitude)
  }

  private const val WAIT_MS = 20_000L
  private const val GOOD_M = 100f
}
