package app.jarvis.wear

import app.jarvis.link.Ask
import app.jarvis.link.Hello
import app.jarvis.link.Reply
import app.jarvis.link.Stop
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One question at a time, asked through the phone ([Phone]): the words the watch heard go out,
 * and what comes back is shown as it comes, the answer's voice played a sentence at a time
 * ([play]). An answer Jarvis sent no voice for (Settings has the device speak itself) is said by
 * the watch ([say]). Asking again, or [stop], hangs up the one before.
 *
 * The phone not answering at all in [firstReplyMs] (out of reach, its app not there) fails the
 * question rather than leaving it waiting; once it has answered, Jarvis may take a while, up to
 * [LONGEST_MS].
 */
class Asking(
    private val scope: CoroutineScope,
    private val phone: Phone,
    /** The watch's own id among the person's screens. */
    private val origin: () -> String,
    /** Answers spoken, or only shown. */
    private val speak: () -> Boolean,
    private val play: (seq: Int, mp3: ByteArray) -> Unit,
    private val say: (String) -> Unit,
    private val hush: () -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val firstReplyMs: Long = 15_000,
    private val longestMs: Long = LONGEST_MS,
) {
  sealed interface State {
    data object Idle : State

    /** Asked, and on its way: what Jarvis is doing meanwhile, when it says. */
    data class Asked(val question: String, val progress: String? = null) : State

    /** [done] once all of it is in, voice included. [ok] false for a failure Jarvis put into words. */
    data class Answered(val question: String, val answer: String, val ok: Boolean, val notForMe: Boolean, val done: Boolean) : State

    data class Failed(val question: String?, val why: String) : State
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state

  private var job: Job? = null
  private var asking: String? = null

  /** Whether the phone can answer, and as whom: null when it isn't in reach. */
  suspend fun hello(): Reply.Ready? = phone.request(Hello(newId()), firstReplyMs) as? Reply.Ready

  /** [openMic]: a follow-up, heard by the microphone opening again after an answer ([Ask.openMic]). */
  fun ask(question: String, openMic: Boolean = false) {
    stop()
    val id = newId()
    asking = id
    _state.value = State.Asked(question)
    val aloud = speak()
    job =
        scope.launch {
          val first = CompletableDeferred<Unit>()
          val over = CompletableDeferred<Unit>()
          var spoke = false
          // Listening before the question goes, so a quick reply isn't missed.
          val hearing =
              launch(start = CoroutineStart.UNDISPATCHED) {
                phone.replies.collect { r ->
                  if (r.id != id) return@collect
                  first.complete(Unit)
                  when (r) {
                    // A blank one is the phone saying it has the question.
                    is Reply.Progress -> if (r.text.isNotBlank()) (_state.value as? State.Asked)?.let { _state.value = it.copy(progress = r.text) }
                    is Reply.Answer -> _state.value = State.Answered(question, r.text, r.ok, r.notForMe, done = false)
                    is Reply.Audio ->
                        runCatching { Base64.getDecoder().decode(r.base64) }.getOrNull()?.let {
                          spoke = true
                          play(r.seq, it)
                        }
                    is Reply.Done -> {
                      val a = _state.value as? State.Answered
                      if (a != null) {
                        if (aloud && !spoke && a.answer.isNotBlank()) say(a.answer)
                        _state.value = a.copy(done = true)
                      } else _state.value = State.Failed(question, "Jarvis sent no answer")
                      over.complete(Unit)
                    }
                    is Reply.Failed -> {
                      _state.value = State.Failed(question, r.text)
                      over.complete(Unit)
                    }
                    else -> Unit
                  }
                }
              }
          try {
            if (!phone.send(Ask(id, question, origin(), aloud, CHARS, openMic))) {
              _state.value = State.Failed(question, "The phone isn't in reach, or hasn't the Jarvis app")
              return@launch
            }
            if (withTimeoutOrNull(firstReplyMs) { first.await() } == null) {
              _state.value = State.Failed(question, "The phone didn't answer. Is the Jarvis app on it up to date?")
              phone.send(Stop(id))
              return@launch
            }
            if (withTimeoutOrNull(longestMs) { over.await() } == null) {
              _state.value = State.Failed(question, "Jarvis took too long")
              phone.send(Stop(id))
            }
          } finally {
            hearing.cancel()
            if (asking == id) asking = null
          }
        }
  }

  /** Stops the question on its way, and the voice, and forgets it. */
  fun stop() {
    hush()
    // Taken first: the question's own ending forgets it as it is cancelled.
    val id = asking
    asking = null
    job?.cancel()
    job = null
    id?.let { scope.launch { phone.send(Stop(it)) } }
    _state.value = State.Idle
  }

  companion object {
    /** About what a written answer fits on the round screen without much scrolling. */
    const val CHARS = 300

    /**
     * The longest a whole answer is waited for. Work in Jarvis's browser takes up to twenty steps,
     * each a page opened, read or pressed, which three minutes cut short; five is what the G2's own
     * app gives an answer, and about as long as a wrist is worth holding up.
     */
    const val LONGEST_MS = 300_000L
  }
}
