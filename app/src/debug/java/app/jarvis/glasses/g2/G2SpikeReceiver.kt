package app.jarvis.glasses.g2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.jarvis.glasses.JarvisApp
import java.io.File
import kotlinx.coroutines.launch

/**
 * Drives the G2 spike from a computer, debug builds only:
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2SpikeReceiver --es do show --es text "Hello"`.
 * Only the shell can send it (the receiver needs DUMP).
 */
class G2SpikeReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val g2 = link ?: G2Link(context).also { link = it }
    val text = intent.getStringExtra("text") ?: "Hello from Jarvis"
    when (intent.getStringExtra("do")) {
      "connect" -> g2.connect()
      "show" -> g2.show(text)
      "update" -> g2.update(text)
      "gesture" -> g2.gestureInit()
      "mic-on" -> g2.micOn()
      "mic-off" -> g2.micOff()
      "release" -> g2.release()
      // A WAV pushed next to the recording, decoded on the computer: what Jarvis hears in it.
      "transcribe" -> {
        val app = context.applicationContext as JarvisApp
        val wav = File(context.getExternalFilesDir(null), intent.getStringExtra("text") ?: "g2-mic.wav")
        app.scope.launch {
          val heard = runCatching { app.client.transcribe(wav.readBytes()) }
          Log.i("G2", "Jarvis heard (${wav.name}): ${heard.getOrElse { "failed: ${it.message}" }}")
        }
      }
    }
  }

  companion object {
    private var link: G2Link? = null
  }
}
