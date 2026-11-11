package app.jarvis.glasses.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserTest {
  private val screen = BrowserScreen("", 1280, 800, "https://portal.example.com/login", "Sign in")

  @Test
  fun aPointOnThePictureIsThatPlaceOnTheBrowsersScreen() {
    // Drawn at a quarter of its size: 320 by 200.
    assertEquals(BrowserScreen.Point(640, 400), screen.at(160f, 100f, 320, 200))
    assertEquals(BrowserScreen.Point(0, 0), screen.at(0f, 0f, 320, 200))
    assertEquals(BrowserScreen.Point(1280, 800), screen.at(320f, 200f, 320, 200))
    // Each way by its own measure, should the picture be drawn out of shape.
    assertEquals(BrowserScreen.Point(320, 600), screen.at(100f, 300f, 400, 400))
    assertEquals(BrowserScreen.Point(6, 2), screen.at(1.5f, 0.6f, 320, 200))
  }

  @Test
  fun aFingerOffTheEdgeStaysOnIt() {
    assertEquals(BrowserScreen.Point(0, 800), screen.at(-30f, 260f, 320, 200))
    assertEquals(BrowserScreen.Point(1280, 0), screen.at(400f, -5f, 320, 200))
  }

  @Test
  fun nothingIsPressedBeforeThereIsAPicture() {
    assertNull(BrowserScreen().at(10f, 10f, 320, 200))
    assertNull(screen.at(10f, 10f, 0, 0))
  }

  @Test
  fun thePageIsNamedByItsTitleOrItsAddress() {
    assertEquals("Sign in", screen.heading)
    assertEquals("https://portal.example.com/login", screen.copy(title = "  ").heading)
    assertEquals("", BrowserScreen().heading)
  }

  @Test
  fun eachEventIsAsJarvisTakesIt() {
    val at = BrowserScreen.Point(12, 34)
    assertEquals("""{"kind":"mouse","type":"down","x":12,"y":34}""", BrowserInput.mouse("down", at).toString())
    assertEquals("""{"kind":"wheel","x":640,"y":400,"dy":-500}""", BrowserInput.wheel(screen.middle, -BrowserInput.WHEEL).toString())
    assertEquals("""{"kind":"text","text":"a \"b\""}""", BrowserInput.text("a \"b\"").toString())
    assertEquals("""{"kind":"key","key":"Backspace"}""", BrowserInput.key(BrowserInput.BACKSPACE).toString())
    assertEquals("""{"events":[{"kind":"back"},{"kind":"key","key":"Enter"}]}""", BrowserInput.body(listOf(BrowserInput.back, BrowserInput.key(BrowserInput.ENTER))).toString())
  }

  private fun List<JsonObject>.types() = map { it["type"].toString().trim('"') }

  @Test
  fun aTapIsDownThenUp() = runTest {
    val sent = ArrayList<List<JsonObject>>()
    val hand = BrowserHand(this, { sent += it })
    hand.down(BrowserScreen.Point(5, 6))
    assertTrue(hand.held)
    hand.up(BrowserScreen.Point(5, 6))
    assertFalse(hand.held)
    runCurrent()
    assertEquals(listOf(listOf("down"), listOf("up")), sent.map { it.types() })
  }

  @Test
  fun aDragsMovesGoAsItHappensAndNothingOvertakes() = runTest {
    val sent = ArrayList<List<JsonObject>>()
    // The press is slow to arrive: what follows waits for it.
    val arrived = CompletableDeferred<Unit>()
    val hand = BrowserHand(this, { if (sent.isEmpty()) arrived.await(); sent += it })
    hand.down(BrowserScreen.Point(0, 0))
    hand.move(BrowserScreen.Point(1, 0))
    hand.move(BrowserScreen.Point(2, 0))
    hand.flush()
    hand.move(BrowserScreen.Point(3, 0))
    hand.up(BrowserScreen.Point(3, 0))
    runCurrent()
    assertTrue(sent.isEmpty())
    arrived.complete(Unit)
    hand.settled()
    assertEquals(listOf(listOf("down"), listOf("move", "move"), listOf("move", "up")), sent.map { it.types() })
    // Nothing held, nothing gathered: a move with no finger down goes nowhere.
    hand.move(BrowserScreen.Point(9, 9))
    hand.flush()
    hand.up(null)
    runCurrent()
    assertEquals(3, sent.size)
  }

  @Test
  fun aTouchTakenAwayLetsGoInACorner() = runTest {
    val sent = ArrayList<List<JsonObject>>()
    val hand = BrowserHand(this, { sent += it })
    hand.down(BrowserScreen.Point(50, 50))
    hand.move(BrowserScreen.Point(60, 50))
    hand.cancel()
    hand.cancel()
    runCurrent()
    assertEquals(listOf("""{"kind":"mouse","type":"move","x":0,"y":0}""", """{"kind":"mouse","type":"up","x":0,"y":0}"""), sent.last().map { it.toString() })
    assertEquals(2, sent.size)
  }

  @Test
  fun moreThanJarvisTakesAtOnceGoesAsSeveral() = runTest {
    val sent = ArrayList<List<JsonObject>>()
    val hand = BrowserHand(this, { sent += it })
    hand.down(BrowserScreen.Point(0, 0))
    repeat(200) { hand.move(BrowserScreen.Point(it, 0)) }
    hand.up(BrowserScreen.Point(200, 0))
    runCurrent()
    assertEquals(listOf(1, 80, 80, 41), sent.map { it.size })
  }

  @Test
  fun whatJarvisSaysOfAFailureIsSaidAndClearedByTheNextThatGoes() = runTest {
    val said = ArrayList<String>()
    var fail = true
    val hand = BrowserHand(this, { if (fail) throw java.io.IOException("The browser is not answering") }, { said += it })
    hand.send(listOf(BrowserInput.back))
    runCurrent()
    fail = false
    hand.send(listOf(BrowserInput.back))
    runCurrent()
    assertEquals(listOf("The browser is not answering", ""), said)
  }
}
