package app.jarvis.glasses.g2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.jarvis.glasses.jarvis
import app.jarvis.glasses.hub.AskRef
import app.jarvis.glasses.jarvis.Alert
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug builds only, from the computer. Only the shell can send it (the receiver needs DUMP).
 *
 * An alert shown on G2 glasses as if Jarvis had sent it, to try the display and a reply without
 * waiting for a real one (the reply is a real question to Jarvis):
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --es text "'The gate is open.'"`
 *
 * A background job's question, its choices "|" apart, on its own page (answering it asks the real
 * Jarvis to tell a job that does not exist, which it refuses, and the page says so):
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --es ask "'Under RM800 only?'" --es choices "'Yes|Up to RM1,000'"`
 *
 * Jarvis's own screen on them, as "open Jarvis" said to Even AI opens it:
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --ez menu true`
 *
 * What the glasses send while the Even app has them, heard without signing in (`--ei listen`
 * minutes; G2Listen): `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --ei listen 5`
 *
 * The glasses held for a while, to see whether the link stays up (`--ei hold` minutes):
 * `adb shell am broadcast -n app.jarvis.glasses/.g2.G2AlertReceiver --ei hold 5`
 */
class G2AlertReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val app = context.jarvis
    if (intent.getBooleanExtra("menu", false)) return app.openG2()
    val listen = intent.getIntExtra("listen", 0)
    if (listen > 0) return G2Listen(context.applicationContext).start(listen)
    val minutes = intent.getIntExtra("hold", 0)
    if (minutes > 0) {
      val link = app.g2Link ?: return
      app.scope.launch {
        val start = System.currentTimeMillis()
        fun seconds() = (System.currentTimeMillis() - start) / 1000
        if (!link.open()) {
          Log.i("G2", "hold: couldn't sign in")
          return@launch
        }
        val dropped = launch { link.touches.collect { if (it == G2Touch.CLOSED) Log.i("G2", "hold: closed after ${seconds()} s") } }
        while (seconds() < minutes * 60) {
          link.show("Holding the link: ${seconds() / 60} of $minutes minutes.\n\n(${seconds()} s)")
          delay(15_000)
        }
        dropped.cancel()
        link.close()
        Log.i("G2", "hold: done after ${seconds()} s")
      }
      return
    }
    val question = intent.getStringExtra("ask")
    val choices = intent.getStringExtra("choices")?.split("|")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val text = question?.let { q -> q + if (choices.isNotEmpty()) "\n\n" + choices.joinToString("\n") { "- $it" } else "" } ?: intent.getStringExtra("text") ?: "This is a test alert."
    val alert =
        Alert(
            "test-${System.currentTimeMillis()}", intent.getStringExtra("title") ?: if (question != null) "A question: Test job" else "Jarvis", text, speak = true, urgent = false,
            source = if (question != null) "job" else "", ask = question?.let { AskRef("jtest", "q1") },
        )
    val shown = app.g2?.show(alert) { Log.i("G2", "test alert not shown: the G2 couldn't be signed in to") } == true
    Log.i("G2", "test alert ${if (shown) "shown" else "not shown: no G2 with the phone, or one already showing"}")
  }
}
