package app.jarvis.glasses.chat

import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen of the browser Jarvis works in, as `GET /api/browser/screen` gives it (docs/api.md,
 * "The browser's screen", in jarvis-voice-agent): a base64 JPEG, and its size in the browser's own
 * points, which is what a press is sent in.
 */
@Serializable
data class BrowserScreen(val jpeg: String = "", val width: Int = 0, val height: Int = 0, val url: String = "", val title: String = "") {
  /** What the card says above the picture: the page's title, or its address when it has none. */
  val heading: String
    get() = title.trim().ifEmpty { url.trim() }

  /**
   * Where on the browser's own screen a point on the picture is: ([x], [y]) on a picture drawn
   * [shownWidth] by [shownHeight]. Null before there is a picture to press on. A finger dragged off
   * the picture's edge stays on the edge.
   */
  fun at(x: Float, y: Float, shownWidth: Int, shownHeight: Int): Point? {
    if (width <= 0 || height <= 0 || shownWidth <= 0 || shownHeight <= 0) return null
    return Point((x / shownWidth * width).roundToInt().coerceIn(0, width), (y / shownHeight * height).roundToInt().coerceIn(0, height))
  }

  /** The middle of the screen, where the scroll buttons turn the wheel. */
  val middle: Point
    get() = Point(width / 2, height / 2)

  data class Point(val x: Int, val y: Int)
}

/** What a person does to the browser, as `POST /api/browser/input` takes each one. */
object BrowserInput {
  /** Jarvis takes at most this many events at once. */
  const val MOST = 80

  /** The keys Jarvis passes on as keys; anything else is typed as [text]. */
  const val ENTER = "Enter"
  const val BACKSPACE = "Backspace"

  /** One turn of the scroll buttons, as the web's Up and Down (ui/Browser.ts there). */
  const val WHEEL = 500

  /** [type]: "down", "move" or "up". A tap is down then up. */
  fun mouse(type: String, at: BrowserScreen.Point): JsonObject = buildJsonObject {
    put("kind", "mouse")
    put("type", type)
    put("x", at.x)
    put("y", at.y)
  }

  /** Scrolling at [at]: [dy] down the page, negative for up. */
  fun wheel(at: BrowserScreen.Point, dy: Int): JsonObject = buildJsonObject {
    put("kind", "wheel")
    put("x", at.x)
    put("y", at.y)
    put("dy", dy)
  }

  /** Typed into whatever has the keyboard on the page. */
  fun text(text: String): JsonObject = buildJsonObject {
    put("kind", "text")
    put("text", text)
  }

  fun key(key: String): JsonObject = buildJsonObject {
    put("kind", "key")
    put("key", key)
  }

  val back: JsonObject = buildJsonObject { put("kind", "back") }

  /** The request's body: `{"events": [...]}`. */
  fun body(events: List<JsonObject>): JsonObject = buildJsonObject { put("events", buildJsonArray { for (e in events) add(e) }) }
}

/**
 * A hand on the browser's picture, as the web's (ui/BrowserView.ts there): a press goes at once,
 * a drag's moves are gathered and go as it happens ([flush], from the loop that fetches the
 * picture), the release takes the last of them with it. Everything is sent one request after
 * another, in the order it was done, so nothing overtakes: a release never lands before its press.
 *
 * [post] is `POST /api/browser/input`; what it throws is [said], and "" once one gets through.
 */
class BrowserHand(
    private val scope: CoroutineScope,
    private val post: suspend (List<JsonObject>) -> Unit,
    private val said: (String) -> Unit = {},
) {
  private val sending = Mutex()
  private val moves = ArrayList<JsonObject>()

  /** A finger is down on the picture. */
  var held = false
    private set

  fun down(at: BrowserScreen.Point) {
    held = true
    moves.clear()
    send(listOf(BrowserInput.mouse("down", at)))
  }

  fun move(at: BrowserScreen.Point) {
    if (held) moves += BrowserInput.mouse("move", at)
  }

  fun up(at: BrowserScreen.Point?) {
    if (!held) return
    held = false
    send(taken() + listOfNotNull(at?.let { BrowserInput.mouse("up", it) }))
  }

  /** The touch was taken away (a second finger, the list scrolling): let go in a corner, so nothing on the page is pressed. */
  fun cancel() {
    if (!held) return
    held = false
    moves.clear()
    val corner = BrowserScreen.Point(0, 0)
    send(listOf(BrowserInput.mouse("move", corner), BrowserInput.mouse("up", corner)))
  }

  /** The drag's moves so far, sent while it is still under way, so a slider is seen to follow the finger. */
  fun flush() {
    if (held) send(taken())
  }

  /** [events] after everything sent before them; more than Jarvis takes at once go as several requests. */
  fun send(events: List<JsonObject>) {
    if (events.isEmpty()) return
    // Undispatched callers queue on the mutex in the order they came: it is fair.
    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
      sending.withLock {
        for (some in events.chunked(BrowserInput.MOST)) {
          try {
            post(some)
            said("")
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            said(e.message ?: "The browser didn't take that")
            break
          }
        }
      }
    }
  }

  /** Waits until everything sent so far has gone: the next picture is asked for after it, so it shows what the press did. */
  suspend fun settled() = sending.withLock {}

  private fun taken(): List<JsonObject> = moves.toList().also { moves.clear() }
}
