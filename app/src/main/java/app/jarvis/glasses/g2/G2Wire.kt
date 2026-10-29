package app.jarvis.glasses.g2

import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.UUID

/*
 * The Even Realities G2's Bluetooth protocol, as far as Jarvis needs it: framing, the handful of
 * protobuf messages to sign in, keep alive, draw a text page, read touches and stream the mic.
 *
 * Ported from MentraOS's G2 driver (Mentra-Community/MentraOS,
 * mobile/modules/bluetooth-sdk/android/.../sgcs/G2.kt, Apache License 2.0), which recovered it
 * from the official app. Even publishes none of this; a firmware update may change it.
 */

object G2Uuid {
  val WRITE: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5401")
  val NOTIFY: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e5402")
  val AUDIO: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e6402")
  val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Service ids (the seventh byte of every packet). */
object G2Service {
  const val GESTURE_CTRL: Byte = 0x0D
  const val G2_SETTING: Byte = 0x09
  const val DEVICE_SETTINGS: Byte = 0x80.toByte()
  const val EVEN_HUB: Byte = 0xE0.toByte()
}

/** CRC-16/CCITT-FALSE over the whole payload, sent little-endian after the last chunk. */
fun g2Crc16(data: ByteArray): Int {
  var crc = 0xFFFF
  for (byte in data) {
    val b = byte.toInt() and 0xFF
    crc = ((crc shr 8) or ((crc shl 8) and 0xFF00)) xor b
    crc = crc xor ((crc and 0xFF) shr 4)
    crc = crc xor ((crc shl 12) and 0xFFFF)
    crc = crc xor (((crc and 0xFF) shl 5) and 0xFFFF)
  }
  return crc and 0xFFFF
}

class Proto {
  private val out = ByteArrayOutputStream()

  private fun varint(value: Long) {
    var v = value
    while (v.toULong() > 0x7FuL) {
      out.write(((v and 0x7F) or 0x80).toInt())
      v = v ushr 7
    }
    out.write((v and 0x7F).toInt())
  }

  fun int(field: Int, value: Int) = apply {
    varint((field shl 3).toLong())
    varint(value.toLong())
  }

  fun bytes(field: Int, value: ByteArray) = apply {
    varint(((field shl 3) or 2).toLong())
    varint(value.size.toLong())
    out.write(value)
  }

  fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

  fun build(): ByteArray = out.toByteArray()
}

/** A protobuf message's top-level fields: varints as Int, length-delimited as ByteArray. */
fun parseProto(data: ByteArray): Map<Int, Any> {
  val fields = mutableMapOf<Int, Any>()
  var i = 0
  fun varint(): Long? {
    var result = 0L
    var shift = 0
    while (i < data.size) {
      val b = data[i++].toInt() and 0xFF
      result = result or ((b.toLong() and 0x7F) shl shift)
      if (b and 0x80 == 0) return result
      shift += 7
      if (shift > 63) return null
    }
    return null
  }
  while (i < data.size) {
    val tag = varint() ?: break
    val field = (tag shr 3).toInt()
    when ((tag and 7).toInt()) {
      0 -> fields[field] = (varint() ?: break).toInt()
      1 -> i += 8
      2 -> {
        val len = varint()?.toInt() ?: break
        if (i + len > data.size) break
        fields[field] = data.copyOfRange(i, i + len)
        i += len
      }
      5 -> i += 4
      else -> break
    }
  }
  return fields
}

/** Splits a payload into packets of at most 236 bytes: header, chunk, and the CRC on the last. */
fun g2Packets(syncId: Int, service: Byte, payload: ByteArray, reserveFlag: Boolean): List<ByteArray> {
  val max = 236
  val chunks = payload.toList().chunked(max).map { it.toByteArray() }.toMutableList()
  if (chunks.isEmpty() || chunks.last().size == max) chunks.add(ByteArray(0))
  val crc = g2Crc16(payload)
  return chunks.mapIndexed { i, chunk ->
    val last = i == chunks.lastIndex
    ByteArrayOutputStream().apply {
      write(0xAA)
      write(0x21) // to the glasses (2), from the phone (1)
      write(syncId and 0xFF)
      write(chunk.size + if (last) 2 else 0)
      write(chunks.size)
      write(i + 1)
      write(service.toInt() and 0xFF)
      write(if (reserveFlag) 0x20 else 0x00)
      write(chunk)
      if (last) {
        write(crc and 0xFF)
        write((crc shr 8) and 0xFF)
      }
    }.toByteArray()
  }
}

/** One message from the glasses, put back together from its packets. */
class G2Reassembler {
  private val partial = mutableMapOf<String, ByteArrayOutputStream>()

  /** The service id and whole payload once [raw] completes a message; null until then. */
  fun accept(raw: ByteArray, side: String): Pair<Byte, ByteArray>? {
    if (raw.size < 8 || raw[0] != 0xAA.toByte()) return null
    val len = raw[3].toInt() and 0xFF
    if (raw.size < len + 8) return null
    val total = raw[4].toInt() and 0xFF
    val serial = raw[5].toInt() and 0xFF
    val service = raw[6]
    if (((raw[7].toInt() and 0xFF) shr 1) and 0x0F != 0) return null // an error result
    val last = serial == total
    val payload = raw.copyOfRange(8, 8 + len - if (last) 2 else 0)
    val key = "$side-${service.toInt() and 0xFF}-${raw[2].toInt() and 0xFF}"
    if (total == 1) return service to payload
    val buf = if (serial == 1) ByteArrayOutputStream().also { partial[key] = it } else partial[key] ?: return null
    buf.write(payload)
    if (!last) return null
    partial.remove(key)
    return service to buf.toByteArray()
  }
}

/** Device-settings messages (service 0x80): sign-in, time and the link's heartbeat. Nothing else. */
object DevSettings {
  fun auth(magic: Int) =
      Proto().int(1, 4).int(2, magic).bytes(3, Proto().int(1, 1).int(2, 4).build()).build() // Android

