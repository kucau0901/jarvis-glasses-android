package app.jarvis.glasses.live

import kotlin.math.log10

/**
 * How loud a voice is, 0..1, for the conversation pill's wave (ui/LiveWave.kt): the wearer's and
 * Jarvis's, as the web app's VoiceLevels (src/app/audio.ts there) reads them. A level is the
 * amplitude WebRTC reports (0..1, linear), or a microphone's samples as a fraction of full scale.
 */
object VoiceLevel {
  /** Quieter than this is silence: room noise, a breath. */
  private const val FLOOR_DB = -50.0

  /** Louder than this is as loud as the wave goes. */
  private const val CEILING_DB = -12.0

  /** On a decibel scale, as loudness is heard: a linear amplitude sits near zero for all but a shout. */
  fun loudness(amplitude: Double): Float {
    if (amplitude.isNaN() || amplitude <= 0.0) return 0f
    val db = 20 * log10(amplitude)
    return ((db - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0.0, 1.0).toFloat()
  }

  /**
   * A peak as WebRTC reports it ("audioLevel": the loudest sample of the last ~0.1 s, after its
   * gain control), as loud as the RMS of a microphone's samples would be: speech peaks sit about
   * 11 dB above its RMS, and read as they are, a Live voice pinned the wave at its top.
   */
  fun peakLoudness(peak: Double): Float = loudness(peak * PEAK_TO_RMS)

  private const val PEAK_TO_RMS = 0.28

  /** Rises fast, so a sound registers at once; falls slowly, so the wave doesn't strobe between syllables. */
  fun smooth(previous: Float, now: Float, rise: Float = 0.7f, fall: Float = 0.3f): Float =
      previous + (now - previous) * (if (now > previous) rise else fall)
}

/** The voices in a session, for the wave: the wearer's, Jarvis's, and whether Jarvis is working on an answer. */
data class Voices(val user: Float = 0f, val agent: Float = 0f, val thinking: Boolean = false)
