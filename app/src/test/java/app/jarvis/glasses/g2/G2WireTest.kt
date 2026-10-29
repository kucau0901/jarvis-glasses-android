package app.jarvis.glasses.g2

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class G2WireTest {
  private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

  private fun touch(type: Int?, onText: Boolean = false): ByteArray {
    val inner = Proto().apply { if (type != null) int(if (onText) 3 else 1, type) }.build()
    return Proto().int(1, 2).int(2, 0).bytes(13, Proto().bytes(if (onText) 2 else 3, inner).build()).build()
  }

  @Test fun theChecksumIsCcittFalse() {
    assertEquals(0x29B1, g2Crc16("123456789".toByteArray()))
  }

  @Test fun aShortMessageIsOnePacketWithItsChecksumLast() {
    val payload = byteArrayOf(1, 2, 3)
    val packets = g2Packets(7, G2Service.EVEN_HUB, payload, reserveFlag = true)
    assertEquals(1, packets.size)
    val crc = g2Crc16(payload)
    assertEquals("aa2107050101e020010203" + "%02x%02x".format(crc and 0xff, crc shr 8), hex(packets[0]))
  }

  @Test fun aLongMessageIsSplitAndPutBackTogether() {
    for (size in listOf(500, 236, 0)) {
      val payload = ByteArray(size) { it.toByte() }
      val packets = g2Packets(3, G2Service.DEVICE_SETTINGS, payload, reserveFlag = false)
      // Whole chunks of 236, and a packet for the checksum alone when the last chunk is full.
      assertEquals(size / 236 + 1, packets.size)
      val r = G2Reassembler()
      val whole = packets.map { r.accept(it, "R") }
      assertEquals(List(packets.size - 1) { null }, whole.dropLast(1))
      assertEquals(G2Service.DEVICE_SETTINGS, whole.last()!!.first)
      assertArrayEquals(payload, whole.last()!!.second)
    }
  }

  @Test fun aReplyWithAnErrorIsDropped() {
    val packet = g2Packets(1, G2Service.EVEN_HUB, byteArrayOf(9), reserveFlag = false)[0]
    packet[7] = 0x02 // result code 1
    assertNull(G2Reassembler().accept(packet, "R"))
  }

  @Test fun touchesAreReadFromTheTouchpadAndTheRing() {
    assertEquals(G2Touch.TAP, g2Touch(touch(null)))
    assertEquals(G2Touch.UP, g2Touch(touch(1)))
    assertEquals(G2Touch.DOWN, g2Touch(touch(2, onText = true)))
    assertEquals(G2Touch.DOUBLE_TAP, g2Touch(touch(3)))
    assertEquals(G2Touch.CLOSED, g2Touch(touch(7)))
    // The page brought back to the front, or put behind something for a moment, and the ring's own events.
    for (t in listOf(4, 5, 9, 10)) assertNull(g2Touch(touch(t)))
  }

  @Test fun otherMessagesAreNotTouches() {
    // A heartbeat's reply, as the glasses sent it.
    assertNull(g2Touch(byteArrayOf(0x08, 0x0c, 0x7a, 0x02, 0x10, 0x0c)))
    assertNull(g2Touch(Proto().int(1, 2).build()))
  }

  @Test fun signingInIsTheAndroidPhoneType() {
    assertEquals("080410051a0408011004", hex(DevSettings.auth(5)))
  }

  @Test fun aPageIsCreatedFirstThenRebuilt() {
    val first = parseProto(EvenHub.page("Hello", first = true, magic = 1))
    assertEquals(0, first[1])
    val rebuilt = parseProto(EvenHub.page("Hello", first = false, magic = 2))
    assertEquals(7, rebuilt[1])
    val containers = parseProto(rebuilt[7] as ByteArray)
    assertEquals(2, containers[1])
    // The last container carries the text.
    assertEquals("Hello", String(parseProto(containers[3] as ByteArray)[12] as ByteArray))
  }
}