  /** The right arm takes commands. */
  fun rightTakesCommands(magic: Int) = Proto().int(1, 5).int(2, magic).bytes(4, Proto().int(1, 1).build()).build()

  fun timeSync(magic: Int, nowMs: Long = System.currentTimeMillis()): ByteArray {
    val local = nowMs / 1000 + TimeZone.getDefault().getOffset(nowMs) / 1000
    return Proto().int(1, 128).int(2, magic).bytes(128, Proto().int(1, local.toInt()).build()).build()
  }

  fun heartbeat(magic: Int) = Proto().int(1, 14).int(2, magic).bytes(13, ByteArray(0)).build()
}

/** The glasses' own settings (service 0x09): only asked what they are, never changed. */
object G2Setting {
  /** Asks for the basic settings, which come with the battery and the firmware. */
  fun ask(magic: Int) = Proto().int(1, 2).int(2, magic).bytes(4, Proto().int(1, 1).build()).build()
}

/** What the glasses say of themselves: 0–100, charging, the right arm's firmware. */
data class G2Info(val battery: Int?, val charging: Boolean?, val firmware: String?)

/** The battery and firmware in a settings message (service 0x09), sent asked or not; null for others. */
fun g2Info(payload: ByteArray): G2Info? {
  val top = parseProto(payload)
  if (top[1] != 2 && top[1] != 3) return null
  val f = parseProto(top[4] as? ByteArray ?: return null)
  val battery = (f[12] as? Int)?.takeIf { it in 0..100 }
  val firmware = (f[6] as? ByteArray)?.let { String(it, Charsets.UTF_8) }
  if (battery == null && firmware == null) return null
  return G2Info(battery, (f[13] as? Int)?.let { it != 0 }, firmware)
}

/** EvenHub messages (service 0xE0): the page an app draws, and its mic. */
object EvenHub {
  const val WIDTH = 576
  const val HEIGHT = 288

  private fun message(cmd: Int, slot: Int, body: ByteArray, magic: Int = 0) =
      Proto().int(1, cmd).int(2, magic).bytes(slot, body).build()

  private fun text(id: Int, x: Int, y: Int, w: Int, h: Int, content: String, capture: Boolean, border: Int = 0) =
      Proto()
          .int(1, x).int(2, y).int(3, w).int(4, h)
          .int(5, border).int(6, if (border > 0) 15 else 0).int(7, if (border > 0) 6 else 0).int(8, if (border > 0) 8 else 0)
          .int(9, id).string(10, "c$id").int(11, if (capture) 1 else 0).string(12, content)
          .build()

  /**
   * A page of [content] filling the display. Container 0 is a 1×1 empty one that takes the touches,
   * as MentraOS does, so no visible container carries the flag.
   */
  fun page(content: String, first: Boolean, magic: Int): ByteArray {
    val containers =
        Proto()
            .int(1, 2)
            .bytes(3, text(0, 0, 0, 1, 1, "", capture = true))
            .bytes(3, text(1, 0, 0, WIDTH, HEIGHT, content, capture = false, border = 1))
            .build()
    return if (first) message(0, 3, containers, magic) else message(7, 7, containers, magic)
  }

  fun updateText(content: String): ByteArray {
    val bytes = content.toByteArray(Charsets.UTF_8)
    return message(5, 9, Proto().int(1, 1).int(3, 0).int(4, bytes.size).bytes(5, bytes).build())
  }

  fun shutdown() = message(9, 11, Proto().int(1, 0).build())

  fun heartbeat() = message(12, 14, ByteArray(0))

  fun mic(on: Boolean, magic: Int) = message(15, 18, Proto().int(1, if (on) 1 else 0).build(), magic)
}

/** What the wearer did on the touchpad or the R1 ring, or the glasses closing the page. */
enum class G2Touch { TAP, DOUBLE_TAP, UP, DOWN, CLOSED }

/** The mic sends 200 bytes of LC3 at a time, five 10 ms frames, then a 5-byte trailer. */
const val G2_MIC_BYTES = 200
const val G2_LC3_FRAME_BYTES = 40

/**
 * A touch from an EvenHub "event to app" message (cmd 2), the touchpad's and the ring's alike;
 * null for anything else, the ring's own events (types 9 and 10) and the page coming to the front
 * included.
 */
fun g2Touch(payload: ByteArray): G2Touch? {
  val top = parseProto(payload)
  if (top[1] != 2) return null
  val event = parseProto(top[13] as? ByteArray ?: return null)
  // A system event (field 3) has its type in field 1, one on a text container (field 2) in field 3.
  // A click leaves the type out, as protobuf does a default.
  val sys = event[3] as? ByteArray
  val text = event[2] as? ByteArray
  val type =
      when {
        sys != null -> parseProto(sys)[1] as? Int ?: 0
        text != null -> parseProto(text)[3] as? Int ?: 0
        else -> return null
      }
  return when (type) {
    0 -> G2Touch.TAP
    1 -> G2Touch.UP
    2 -> G2Touch.DOWN
    3 -> G2Touch.DOUBLE_TAP
    // Abnormal and system exit: the page is gone. Foreground exit (5) only puts it behind
    // something for a moment.
    6, 7 -> G2Touch.CLOSED
    else -> null
  }
}
