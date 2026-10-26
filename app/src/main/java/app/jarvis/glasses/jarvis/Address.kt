package app.jarvis.glasses.jarvis

import java.net.URI

/**
 * Which Jarvis addresses the app will use. Any https address; plain http only on the home network
 * (a Jarvis in Docker at 192.168.x.x:8787, say), never across the internet, since the device token
 * would travel in the clear.
 */
object Address {
  fun usable(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    val host = uri.host?.lowercase() ?: return false
    return when (uri.scheme?.lowercase()) {
      "https" -> true
      "http" -> isLocal(host)
      else -> false
    }
  }

  private fun isLocal(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true
    val parts = host.split('.').map { it.toIntOrNull() ?: return false }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    val (a, b) = parts
    return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
  }
}
