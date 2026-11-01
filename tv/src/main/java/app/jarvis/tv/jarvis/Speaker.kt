package app.jarvis.tv.jarvis

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.TreeMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Jarvis's voice on the TV: the answer's mp3 sentences ([VoiceEvent.Audio]) played one after
 * another in their order, through the TV's speakers. Whatever else is playing ducks under it
 * while it speaks (transient focus), and comes back up once it has finished.
 *
 * `play(0, …)` starts a new answer, cutting off what is left of the last. A sentence Jarvis
 * couldn't voice is simply never played: the next one follows on.
 *
 * Safe to call from any thread; the players live on the main thread. Each sentence goes through a
 * temporary file (MediaPlayer on Android 8 plays files reliably, bytes less so), deleted once said.
 */
class Speaker(context: Context) {
  private val context = context.applicationContext
  private val audio = this.context.getSystemService(AudioManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private val disk = Executors.newSingleThreadExecutor()

  private val _speaking = MutableStateFlow(false)
  /** Whether Jarvis is speaking (or between two sentences of one answer). */
  val speaking: StateFlow<Boolean> = _speaking

  // Main thread only, below.
  private val waiting = TreeMap<Int, File>()
  private var player: MediaPlayer? = null
  private var playing: File? = null
  /** Which answer is being said: a sentence written for an earlier one is dropped when it arrives. */
  private var answer = 0
  private var focused = false
  private var released = false

  private val focus =
      AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
          .setAudioAttributes(SPEECH)
          .setOnAudioFocusChangeListener({ change -> if (change == AudioManager.AUDIOFOCUS_LOSS) stopNow() }, main)
          .build()

  /** Done speaking once nothing more came for a moment: the next sentence is often still on its way. */
  private val quiet = Runnable {
    if (player == null && waiting.isEmpty()) {
      abandonFocus()
      _speaking.value = false
    }
  }

  init {
    // Sentences left behind by a process that was stopped mid-answer.
    disk.execute { this.context.cacheDir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".mp3") }?.forEach { it.delete() } }
  }

  /** Says sentence [seq] of the answer once those before it have been said. */
  fun play(seq: Int, mp3: ByteArray) =
      onMain {
        if (released || mp3.isEmpty()) return@onMain
        if (seq == 0) stopNow()
        main.removeCallbacks(quiet)
        _speaking.value = true
        val forAnswer = answer
        disk.execute {
          val file = runCatching { File.createTempFile(PREFIX, ".mp3", context.cacheDir).apply { writeBytes(mp3) } }.getOrNull()
          main.post { if (file != null) arrived(forAnswer, seq, file) else idleSoon() }
        }
      }

  /** Stops speaking now and drops what was still to be said. */
  fun stop() = onMain { stopNow() }

  /** For good: stops, and lets go of the threads. */
  fun release() =
      onMain {
        stopNow()
        released = true
        disk.shutdown()
      }

  private fun arrived(forAnswer: Int, seq: Int, file: File) {
    if (released || forAnswer != answer) {
      file.delete()
      return
    }
    waiting.put(seq, file)?.delete()
    if (player == null) next()
  }

  /** The lowest sentence waiting: in order, and past one that never came. */
  private fun next() {
    val (seq, file) = waiting.pollFirstEntry() ?: return idleSoon()
    main.removeCallbacks(quiet)
    takeFocus()
    val mp = MediaPlayer()
    player = mp
    playing = file
    try {
      mp.setAudioAttributes(SPEECH)
      mp.setOnCompletionListener { finished(it) }
      mp.setOnErrorListener { p, what, extra ->
        Log.w(TAG, "sentence $seq failed: $what/$extra")
        finished(p)
        true
      }
      mp.setOnPreparedListener { if (player === it) it.start() }
      mp.setDataSource(file.path)
      mp.prepareAsync()
    } catch (e: Exception) {
      Log.w(TAG, "sentence $seq unplayable", e)
      finished(mp)
    }
  }

  private fun finished(mp: MediaPlayer) {
    if (player !== mp) return
    mp.release()
    player = null
    playing?.delete()
    playing = null
    next()
  }

  private fun idleSoon() {
    if (player != null || waiting.isNotEmpty()) return
    main.removeCallbacks(quiet)
    main.postDelayed(quiet, GAP_MS)
  }

  private fun stopNow() {
    answer++
    main.removeCallbacks(quiet)
    player?.let { runCatching { it.release() } }
    player = null
    playing?.delete()
    playing = null
    for (f in waiting.values) f.delete()
    waiting.clear()
    abandonFocus()
    _speaking.value = false
  }

  private fun takeFocus() {
    if (focused) return
    focused = runCatching { audio.requestAudioFocus(focus) }.getOrNull() == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
  }

  private fun abandonFocus() {
    if (!focused) return
    runCatching { audio.abandonAudioFocusRequest(focus) }
    focused = false
  }

  private inline fun onMain(crossinline f: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) f() else main.post { f() }
  }

  private companion object {
    const val TAG = "JarvisTV"
    const val PREFIX = "jarvis-say"
    /** How long after a sentence to wait for the next before letting the TV's own sound back up. */
    const val GAP_MS = 1_500L
    val SPEECH: AudioAttributes =
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
  }
}
