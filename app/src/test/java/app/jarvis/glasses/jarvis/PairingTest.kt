package app.jarvis.glasses.jarvis

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PairingTest {
  private val server = MockWebServer()
  private lateinit var pairing: Pairing

  @Before
  fun setUp() {
    server.start()
    pairing = Pairing({ server.url("/").toString() }, { mapOf("CF-Access-Client-Id" to "id.access") }, OkHttpClient())
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun aCodeIsShownThenApproved() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"code":"KX7P2Q","poll":"p1","expiresAt":1793000000000}"""))
    server.enqueue(MockResponse().setBody("""{"status":"waiting"}"""))
    server.enqueue(MockResponse().setBody("""{"status":"approved","token":"jss1_abc","name":"Aminah"}"""))
    val code = pairing.start("Jarvis glasses")
    assertEquals("KX7P2Q", code.code)
    assertEquals(Pairing.Outcome.Approved("jss1_abc", "Aminah"), pairing.await(code, every = 1))

    val start = server.takeRequest()
    assertEquals("/api/auth/pair/start", start.path)
    assertTrue(start.body.readUtf8().contains("Jarvis glasses"))
    // Cloudflare Access headers go on every call when set; no Jarvis credential before pairing.
    assertEquals("id.access", start.getHeader("CF-Access-Client-Id"))
    assertEquals(null, start.getHeader("Authorization"))
    assertTrue(server.takeRequest().body.readUtf8().contains("\"poll\":\"p1\""))
  }

  @Test
  fun anExpiredCodeSaysSo() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"code":"1","poll":"p","expiresAt":0}"""))
    server.enqueue(MockResponse().setBody("""{"status":"expired"}"""))
    assertEquals(Pairing.Outcome.Expired, pairing.await(pairing.start("x"), every = 1))
  }
}
