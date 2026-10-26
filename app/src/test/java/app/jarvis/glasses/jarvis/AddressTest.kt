package app.jarvis.glasses.jarvis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressTest {
  @Test
  fun httpsAnywhere() {
    assertTrue(Address.usable("https://jarvis.example.com"))
    assertTrue(Address.usable("https://203.0.113.5:8443"))
  }

  @Test
  fun plainHttpOnlyAtHome() {
    assertTrue(Address.usable("http://192.168.1.20:8787"))
    assertTrue(Address.usable("http://10.0.0.5"))
    assertTrue(Address.usable("http://172.20.1.1:8787"))
    assertTrue(Address.usable("http://jarvis.local:8787"))
    assertTrue(Address.usable("http://100.101.102.103")) // Tailscale
    assertFalse(Address.usable("http://jarvis.example.com"))
    assertFalse(Address.usable("http://203.0.113.5"))
    assertFalse(Address.usable("http://172.32.0.1"))
  }

  @Test
  fun notAnAddress() {
    assertFalse(Address.usable(""))
    assertFalse(Address.usable("jarvis.example.com"))
    assertFalse(Address.usable("ftp://192.168.1.2"))
  }
}
