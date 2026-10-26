package app.jarvis.glasses.jarvis

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Catches the one page a sign-in ends on at http://127.0.0.1:[port]: connecting a ChatGPT plan
 * sends the browser there (routes/chatgpt.ts in jarvis-voice-agent), which on a computer is a page
 * that can't be reached, to be copied and pasted. On the phone the app is that page, so nothing
 * needs pasting. Answers the browser with a short note and gives back the whole address.
 */
object Loopback {
  const val CHATGPT_PORT = 1455

  /** The address the browser asked for, or null if nothing came within [waitMs]. */
  suspend fun await(port: Int, waitMs: Int): String? =
      withContext(Dispatchers.IO) {
        ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { server ->
          server.soTimeout = waitMs
          var caught: String? = null
          while (caught == null) {
            val socket =
                try {
                  server.accept()
                } catch (_: SocketTimeoutException) {
                  return@withContext null
                }
            socket.use { s ->
              // "GET /auth/callback?code=…&state=… HTTP/1.1"
              val line = s.getInputStream().bufferedReader().readLine().orEmpty()
              val target = line.split(' ').getOrNull(1).orEmpty()
              val page = if (target.contains("code=") || target.contains("error=")) DONE else ""
              val reply = if (page.isEmpty()) "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" else
                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${page.toByteArray().size}\r\nConnection: close\r\n\r\n$page"
              s.getOutputStream().apply { write(reply.toByteArray()); flush() }
              // A browser may ask for a favicon first; only the sign-in's own page counts.
              if (page.isNotEmpty()) caught = "http://127.0.0.1:$port$target"
            }
          }
          caught
        }
      }

  private const val DONE =
      "<!doctype html><meta name=viewport content='width=device-width'><body style='font:16px system-ui;margin:40px'>" +
          "<h2>Back to Jarvis</h2><p>The sign-in reached the Jarvis app. Go back to it to finish.</p>"
}
