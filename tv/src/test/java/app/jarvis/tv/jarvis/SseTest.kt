package app.jarvis.tv.jarvis

import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseTest {
  private fun parse(vararg lines: String): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    val p = SseParser(out::add)
    lines.forEach(p::line)
    p.flush()
    return out
  }

  @Test
  fun framesAreSplitByBlankLinesAndCommentsAreIgnored() {
    val frames = parse(": ping", "data: {\"type\":\"progress\",\"text\":\"a\"}", "", ": ping", "", "data: {\"type\":\"result\",\"text\":\"b\"}\r", "\r")
    assertEquals(listOf("progress", "result"), frames.map { it["type"]!!.jsonPrimitive.content })
  }

  @Test
  fun aLastFrameWithoutItsBlankLineStillCounts() {
    assertEquals(1, parse("data: {\"type\":\"result\",\"text\":\"x\"}").size)
  }

  @Test
  fun dataLinesOfOneFrameAreJoinedAndJunkIsSkipped() {
    val frames = parse("data: {\"type\":", "data: \"transcript\",\"text\":\"hi\"}", "", "data: not json", "", "data: [1,2]", "")
    assertEquals(1, frames.size)
    assertEquals("hi", frames[0]["text"]!!.jsonPrimitive.content)
  }

  @Test
  fun eventsAreReadFromFrames() {
    val (heard, tool, display, result, audio) = parse(
        "data: {\"type\":\"transcript\",\"text\":\"lights off\"}", "",
        "data: {\"type\":\"tool\",\"name\":\"home\",\"id\":\"t1\",\"phase\":\"start\",\"args\":{}}", "",
        "data: {\"type\":\"display\",\"kind\":\"image\",\"image\":\"img9\",\"label\":\"A cat\"}", "",
        "data: {\"type\":\"result\",\"text\":\"Done.\",\"tools\":[\"home\"]}", "",
        "data: {\"type\":\"audio\",\"seq\":1,\"last\":true,\"ms\":900,\"audioError\":\"tts failed\"}", "",
    ).map { VoiceEvent.from(it) }
    assertEquals(VoiceEvent.Heard("lights off"), heard)
    assertEquals(VoiceEvent.Tool("home", done = false), tool)
    assertEquals(VoiceEvent.Display("image", "A cat", id = "img9"), display)
    assertEquals(VoiceEvent.Result("Done.", error = false), result)
    assertEquals(VoiceEvent.Audio(1, true, null), audio)
  }

  @Test
  fun displaysTheTvCannotDrawAndUnusedEventsAreDropped() {
    val events = parse(
        "data: {\"type\":\"display\",\"kind\":\"map\",\"label\":\"Nowhere\"}", "",
        "data: {\"type\":\"display\",\"kind\":\"hologram\"}", "",
        "data: {\"type\":\"used\",\"tokens\":12}", "",
        "data: {\"type\":\"display\",\"kind\":\"close\"}", "",
    ).map { VoiceEvent.from(it) }
    assertNull(events[0])
    assertNull(events[1])
    assertNull(events[2])
    assertTrue(events[3] is VoiceEvent.Display)
  }

  @Test
  fun anErrorIsAnAnswerToo() {
    val e = VoiceEvent.from(parse("data: {\"type\":\"error\",\"text\":\"Home Assistant is offline.\"}").single())
    assertEquals(VoiceEvent.Result("Home Assistant is offline.", error = true), e)
  }
}
