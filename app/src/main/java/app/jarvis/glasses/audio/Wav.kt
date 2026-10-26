package app.jarvis.glasses.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM wrapped in a WAV header, the plainest recording Jarvis takes. */
object Wav {
  fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + pcm.size)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16) // PCM chunk size
    header.putShort(1) // PCM
    header.putShort(1) // mono
    header.putInt(sampleRate)
    header.putInt(sampleRate * 2) // byte rate
    header.putShort(2) // block align
    header.putShort(16) // bits per sample
    header.put("data".toByteArray())
    header.putInt(pcm.size)
    return header.array() + pcm
  }
}
