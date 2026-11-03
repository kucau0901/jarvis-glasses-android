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
import app.jarvis.glasses.glasses.GlassesState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps Jarvis reachable with the phone in a pocket: the alerts socket stays open, and a
 * conversation can use the glasses microphone from the background. Android lets a service use the
 * microphone only if it was started as a microphone service while the app was on screen, so the
 * screen starts this, and it runs until stopped from its notification.
 *
 * The notification's Talk button is a way to start a conversation without touching the glasses.
 * Its words say how to open Jarvis with the glasses that are with the phone ([howToTalk]), and change
 * as they come and go.
 */
class JarvisService : Service() {
  private var watching: Job? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        jarvis.conversation.stop()
        jarvis.live.stop()
        jarvis.alerts.stop()
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_TALK -> jarvis.talk()
    }
    startForeground(
        NOTIFICATION,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
    )
    if (jarvis.prefs.isSetUp) jarvis.alerts.start()
    if (watching == null) {
      val app = jarvis
      val nm = getSystemService(NotificationManager::class.java)
      watching =
          app.scope.launch {
            combine(app.glasses, app.audio.connected) { g, metaHeard -> howToTalk(g, metaHeard, app.prefs.g2) }
                .distinctUntilChanged()
                .collect { nm.notify(NOTIFICATION, notification(it)) }
          }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    watching?.cancel()
    watching = null
    jarvis.alerts.stop()
    super.onDestroy()
  }

  private fun notification(talk: String = howToTalk(jarvis.glasses.value, jarvis.audio.connected.value, jarvis.prefs.g2)): Notification {
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis on the glasses", NotificationManager.IMPORTANCE_LOW))
    fun action(a: String, code: Int) =
        PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_jarvis)
        .setContentTitle("Jarvis is listening for alerts")
        .setContentText(talk)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(Notification.Action.Builder(null, "Talk", action(ACTION_TALK, 1)).build())
        .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 2)).build())
        .build()
  }

  companion object {
    /**
     * How to open Jarvis with the glasses that are with the phone: on a G2, through Even AI ("Hey
     * Even", with "Show alerts on Even G2" on); on Ray-Ban Meta glasses, through Meta AI; with
     * neither, the notification's own Talk button.
     */
    fun howToTalk(glasses: List<GlassesState>, metaHeard: Boolean, g2: Boolean): String {
      val connected = glasses.filter { it.link == GlassesState.Link.CONNECTED }
      val onG2 = g2 && connected.any { it.model == G2_MODEL }
      val onMeta = metaHeard || connected.any { it.model != G2_MODEL }
      return when {
        onG2 && onMeta -> "Say \"Hey Even, open Jarvis\" on the G2, or \"Hey Meta, open Jarvis\""
        onG2 -> "Say \"Hey Even, open Jarvis\" on the G2, or tap Talk"
        onMeta -> "Say \"Hey Meta, open Jarvis\", or tap Talk"
        else -> "Tap Talk to ask Jarvis"
      }
    }

    /** How the G2 says what it is ([app.jarvis.glasses.g2.G2Link]). */
    private const val G2_MODEL = "Even Realities G2"
    private const val CHANNEL = "jarvis"
    private const val NOTIFICATION = 1
    const val ACTION_TALK = "app.jarvis.glasses.TALK"
    const val ACTION_STOP = "app.jarvis.glasses.STOP"

    fun start(context: Context, action: String? = null) {
      context.startForegroundService(Intent(context, JarvisService::class.java).setAction(action))
    }
  }
}
