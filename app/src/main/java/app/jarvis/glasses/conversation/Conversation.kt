package app.jarvis.glasses.conversation

import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn
import app.jarvis.glasses.jarvis.VoiceEvent
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The glasses' microphone: one utterance as WAV, or null when nobody spoke within [startTimeoutMs]
 * or speech was still going [maxMs] after the microphone opened (too long to be a question).
 */
fun interface Ears {
  suspend fun listen(startTimeoutMs: Long, maxMs: Long): ByteArray?
}

/** The glasses' speakers. [open] routes audio to the glasses and says whether they are there. */
interface Mouth {
  suspend fun open(): Boolean

  fun close()

  /** A short tone: the microphone is open now. */
  suspend fun cue()

  /** Another short tone, unlike [cue]: the microphone is closed until "Hey Meta, open Jarvis" again. */
  suspend fun closingCue()

  suspend fun play(audio: ByteArray)

  /** The phone's own voice, for when Jarvis sends words without audio. */
  suspend fun say(text: String)
}

/** The glasses' camera: one photo as JPEG, about 1024 px, or null when it cannot take one. */
fun interface Eyes {
  suspend fun photo(): ByteArray?
}

/** What the conversation needs from Jarvis ([app.jarvis.glasses.jarvis.JarvisClient]). */
interface Brain {
  suspend fun transcribe(wav: ByteArray): String

  suspend fun ask(text: String, context: List<Turn>, photoJpeg: ByteArray?, onEvent: suspend (VoiceEvent) -> Unit): VoiceEvent.Answer

  suspend fun speech(text: String): ByteArray?
}

/** The conversation Talk shares with Chat and Live ([app.jarvis.glasses.chat.ChatLog]). */
interface Memory {
  /** What a question goes with: the recent part of the conversation. */
  fun recent(): List<Turn>

  /** [turns] just said, kept; after [alert] when they reply to one Jarvis said first. */
  fun keep(turns: List<Turn>, alert: Alert? = null)
}

/**
 * A spoken conversation through the glasses, with nothing to touch: it starts from "Hey Meta,
 * open Jarvis", from the app, or from Jarvis itself (an alert, said first), and each answer is
 * followed by the microphone opening again for a follow-up while [followUps] is on. Silence, or a
 * closing like "thanks" or "dah", ends it, and a closing tone says the microphone is shut.
 *
 * The glasses' microphone hears everyone nearby (a TV, people talking near you), so each question
 * is asked as heard by an open microphone (`openMic`): when nothing heard was said to Jarvis, it
 * answers [VoiceEvent.Answer.notForMe], and nothing is kept or said and the conversation ends.
 * Speech still going at the cap ([followUpMaxMs] for a follow-up) is not a question and is never
 * sent; a photo goes only with the wearer's opening question.
 *
 * The recent conversation, the one Chat shows ([memory]), goes with each question as `context`, so
 * "and tomorrow?" and "yes, do that" (after an alert) are understood, and each question answered is
 * kept there with its answer.
 */
