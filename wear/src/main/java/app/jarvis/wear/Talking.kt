package app.jarvis.wear

import app.jarvis.glasses.conversation.Phrases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A conversation on the watch, as Talk has one on the phone: once an answer has been said, the
 * watch listens again for a follow-up, asked as heard by an open microphone ([ask]'s openMic), so
 * Jarvis answers only what was said to it. It ends on silence, on a closing ("thanks", "stop",
 * "terima kasih": [Phrases.isStop]), when Jarvis finds nothing was said to it or answers without
 * words, after a failure, on a second bare "okay", and when follow-ups are off.
 *
 * [listen] opens the watch's recogniser; what it hears comes back to [heard], or [missed] when it
 * heard nothing. The conversation lives while Jarvis is on screen: [end] when it isn't.
 */
class Talking(
    scope: CoroutineScope,
    private val answers: StateFlow<Asking.State>,
    private val speaking: StateFlow<Boolean>,
    private val followUps: () -> Boolean,
    private val ask: (question: String, openMic: Boolean) -> Unit,
    private val listen: () -> Unit,
    /** A breath between the answer's last word and the microphone opening again. */
    private val gapMs: Long = 400,
) {
  private var open = false
  private var relistened = false
  // The answer last acted on: each is followed up once.
  private var handled: Asking.State.Answered? = null

  private val _following = MutableStateFlow(false)
  /** Listening, or about to, for a follow-up rather than an opening question. */
  val following: StateFlow<Boolean> = _following

  init {
    scope.launch {
      combine(answers, speaking) { a, s -> a to s }.collect { (a, s) ->
        if (!open || a !is Asking.State.Answered || !a.done || s || a === handled) return@collect
        handled = a
        if (!a.ok || a.notForMe || a.answer.isBlank() || !followUps()) {
          end()
          return@collect
        }
        delay(gapMs)
        // Still wanted: not stopped, nor asked again, meanwhile.
        if (open && answers.value === a && !speaking.value) {
          _following.value = true
          relistened = false
          listen()
        }
      }
    }
    // A failure ends it.
    scope.launch { answers.collect { if (open && it is Asking.State.Failed) end() } }
  }

  /** An opening question: listen for it. */
  fun start() {
    open = true
    relistened = false
    _following.value = false
    handled = answers.value as? Asking.State.Answered
    listen()
  }

  fun heard(words: String) {
    val following = _following.value
    if (!open) return ask(words, false)
    if (following && Phrases.isStop(words)) return end()
    // A bare "okay" after an answer: listen again, ask nothing. Once only.
    if (following && Phrases.isAck(words)) {
      if (relistened) return end()
      relistened = true
      return listen()
    }
    ask(words, following)
  }

  /**
   * Nothing heard. A follow-up not coming ends the conversation, quietly: true then, and nothing
   * need be shown. An opening question not heard is worth saying so (false).
   */
  fun missed(): Boolean {
    val wasFollowing = open && _following.value
    if (wasFollowing) end()
    return wasFollowing
  }

  fun end() {
    open = false
    relistened = false
    _following.value = false
  }
}
