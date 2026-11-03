package app.jarvis.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Listening through the watch's own recogniser (Google's, on the watch, so a question is heard
 * without the phone): one turn per press, words back as text. A copy of the TV's Voice, which
 * waits on the same move into a shared module. [refused] is called instead when the recogniser
 * won't serve this app at all. Call from the main thread.
 */
class Ears(private val context: Context, private val heard: (String) -> Unit, private val refused: () -> Unit) {
  sealed interface State {
    data object Idle : State

    /** Listening: how loud, 0 to 1, and the words so far. */
    data class Listening(val level: Float = 0f, val words: String = "") : State

    data class Failed(val why: String, val code: Int) : State
  }

  private val _state = MutableStateFlow<State>(State.Idle)
  val state: StateFlow<State> = _state

  private var recognizer: SpeechRecognizer? = null

  /** Whether the watch has a recogniser this app may use. */
  val available: Boolean
    get() = SpeechRecognizer.isRecognitionAvailable(context)

  fun listen() {
    stop()
    val r = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
    r.setRecognitionListener(Listener())
    _state.value = State.Listening()
    r.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
    )
  }

  /** Stops listening now and acts on what was heard so far, as a pause in speech would. */
  fun finish() {
    recognizer?.stopListening()
  }

  /** Stops listening, and forgets a failure. */
  fun stop() {
    recognizer?.destroy()
    recognizer = null
    _state.value = State.Idle
  }

  private inner class Listener : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      Log.i(TAG, "ready")
    }

    override fun onBeginningOfSpeech() {
      Log.i(TAG, "speech began")
    }

    // Google's recogniser reports about -2 (quiet) to 10 (loud).
    override fun onRmsChanged(rmsdB: Float) {
      val now = _state.value as? State.Listening ?: return
      _state.value = now.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
      Log.i(TAG, "speech ended")
    }

    override fun onError(error: Int) {
      Log.w(TAG, "error $error")
      recognizer?.destroy()
      recognizer = null
      if (unusable(error)) {
        _state.value = State.Idle
        refused()
      } else {
        _state.value = State.Failed(why(error), error)
      }
    }

    override fun onPartialResults(partial: Bundle?) {
      val words = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
      val now = _state.value as? State.Listening ?: return
      _state.value = now.copy(words = words)
    }

    override fun onResults(results: Bundle?) {
      val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
      Log.i(TAG, "heard \"$words\"")
      recognizer?.destroy()
      recognizer = null
      _state.value = State.Idle
      if (words.isBlank()) _state.value = State.Failed(why(SpeechRecognizer.ERROR_NO_MATCH), SpeechRecognizer.ERROR_NO_MATCH) else heard(words)
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit
  }

  companion object {
    const val TAG = "JarvisWatch"

    /** Errors that mean this way of listening won't work here at all, rather than this once. */
    private fun unusable(code: Int): Boolean = code == SpeechRecognizer.ERROR_CLIENT || code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS

    private fun why(code: Int): String =
        when (code) {
          SpeechRecognizer.ERROR_NO_MATCH -> "DIDN'T CATCH THAT"
          SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "HEARD NOTHING"
          SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NO NETWORK"
          SpeechRecognizer.ERROR_AUDIO -> "NO MIC"
          SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MIC NOT ALLOWED"
          SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "LISTENER BUSY"
          SpeechRecognizer.ERROR_SERVER -> "LISTENER FAILED"
          else -> "COULDN'T LISTEN"
        }
  }
}