class Conversation(
    private val scope: CoroutineScope,
    private val ears: Ears,
    private val mouth: Mouth,
    private val eyes: Eyes,
    private val brain: Brain,
    private val memory: Memory,
    private val followUps: () -> Boolean,
) {
  /** OPENING while the glasses' microphone and speakers are routed to, which takes up to a few seconds. */
  enum class Status { IDLE, OPENING, LISTENING, THINKING, SPEAKING }

  private val _status = MutableStateFlow(Status.IDLE)
  val status: StateFlow<Status> = _status

  private val _note = MutableStateFlow("")
  /** The last thing worth showing on the phone: progress, a failure, why nothing happened. */
  val note: StateFlow<String> = _note

  private var job: Job? = null
  // An alert said first and not yet replied to: asked with, and kept before the reply.
  private var opened: Alert? = null

  val isBusy: Boolean
    get() = job?.isActive == true

  /** Starts a conversation unless one is going; [opening] is an alert Jarvis says first. */
  @Synchronized
  fun start(opening: Alert? = null): Boolean {
    if (isBusy) return false
    job = scope.launch { run(opening) }
    return true
  }

  fun stop() {
    job?.cancel()
  }

  /** Waits for the conversation going, if any, to end. */
  suspend fun join() {
    job?.join()
  }

  private suspend fun run(opening: Alert?) {
    opened = opening
    // At once: a Live session that couldn't open hands over to this, and the phone shows it going on.
    _status.value = Status.OPENING
    // The microphone was opened, so its closing gets the closing tone.
    var listened = false
    try {
      if (!mouth.open()) {
        _note.value = "Couldn't open the microphone and speaker"
        return
      }
      if (opening != null) {
        _status.value = Status.SPEAKING
        // With its title: a family message says who wrote it.
        val audio = runCatching { brain.speech(opening.said) }.getOrNull()
        if (audio != null) mouth.play(audio) else mouth.say(opening.said)
      }
      var wait = if (opening != null) replyWaitMs else firstWaitMs
      var cap = maxMs
      // The wearer's own opening question, the only one a photo goes with: a follow-up or a reply to
      // an alert may be someone else's "what's this?".
      var first = opening == null
      // Heard the launch phrase or a bare "okay" since the last answer, and listened again for it.
      var relistened = false
      while (true) {
        _status.value = Status.LISTENING
        mouth.cue()
        listened = true
        // Null for nobody speaking, and for speech too long to be a question: nothing is sent.
        val wav = ears.listen(wait, cap) ?: break
        _status.value = Status.THINKING
        val heard = brain.transcribe(wav)
        if (heard.isBlank()) {
          if (first) mouth.say("I didn't catch that.")
          break
        }
        if (Phrases.isStop(heard)) break
        // The launch phrase said again, or a bare "okay": listen again, ask nothing. Once only, so a
        // TV saying "okay" now and then doesn't keep the microphone open.
        if (Phrases.isLaunchPhrase(heard) || Phrases.isAck(heard)) {
          if (relistened) break
          relistened = true
          continue
        }
        val look = first && Phrases.wantsPhoto(heard)
        val photo = if (look) eyes.photo() else null
        if (photo == null && look) _note.value = "No photo: the glasses camera isn't available"
        if (!answer(heard, photo)) break
        if (!followUps()) break
        wait = followUpWaitMs
        cap = followUpMaxMs
        first = false
        relistened = false
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      _note.value = e.message ?: "Jarvis couldn't be reached"
      runCatching { mouth.say("I couldn't reach Jarvis just now.") }
    } finally {
      // Not when stopped: the glasses came off, or Live took over.
      if (listened && currentCoroutineContext().isActive) runCatching { mouth.closingCue() }
      mouth.close()
      _status.value = Status.IDLE
    }
  }

  /**
   * Asks [heard] and plays the answer; false when Jarvis says nothing heard was said to it, which
   * ends the conversation. Its own scope, so stopping the conversation stops the playing too.
   */
  private suspend fun answer(heard: String, photo: ByteArray?): Boolean = coroutineScope {
    // The alert, unless Chat has it already (it is put there as it arrives).
    val recent = memory.recent()
    val context = recent + listOfNotNull(opened?.let { o -> Turn.jarvis(o.told).takeIf { t -> recent.none { it == t } } })
    _note.value = ""
    // Sentences are played as they arrive, while the rest of the answer is still coming.
    val clips = Channel<ByteArray>(Channel.UNLIMITED)
    var spoke = false
    val player = launch {
      for (clip in clips) {
        _status.value = Status.SPEAKING
        mouth.play(clip)
      }
    }
    val reply =
        try {
          brain.ask(heard, context, photo) { e ->
            when (e) {
              is VoiceEvent.Progress -> _note.value = "${e.text}…"
              is VoiceEvent.Audio -> {
                spoke = true
                clips.send(Base64.getDecoder().decode(e.base64))
              }
              else -> Unit
            }
          }
        } finally {
          clips.close()
        }
    player.join()
    // What went wrong is not kept, as Chat does; nor what wasn't said to Jarvis, which may be
    // someone else's words: not here, so not in the person's conversation on Jarvis either.
    if (reply.ok && !reply.notForMe && reply.text.isNotBlank()) {
      memory.keep(listOf(Turn.user(heard), Turn.jarvis(reply.text)), opened)
      opened = null
    }
    // Not for Jarvis: a spoken answer then has no words, so nothing is said; the G2's has a short line, shown.
    if (!spoke && reply.text.isNotBlank()) {
      _status.value = Status.SPEAKING
      mouth.say(reply.text)
    }
    if (reply.notForMe) _note.value = "Nothing heard was said to Jarvis, so nothing was kept"
    !reply.notForMe
  }

  companion object {
    const val firstWaitMs = 8_000L
    const val followUpWaitMs = 5_000L
    const val replyWaitMs = 7_000L
    /** The longest a question may run, from the microphone opening: the opening one, or a reply to an alert. */
    const val maxMs = 45_000L
    /** A follow-up's: the microphone opened by itself, so a longer stretch is someone else talking. */
    const val followUpMaxMs = 15_000L
  }
}
