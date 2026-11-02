package app.jarvis.glasses.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelTest {
  @Test fun loudnessIsHeardOnADecibelScale() {
    assertEquals(0f, VoiceLevel.loudness(0.0), 0f)
    assertEquals(0f, VoiceLevel.loudness(Double.NaN), 0f)
    // Below -50 dB is silence, above -12 dB as loud as it goes.
    assertEquals(0f, VoiceLevel.loudness(0.002), 0f)
    assertEquals(1f, VoiceLevel.loudness(0.5), 0f)
    // Ordinary speech (-30 dB) is about half way, not near zero as a linear 0.03 would be.
    assertEquals(0.53f, VoiceLevel.loudness(0.0316), 0.01f)
  }

  @Test fun aPeakIsReadAsLoudAsItsSpeech() {
    // A speech peak of -10 dB is speech of about -21 dB: loud, not off the top.
    assertEquals(0.75f, VoiceLevel.peakLoudness(0.316), 0.02f)
    assertTrue(VoiceLevel.peakLoudness(0.316) < VoiceLevel.loudness(0.316))
    assertEquals(0f, VoiceLevel.peakLoudness(0.0), 0f)
  }

  @Test fun aLevelRisesFastAndFallsSlowly() {
    val up = VoiceLevel.smooth(0f, 1f)
    val down = VoiceLevel.smooth(1f, 0f)
    assertTrue(up > 1f - down)
    assertEquals(0.7f, up, 1e-6f)
    assertEquals(0.7f, down, 1e-6f)
  }
}
