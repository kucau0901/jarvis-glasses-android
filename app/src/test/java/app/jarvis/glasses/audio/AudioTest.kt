package app.jarvis.glasses.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
  @Test
  fun wavHeaderDescribesSixteenBitMono() {
    val wav = Wav.wrap(ByteArray(320), 16_000)
    val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("RIFF", String(wav, 0, 4))
    assertEquals(36 + 320, b.getInt(4))
    assertEquals("WAVE", String(wav, 8, 4))
    assertEquals(1, b.getShort(22).toInt())
    assertEquals(16_000, b.getInt(24))
    assertEquals(16, b.getShort(34).toInt())
    assertEquals(320, b.getInt(40))
    assertEquals(44 + 320, wav.size)
  }

  private fun frame(level: Int) = ShortArray(320) { if (it % 2 == 0) level.toShort() else (-level).toShort() } // 20 ms at 16 kHz

  private fun run(gate: SpeechGate, frames: List<ShortArray>): SpeechGate.Verdict {
    for (f in frames) {
      val v = gate.feed(f)
      if (v != SpeechGate.Verdict.LISTENING) return v
    }
    return SpeechGate.Verdict.LISTENING
  }

  @Test
  fun aQuestionEndsAfterQuietFollowingSpeech() {
    val gate = SpeechGate(16_000, startTimeoutMs = 5_000)
    val frames = List(25) { frame(60) } + List(50) { frame(4_000) } + List(70) { frame(60) }
    assertEquals(SpeechGate.Verdict.DONE, run(gate, frames))
    assertTrue(gate.heardSpeech)
  }

  @Test
  fun nobodySpeakingEndsTheTurn() {
    val gate = SpeechGate(16_000, startTimeoutMs = 1_000)
    assertEquals(SpeechGate.Verdict.NO_SPEECH, run(gate, List(60) { frame(60) }))
  }

  @Test
  fun aClickDoesNotOpenAQuestion() {
    val gate = SpeechGate(16_000, startTimeoutMs = 1_000)
    val frames = List(10) { frame(60) } + listOf(frame(8_000)) + List(60) { frame(60) }
    assertEquals(SpeechGate.Verdict.NO_SPEECH, run(gate, frames))
  }

  @Test
  fun aPauseShorterThanTheEndDoesNotCutTheQuestion() {
    val gate = SpeechGate(16_000, startTimeoutMs = 5_000, endSilenceMs = 1_200)
    // speech, 0.6 s pause, speech again: still listening at the end
    val frames = List(30) { frame(4_000) } + List(30) { frame(60) } + List(30) { frame(4_000) }
    assertEquals(SpeechGate.Verdict.LISTENING, run(gate, frames))
  }

  @Test
  fun aLongQuestionIsCutAtTheLimit() {
    val gate = SpeechGate(16_000, startTimeoutMs = 5_000, maxMs = 2_000)
    assertEquals(SpeechGate.Verdict.DONE, run(gate, List(200) { frame(4_000) }))
  }
}
