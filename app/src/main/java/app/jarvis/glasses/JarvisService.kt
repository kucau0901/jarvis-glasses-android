package app.jarvis.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps Jarvis reachable with the phone in a pocket: the alerts socket stays open, and a
 * conversation can use the glasses microphone from the background. Android lets a service use the
 * microphone only if it was started as a microphone service while the app was on screen, so the
 * screen starts this, and it runs until stopped from its notification.
 *
 * The notification's Talk button is a way to start a conversation without touching the glasses.
 */
class JarvisService : Service() {
  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        jarvis.conversation.stop()
        jarvis.alerts.stop()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_TALK -> jarvis.conversation.start()
    }
    startForeground(
        NOTIFICATION,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    if (jarvis.prefs.isSetUp) jarvis.alerts.start()
    return START_STICKY
  }

  override fun onDestroy() {
    jarvis.alerts.stop()
    super.onDestroy()
  }

  private fun notification(): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis on the glasses", NotificationManager.IMPORTANCE_LOW))
    fun action(a: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("Jarvis is listening for alerts")
        .setContentText("Say \"Hey Meta, open Jarvis\" to talk")
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "Talk", action(ACTION_TALK, 1)).build())
        .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 2)).build())
        .build()
  }

  companion object {
    private const val CHANNEL = "jarvis"
    private const val NOTIFICATION = 1
    const val ACTION_TALK = "app.jarvis.glasses.TALK"
    const val ACTION_STOP = "app.jarvis.glasses.STOP"

    fun start(context: Context, action: String? = null) {
      context.startForegroundService(Intent(context, JarvisService::class.java).setAction(action))
    }
  }
}
