package app.jarvis.tv

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.jarvis.tv.Voice.Companion.TAG
import app.jarvis.tv.jarvis.Jarvis
import app.jarvis.tv.jarvis.Panel
import app.jarvis.tv.jarvis.PanelRow
import app.jarvis.tv.jarvis.Reading
import app.jarvis.tv.jarvis.Speaker
import app.jarvis.tv.jarvis.Turn
import app.jarvis.tv.jarvis.TvPrefs
import app.jarvis.tv.jarvis.VoiceEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TV's side of Jarvis: who it's signed in as, the Home panels and their readings (the same as
 * the phone's and the web's), and the conversation: what was asked, what Jarvis is doing, its
 * answer, spoken through the TV. Tiles are made and removed by asking Jarvis, as on the web.
 */
class TvJarvis(context: Context, private val scope: CoroutineScope) {
  val prefs = TvPrefs(context)
  private val jarvis = Jarvis(prefs)
  val speaker = Speaker(context)

  var signedIn by mutableStateOf(prefs.signedIn)
    private set

  var panels by mutableStateOf(emptyList<Panel>())
    private set

  /** Readings of tiles that read on their own, by [thingKey]. */
  val readings = mutableStateMapOf<String, Reading>()

  /** Tiles whose button was pressed and hasn't answered yet, by [thingKey]. */
  val busy = mutableStateMapOf<String, Boolean>()

  /** The tile waiting for the second press its button needs ([needsSecondPress]), by [thingKey]; for a few seconds. */
  var armed by mutableStateOf<String?>(null)
    private set

  private var disarming: Job? = null

  /** Signing in: the code to approve on Jarvis web, who approved it, or why it didn't work. */
  var pairCode by mutableStateOf<String?>(null)
    private set

  var pairError by mutableStateOf<String?>(null)
    private set

  private var pairing: Job? = null

  /** The conversation on screen: what was asked, what Jarvis is doing, and its answer. */
  var heard by mutableStateOf("")
    private set

  var working by mutableStateOf("")
    private set

  var answer by mutableStateOf("")
    private set

  var picture by mutableStateOf<ImageBitmap?>(null)
    private set

  /**
   * A short line for what isn't an answer: a button's reply, a removal, something that went wrong.
   * Kept apart from the answer, so it never wipes one, and gone after a few seconds.
   */
  var notice by mutableStateOf("")
    private set

  private var noticeShown: Job? = null

  /**
   * The last few turns and when they were said, sent with each question so "turn it off" knows
   * what "it" is. Half an hour old, they're dropped, as Jarvis drops its own shared log.
   */
  private val turns = ArrayDeque<Pair<Long, Turn>>()

  private var asking: Job? = null
  private var polling: Job? = null
  private val askedAt = HashMap<String, Long>()

  /** Polls the panels while Home is on screen; Jarvis pushes nothing when they change. */
  fun startPolling() {
    if (polling?.isActive == true || !signedIn) return
    polling =
        scope.launch {
          while (isActive) {
            refresh(quietly = true)
            delay(20_000)
          }
        }
  }

  fun stopPolling() {
    polling?.cancel()
    polling = null
  }

  /** Reads the panels again. [quietly], a failure is only logged: the poll will try again, and the screen stays as it is. */
  suspend fun refresh(quietly: Boolean = false) {
    if (!signedIn) return
    try {
      panels = jarvis.panels().panels
      readTiles()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      if (quietly && (e as? Jarvis.JarvisException)?.signedOut != true) Log.w(TAG, "panels: ${e.message}") else failed(e)
    }
  }

