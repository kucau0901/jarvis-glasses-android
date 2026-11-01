package app.jarvis.glasses.ui

import app.jarvis.glasses.conversation.Conversation.Status
import app.jarvis.glasses.live.LiveVoice.State
import app.jarvis.glasses.live.Voices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWaveTest {
  @Test fun theWaveShowsOnlyInAConversation() {
    assertNull(waveInputs(Status.IDLE, State.OFF, Voices(0.5f, 0.5f), 0.5f))
    assertEquals(WaveInputs(connecting = 1f), waveInputs(Status.IDLE, State.CONNECTING, Voices(), 0f))
    assertEquals(WaveInputs(0.4f, 0.6f, 1f), waveInputs(Status.IDLE, State.LIVE, Voices(0.4f, 0.6f, thinking = true), 0.9f))
    // A Live session that couldn't open hands over to Talk, which shows as connecting while the glasses are routed to.
    assertEquals(WaveInputs(connecting = 1f), waveInputs(Status.OPENING, State.OFF, Voices(), 0f))
    assertEquals(WaveInputs(user = 0.3f), waveInputs(Status.LISTENING, State.OFF, Voices(), 0.3f))
    assertEquals(WaveInputs(think = 1f), waveInputs(Status.THINKING, State.OFF, Voices(), 0.3f))
    assertEquals(WaveInputs(agent = TALK_SPEAKING), waveInputs(Status.SPEAKING, State.OFF, Voices(), 0f))
  }

  @Test fun quietIsAFlatLineAndAVoiceRaisesIt() {
    val quiet = MutableWave()
    assertTrue((0 until 25).all { x -> waveReach(x, 25, 1.3f, quiet) == 0 })
    val loud = MutableWave(WaveInputs(agent = 1f))
    val reaches = (0 until 25).map { x -> waveReach(x, 25, 1.3f, loud) }
    assertTrue(reaches.all { it in 0..WAVE_ROWS / 2 })
    // Tallest in the middle, low at the ends.
    assertTrue(reaches.subList(10, 15).max() > reaches.first())
  }

  @Test fun itEasesTowardItsTargetsWhateverTheFrameRate() {
    val at60 = MutableWave()
    repeat(60) { at60.ease(WaveInputs(user = 1f), 1f / 60) }
    val at30 = MutableWave()
    repeat(30) { at30.ease(WaveInputs(user = 1f), 1f / 30) }
    assertEquals(at60.user, at30.user, 1e-4f)
    // Thinking settles more slowly than a voice.
    val slow = MutableWave().apply { ease(WaveInputs(user = 1f, think = 1f), 0.1f) }
    assertTrue(slow.think < slow.user)
  }

  @Test fun itsWordsHoldThroughAPauseBetweenWords() {
    val w = MutableWave(WaveInputs(agent = 1f))
    assertEquals("JARVIS", waveLabel(w))
    w.ease(WaveInputs(), 0.3f)
    assertTrue(w.agent < 0.01f)
    assertEquals("JARVIS", waveLabel(w))
    repeat(60) { w.ease(WaveInputs(), 0.05f) }
    assertEquals("LISTENING", waveLabel(w))
  }

  @Test fun itSaysWhatIsGoingOn() {
    assertEquals("CONNECTING", waveLabel(MutableWave(WaveInputs(connecting = 1f))))
    assertEquals("THINKING", waveLabel(MutableWave(WaveInputs(think = 1f))))
    // Jarvis saying "let me check" while it works: speaking wins.
    assertEquals("JARVIS", waveLabel(MutableWave(WaveInputs(agent = 0.5f, think = 1f))))
    assertEquals("LISTENING", waveLabel(MutableWave(WaveInputs(user = 0.6f, agent = 0.4f))))
    assertEquals("LISTENING", waveLabel(MutableWave()))
  }

  @Test fun talkBackHearsWhatIsGoingOn() {
    assertEquals("speaking", waveSpoken("JARVIS"))
    assertEquals("listening", waveSpoken("LISTENING"))
    assertEquals("thinking", waveSpoken("THINKING"))
  }
}
