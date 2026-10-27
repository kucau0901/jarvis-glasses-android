package app.jarvis.glasses.jarvis

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventsTest {
  private fun parse(lines: String): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    val p = SseParser { out += it }
    lines.split("\n").forEach(p::line)
    p.flush()
    return out
  }

  @Test
  fun framesAreSplitOnBlankLines() {
    val got = parse("data: {\"type\":\"transcript\",\"text\":\"hi\"}\n\ndata: {\"type\":\"result\",\"text\":\"Hello\",\"ok\":true}\n\n")
    assertEquals(2, got.size)
    assertEquals(VoiceEvent.Heard("hi"), VoiceEvent.from(got[0]))
    assertEquals(VoiceEvent.Answer("Hello", true), VoiceEvent.from(got[1]))
  }

  @Test
  fun aLastFrameWithoutItsBlankLineStillCounts() {
    assertEquals(1, parse("data: {\"type\":\"progress\",\"text\":\"Looking\"}").size)
  }

  @Test
  fun crLfAndNonJsonFramesAreTolerated() {
    val got = parse("data: not json\r\n\r\n: keep-alive\r\ndata: {\"type\":\"progress\",\"text\":\"x\"}\r\n\r\n")
    assertEquals(listOf(VoiceEvent.Progress("x")), got.map { VoiceEvent.from(it) })
  }

  @Test
  fun audioEventsCarryTheirClip() {
    val o = parse("data: {\"type\":\"audio\",\"seq\":1,\"last\":true,\"ms\":900,\"audio\":{\"format\":\"mp3\",\"mime\":\"audio/mpeg\",\"by\":\"openai\",\"data\":\"AAEC\"}}\n\n")
    assertEquals(VoiceEvent.Audio(1, true, "audio/mpeg", "AAEC"), VoiceEvent.from(o.single()))
  }

  @Test
  fun anErrorIsASpeakableFailedAnswer() {
    val e = VoiceEvent.from(parse("data: {\"type\":\"error\",\"text\":\"No luck.\"}").single())
    assertEquals(VoiceEvent.Answer("No luck.", false), e)
  }

  @Test
  fun unknownEventsAreIgnored() {
    assertNull(VoiceEvent.from(parse("data: {\"type\":\"display\",\"kind\":\"map\"}").single()))
  }

  @Test
  fun alertsComeFromAlertMessagesOnly() {
    val msg = parse("data: {\"type\":\"alert\",\"alert\":{\"id\":\"k3v\",\"at\":1,\"title\":\"Office\",\"text\":\"Leave in ten minutes.\",\"speak\":true,\"urgent\":false,\"source\":\"routine\"}}").single()
    assertEquals(Alert("k3v", "Office", "Leave in ten minutes.", speak = true, urgent = false, source = "routine"), Alert.fromMessage(msg))
    assertNull(Alert.fromMessage(parse("data: {\"type\":\"hello\",\"label\":\"glasses\"}").single()))
    assertNull(Alert.fromMessage(parse("data: {\"type\":\"alert\",\"alert\":{\"id\":\"x\",\"text\":\"  \"}}").single()))
  }

  @Test
  fun anAlertSaysWhetherToTalkAboutIt() {
    fun alert(json: String) = Alert.fromMessage(parse("data: {\"type\":\"alert\",\"alert\":$json}").single())!!
    // Jarvis's word: the person's choices, and not a repeat.
    val quiet = alert("""{"id":"a1","title":"Sara, to the family","text":"Dinner at eight","speak":true,"talk":false,"urgent":false,"source":"chat","convo":"family"}""")
    assertEquals(false, quiet.talk)
    assertEquals("chat", quiet.source)
    assertEquals("family", quiet.convo)
    assertEquals("Sara, to the family: Dinner at eight", quiet.said)
    // An older Jarvis sends no talk: speak decides, as before.
    assertEquals(true, alert("""{"id":"a2","text":"Leave now","speak":true}""").talk)
    assertEquals(false, alert("""{"id":"a3","text":"A note","speak":false}""").talk)
    assertEquals("Leave now", alert("""{"id":"a2","title":"Jarvis","text":"Leave now","speak":true}""").said)
  }

  @Test
  fun socketMessagesHaveTheDocumentedShape() {
    assertEquals("""{"type":"ack","id":"k3v","visible":true}""", AlertSocket.ack("k3v"))
    assertEquals("""{"type":"presence","visible":false}""", AlertSocket.presence(false))
  }
}
