package app.jarvis.glasses.watch

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the phone app running while it answers the watch. Play services wakes the app only to hand
 * over the watch's message ([WatchService]); with nothing else of the app running (JarvisService
 * stopped, say), Android would freeze it partway through Jarvis's answer. A short service: Android
 * gives it three minutes, longer than an answer takes, and it stops as soon as none is on its way.
 */
class WatchAskService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_DONE) {
      stopSelf()
      return START_NOT_STICKY
    }
    val n = notification()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
    else startForeground(NOTIFICATION, n)
    return START_NOT_STICKY
  }

  // Android 14 calls this one; 15 and later, the one below. A short service that outlasts its time
  // without stopping is an error, so both stop it.
  @Deprecated("Android 14's; the other is 15's")
  override fun onTimeout(startId: Int) {
    Log.w(TAG, "an answer for the watch outlasted its time")
    stopSelf()
  }

  override fun onTimeout(startId: Int, fgsType: Int) {
    Log.w(TAG, "an answer for the watch outlasted its time")
    stopSelf()
  }

  private fun notification(): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Answering the watch", NotificationManager.IMPORTANCE_MIN))
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Asking Jarvis for the watch")
        .build()
  }

  companion object {
    private const val TAG = "WatchAskService"
    private const val CHANNEL = "watch"
    private const val NOTIFICATION = 3
    private const val ACTION_DONE = "app.jarvis.glasses.WATCH_DONE"

    /** While the watch waits for an answer; with the app in the background Android may refuse, and the answer is tried anyway. */
    fun start(context: Context) {
      try {
        context.startForegroundService(Intent(context, WatchAskService::class.java))
      } catch (e: ForegroundServiceStartNotAllowedException) {
        Log.w(TAG, "not allowed to stay up for the watch", e)
      } catch (e: IllegalStateException) {
        Log.w(TAG, "not allowed to stay up for the watch", e)
      }
    }

    fun stop(context: Context) {
      // Through onStartCommand, so a start still on its way is answered with startForeground first.
      runCatching { context.startService(Intent(context, WatchAskService::class.java).setAction(ACTION_DONE)) }
    }
  }
}