  /** Tiles that read on their own: the kept reading first, then a fresh one if it's stale. Each at most once a minute. */
  private fun readTiles() {
    val now = System.currentTimeMillis()
    for (p in panels) for (r in p.rows) {
      if (!r.read) continue
      val k = thingKey(p, r)
      if (now - (askedAt[k] ?: 0) < 60_000) continue
      askedAt[k] = now
      scope.launch {
        try {
          var got = jarvis.read(p.id, r.key, cached = true)
          readings[k] = got
          if (got.stale) {
            got = jarvis.read(p.id, r.key, cached = false)
            readings[k] = got
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Said on the tile, as the phone does, rather than "…" for good.
          readings[k] = Reading(text = e.message ?: "Couldn't read it", failed = true)
        }
      }
    }
  }

  /** OK on a tile: its button, when it has one plain button. False when it has none, to open the tile instead. */
  fun press(panel: Panel, row: PanelRow): Boolean {
    val button = firstButton(row) ?: return false
    val k = thingKey(panel, row)
    disarming?.cancel()
    if (needsSecondPress(row, button) && armed != k) {
      armed = k
      disarming =
          scope.launch {
            delay(4_000)
            armed = null
          }
      return true
    }
    armed = null
    act(panel, row, button.action, null, null)
    return true
  }

  fun act(panel: Panel, row: PanelRow, action: String, member: String?, value: String?) {
    val k = thingKey(panel, row)
    if (busy[k] == true) return
    busy[k] = true
    scope.launch {
      try {
        val said = jarvis.act(panel.id, row.key, action, member, value)
        if (said.isNotEmpty()) tell(said)
        delay(1_500)
        refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed(e)
      } finally {
        busy.remove(k)
      }
    }
  }

  fun removePanel(panel: Panel) {
    scope.launch {
      try {
        tell("Removed ${jarvis.removePanel(panel.id)}")
        refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed(e)
      }
    }
  }

  /** A to-do: ticked or unticked, added, or the ticked ones cleared. */
  fun note(panel: Panel, tick: String? = null, add: String? = null, clear: Boolean = false) {
    scope.launch {
      try {
        jarvis.note(panel.id, add = add, tick = tick, clear = clear)
        refresh()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        failed(e)
      }
    }
  }

  /**
   * Signs the TV in as a person: a code to approve on Jarvis web (Family → Sign in another
   * screen), waited on until approved or out of time. Then the panels are read.
   */
  fun pair() {
    pairing?.cancel()
    pairCode = null
    pairError = null
    pairing =
        scope.launch {
          try {
            val code = jarvis.pairStart()
            pairCode = code.code
            when (jarvis.pairAwait(code)) {
              is Jarvis.Paired.Approved -> {
                // Whoever signed in now, the last person's conversation isn't theirs.
                turns.clear()
                signedInNow()
              }
              Jarvis.Paired.Expired -> pairError = "The code ran out before it was approved."
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            pairError = e.message ?: "Jarvis didn't answer"
          } finally {
            pairCode = null
          }
        }
  }

  fun stopPairing() {
    pairing?.cancel()
    pairing = null
    pairCode = null
    pairError = null
  }

  /** A tile is removed by asking Jarvis, as there's no other way to; the panels are read again when it's done. */
  fun removeTile(panel: Panel, row: PanelRow) = ask("Remove “${row.label}” from my “${panel.title}” Home panel.")

  /**
   * Asks Jarvis [text] and plays its answer: what it's doing as it works, its words, any picture
   * it shows, and its voice through the TV. A new question stops the last one.
   */
  fun ask(text: String, shown: String = text) {
    if (!signedIn) return tell("Sign in to Jarvis first: it's the tile under your apps.")
    interrupt()
    heard = shown
    working = "Thinking"
    answer = ""
    picture = null
    val context = recent()
    lateinit var me: Job
    me =
        scope.launch {
          var said = ""
          var failedAnswer = false
          try {
            jarvis.ask(text, context).collect { e ->
              when (e) {
                is VoiceEvent.Progress -> working = e.text
                // Away from the answer's events, so its words and voice don't wait on these.
                is VoiceEvent.Tool -> if (e.done && e.name.startsWith("panel_")) scope.launch { refresh(quietly = true) }
                is VoiceEvent.Display -> jarvis.pictureUrl(e)?.let { url -> scope.launch { load(url)?.let { if (asking === me) picture = it } } }
                is VoiceEvent.Result -> {
                  said = e.text
                  failedAnswer = e.error
                  answer = e.text
                  working = ""
                }
                is VoiceEvent.Audio -> e.mp3?.let { speaker.play(e.seq, it) }
                is VoiceEvent.Heard -> Unit
              }
            }
            // Only a real answer is kept: not "it ended early", which would read in Chat as Jarvis's words.
            if (said.isNotEmpty() && !failedAnswer) {
              remember(Turn.user(text), Turn.jarvis(said))
              jarvis.remember(text, said)
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            failed(e)
          } finally {
            // A question cut short by the next one finishes after it began: leave the next one's status alone.
            if (asking === me) working = ""
          }
        }
    asking = me
  }

  /** Stops the answer under way, words and voice, before listening again: Jarvis mustn't talk over the next question. */
  fun interrupt() {
    asking?.cancel()
    asking = null
    speaker.stop()
  }

  /** Clears the conversation from the screen and stops Jarvis talking. */
  fun quiet() {
    interrupt()
    heard = ""
    working = ""
    answer = ""
    picture = null
    notice = ""
  }

  fun signedInNow() {
    signedIn = prefs.signedIn
    if (signedIn) {
      scope.launch { refresh() }
      startPolling()
    }
  }

  fun release() {
    stopPolling()
    pairing?.cancel()
    asking?.cancel()
    speaker.release()
  }

  private fun tell(text: String) {
    notice = text
    noticeShown?.cancel()
    noticeShown =
        scope.launch {
          delay(8_000)
          notice = ""
        }
  }

  private fun remember(vararg t: Turn) {
    val now = System.currentTimeMillis()
    for (turn in t) turns.addLast(now to turn)
    while (turns.size > 12) turns.removeFirst()
  }

  private fun recent(): List<Turn> {
    val since = System.currentTimeMillis() - TURNS_KEPT_MS
    while (turns.isNotEmpty() && turns.first().first < since) turns.removeFirst()
    return turns.map { it.second }
  }

  /** A picture Jarvis shows, fetched and drawn small enough for the card, away from the main thread. */
  private suspend fun load(url: String): ImageBitmap? =
      try {
        val bytes = jarvis.picture(url)
        withContext(Dispatchers.Default) {
          val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
          var sample = 1
          while (bounds.outWidth / (sample * 2) >= PICTURE_W) sample *= 2
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        null
      }

  private fun failed(e: Exception) {
    Log.w(TAG, "jarvis: ${e.message}")
    if ((e as? Jarvis.JarvisException)?.signedOut == true) {
      prefs.signOut()
      signedIn = false
      panels = emptyList()
      turns.clear()
      return tell("Jarvis signed this TV out. Sign in again from Home.")
    }
    tell(e.message ?: "Jarvis didn't answer")
  }

  private companion object {
    const val TURNS_KEPT_MS = 30 * 60_000L

    /** About the answer card's picture width in pixels; a camera's full frame is far more. */
    const val PICTURE_W = 640
  }
}
