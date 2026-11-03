package app.jarvis.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchLinkTest {
  @Test
  fun whatTheWatchSendsIsReadBackByItsPath() {
    val ask = Ask("q1", "what's on today", "w_abc", speak = false, chars = 200)
    assertEquals(ask, WatchLink.fromWatch(WatchLink.ASK, WatchLink.encode(ask)))
    assertEquals(Hello("h1"), WatchLink.fromWatch(WatchLink.HELLO, WatchLink.encode(Hello("h1"))))
    assertEquals(Stop("q1"), WatchLink.fromWatch(WatchLink.STOP, WatchLink.encode(Stop("q1"))))
  }

  @Test
  fun eachMessageGoesByItsOwnPath() {
    val sent =
        listOf(
            Hello("h1"),
            Ask("q1", "hi", "w_1"),
            Stop("q1"),
            Fetch("f1", Fetch.HOME),
            Act("a1", "home", "light.hall", "level:40"),
            Act("a2", "home", "room.master", "turn_on", member = "light.bed"),
            Approve("p1", "prop_1", yes = true),
        )
    for (m in sent) {
      val (path, data) = WatchLink.message(m)
      assertEquals(m, WatchLink.fromWatch(path, data))
    }
  }

  @Test
  fun thingsKeptOnTheWatchReadBack() {
    val things =
        listOf(
            Thing("home", "light.hall", "Hall", "bulb", "On", active = true, buttons = listOf(Button("toggle", "Turn off")), dial = Dial("Brightness", 40.0, 0.0, 100.0, 1.0, "%", "level:")),
            Thing("car", "car", "Car", status = "80%"),
            Thing("home", "room.master", "Master Bed", status = "2 on", members = listOf(Member("light.bed", "Bed light", "On", active = true, buttons = listOf(Button("turn_off", "Off"))))),
        )
    assertEquals(things, WatchLink.decodeThings(WatchLink.encodeThings(things)))
    assertEquals("home/light.hall/Hall", things[0].id)
    assertNull(WatchLink.decodeThings("not json"))
  }

  @Test
  fun aDialStepsWithinItsRangeAndSaysItsNumberAsThePhoneDoes() {
    val level = Dial("Brightness", 40.0, 0.0, 100.0, 5.0, "%", "level:")
    assertEquals(45.0, level.stepped(40.0, 1), 0.0)
    assertEquals(100.0, level.stepped(98.0, 3), 0.0)
    assertEquals(0.0, level.stepped(3.0, -1), 0.0)
    assertEquals("level:45", level.actionFor(45.0))
    val temp = Dial("Temperature", 24.0, 16.0, 30.0, 0.5, "°", "temp:")
    assertEquals(24.5, temp.stepped(24.0, 1), 0.0)
    assertEquals("temp:24.5", temp.actionFor(24.5))
    assertEquals("temp:24", temp.actionFor(24.0))
  }

  @Test
  fun everyKindOfReplyComesBackAsItWent() {
    val replies =
        listOf(
            Reply.Ready("h1", signedIn = true, name = "Yusri"),
            Reply.Progress("q1", "Looking at the calendar"),
            Reply.Answer("q1", "Two meetings.", ok = true),
            Reply.Answer("q1", "", notForMe = true),
            Reply.Audio("q1", 0, last = false, mime = "audio/mpeg", base64 = "SUQz"),
            Reply.Home("f1", listOf(Thing("home", "k", "Hall"))),
            Reply.Approvals("f2", listOf(Approval("p1", "Send the email to Sam", "You asked", "Send"))),
            Reply.Glance("f3", approvals = 2, jobs = 1),
            Reply.Acted("a1", "Done"),
            Reply.Done("q1"),
            Reply.Failed("q1", "Sign in to Jarvis on the phone"),
        )
    for (r in replies) assertEquals(r, WatchLink.reply(WatchLink.REPLY, WatchLink.encode(r)))
  }

  @Test
  fun aReplyIsTaggedWithItsKind() {
    assertEquals("""{"type":"progress","id":"q1","text":"Working"}""", WatchLink.encode(Reply.Progress("q1", "Working")).decodeToString())
  }

  @Test
  fun anOlderAskWithoutTheChoicesSpeaks() {
    val ask = WatchLink.fromWatch(WatchLink.ASK, """{"id":"q1","text":"hi","origin":"w_1"}""".toByteArray()) as Ask
    assertEquals(true, ask.speak)
    assertEquals(300, ask.chars)
  }

  @Test
  fun whatThisVersionCantReadIsLeftAlone() {
    assertNull(WatchLink.fromWatch("/jarvis/other", "{}".toByteArray()))
    assertNull(WatchLink.fromWatch(WatchLink.ASK, "not json".toByteArray()))
    assertNull(WatchLink.fromWatch(WatchLink.ASK, """{"id":"q1"}""".toByteArray()))
    assertNull(WatchLink.reply(WatchLink.REPLY, """{"type":"later","id":"q1"}""".toByteArray()))
    assertNull(WatchLink.reply(WatchLink.ASK, WatchLink.encode(Reply.Progress("q1", "x"))))
  }

  @Test
  fun aNewerReplysExtraFieldsAreIgnored() {
    assertEquals(Reply.Progress("q1", "x"), WatchLink.reply(WatchLink.REPLY, """{"type":"progress","id":"q1","text":"x","more":1}""".toByteArray()))
  }
}
