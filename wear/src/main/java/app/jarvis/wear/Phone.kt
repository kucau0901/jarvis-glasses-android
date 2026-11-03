package app.jarvis.wear

import android.content.Context
import android.util.Log
import app.jarvis.link.FromWatch
import app.jarvis.link.Reply
import app.jarvis.link.WatchLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.tasks.await

/**
 * The phone app, over Wear OS's Data Layer: found by its capability ([WatchLink.PHONE]), sent what
 * the watch asks, and its [replies] heard while something of the watch app has it open ([open],
 * [close]): a screen, the Tile, a complication.
 */
interface Phone {
  val replies: SharedFlow<Reply>

  /** Sends [m] to the phone app; false when no phone with it is in reach. */
  suspend fun send(m: FromWatch): Boolean

  /** [f] with the phone's replies listened for, whatever else of the app is open. */
  suspend fun <T> using(f: suspend () -> T): T = f()
}

/**
 * [m] sent, and the one reply to it: null when no phone is in reach or none came in [timeoutMs].
 * Listening starts before it goes, so a quick reply isn't missed.
 */
suspend fun Phone.request(m: FromWatch, timeoutMs: Long = 12_000): Reply? = coroutineScope {
  val reply = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(timeoutMs) { replies.first { it.id == m.id } } }
  if (!send(m)) {
    reply.cancel()
    return@coroutineScope null
  }
  reply.await()
}

class DataLayerPhone(context: Context) : Phone {
  private val context = context.applicationContext
  private val messages = Wearable.getMessageClient(this.context)
  private val capabilities = Wearable.getCapabilityClient(this.context)

  private val _replies = MutableSharedFlow<Reply>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  override val replies: SharedFlow<Reply> = _replies

  private val listener =
      MessageClient.OnMessageReceivedListener { e ->
        val r = WatchLink.reply(e.path, e.data)
        if (r == null) Log.w(TAG, "unread from the phone: ${e.path}, ${e.data.size} bytes") else _replies.tryEmit(r)
      }

  private var users = 0

  /** Replies listened for, until each [open] has its [close]. */
  fun open() =
      synchronized(this) {
        if (users++ == 0) messages.addListener(listener)
      }

  fun close() =
      synchronized(this) {
        if (users == 0) return
        if (--users == 0) messages.removeListener(listener)
      }

  override suspend fun <T> using(f: suspend () -> T): T {
    open()
    try {
      return f()
    } finally {
      close()
    }
  }

  override suspend fun send(m: FromWatch): Boolean {
    val (path, data) = WatchLink.message(m)
    return try {
      val node = phone() ?: return false
      messages.sendMessage(node, path, data).await()
      true
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Log.w(TAG, "not sent to the phone", e)
      false
    }
  }

  /** The phone with the app, a nearby one first (Bluetooth, rather than through Google's servers). */
  private suspend fun phone(): String? {
    val nodes = capabilities.getCapability(WatchLink.PHONE, CapabilityClient.FILTER_REACHABLE).await().nodes
    return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id
  }

  private companion object {
    const val TAG = "JarvisWatch"
  }
}
