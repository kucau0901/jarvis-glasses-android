package app.jarvis.glasses.jarvis

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackTest {
  private val http = OkHttpClient()

  private fun get(port: Int, path: String) = http.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute().use { it.code to it.body!!.string() }

  @Test
  fun theSignInsPageIsCaughtAndAnswered() = runBlocking {
    val port = 18455
    val caught = async { Loopback.await(port, 5_000) }
    delay(300)
    assertEquals(404, get(port, "/favicon.ico").first)
    val (code, page) = get(port, "/auth/callback?code=abc&state=xyz")
    assertEquals(200, code)
    assertTrue(page.contains("Back to Jarvis"))
    assertEquals("http://127.0.0.1:$port/auth/callback?code=abc&state=xyz", caught.await())
  }

  @Test
  fun nothingComingIsNull() = runBlocking { assertNull(Loopback.await(18456, 300)) }
}
