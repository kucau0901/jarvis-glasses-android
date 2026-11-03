package app.jarvis.glasses.watch

import android.content.Context
import android.util.Log
import app.jarvis.glasses.jarvis
import app.jarvis.link.Reply
import app.jarvis.link.WatchLink
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.tasks.await

/**
 * What the Jarvis watch app sends, from Wear OS's Data Layer: Play services starts this for the
 * `/jarvis/` paths (the manifest's filter), even with the app closed, and the app's [WatchRelay]
 * answers.
 */
class WatchService : WearableListenerService() {
  override fun onMessageReceived(e: MessageEvent) {
    Log.i(TAG, "from the watch: ${e.path}")
    jarvis.watch.onMessage(e.sourceNodeId, e.path, e.data)
  }

  companion object {
    private const val TAG = "WatchService"

    /** [r] to the watch [node]; a failure is logged and left (the watch out of reach, a sentence too big). */
    suspend fun send(context: Context, node: String, r: Reply) {
      try {
        val data = WatchLink.encode(r)
        Wearable.getMessageClient(context).sendMessage(node, WatchLink.REPLY, data).await()
        Log.i(TAG, "to the watch: ${r::class.simpleName}, ${data.size} bytes")
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "not sent to the watch: ${r::class.simpleName}", e)
      }
    }
  }
}
