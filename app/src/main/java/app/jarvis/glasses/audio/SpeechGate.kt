package app.jarvis.glasses.audio

import kotlin.math.sqrt

/**
 * Decides, from loudness alone, when someone has started and finished speaking, so a turn needs
 * no touch on the glasses: a question ends at [endSilenceMs] of quiet after speech, and nobody
 * speaking within [startTimeoutMs] ends the turn without sending anything.
 *
 * Speech is a frame louder than both [minLevel] and [noiseRatio] times the room's noise, which is
 * learnt from the quiet frames as it goes. It has to last [minSpeechMs] to count, so a cough or a
 * click does not open a question.
 */
class SpeechGate(
    private val sampleRate: Int,
    private val startTimeoutMs: Long,
    private val endSilenceMs: Long = 1_200,
    private val maxMs: Long = 45_000,
    private val minSpeechMs: Long = 200,
    private val minLevel: Double = 400.0,
    private val noiseRatio: Double = 2.5,
) {
  enum class Verdict { LISTENING, NO_SPEECH, DONE }

  private var elapsedMs = 0.0
  private var voicedMs = 0.0
  private var silenceMs = 0.0
  private var noise = 0.0
  var heardSpeech = false
    private set

  /** Feeds one buffer of 16-bit samples; read [Verdict] after each. */
  fun feed(samples: ShortArray, count: Int = samples.size): Verdict {
    if (count <= 0) return Verdict.LISTENING
    var sum = 0.0
    for (i in 0 until count) {
      val s = samples[i].toDouble()
      sum += s * s
    }
    val level = sqrt(sum / count)
    val ms = count * 1000.0 / sampleRate
    elapsedMs += ms

    val voiced = level > minLevel && (noise == 0.0 || level > noise * noiseRatio)
    if (!voiced) noise = if (noise == 0.0) level else noise * 0.95 + level * 0.05

    if (!heardSpeech) {
      voicedMs = if (voiced) voicedMs + ms else 0.0
      if (voicedMs >= minSpeechMs) heardSpeech = true
      else if (elapsedMs >= startTimeoutMs) return Verdict.NO_SPEECH
    } else {
      silenceMs = if (voiced) 0.0 else silenceMs + ms
      if (silenceMs >= endSilenceMs) return Verdict.DONE
    }
    return if (elapsedMs >= maxMs) (if (heardSpeech) Verdict.DONE else Verdict.NO_SPEECH) else Verdict.LISTENING
  }
}
