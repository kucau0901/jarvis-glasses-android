package app.jarvis.glasses.alerts

import app.jarvis.glasses.jarvis.Alert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessagesTest {
  private fun alert(title: String, source: String = "chat", convo: String? = "family") =
      Alert("a1", title, "Dinner at 8?", speak = true, urgent = false, source = source, convo = convo)

  @Test
  fun aFamilyRoomMessageIsTheSendersInTheRoom() {
    assertEquals(FromFamily("family", "Aisha", room = true), FromFamily.of(alert("Aisha, to the family")))
  }

  @Test
  fun aDirectMessageIsTheSendersAlone() {
    assertEquals(FromFamily("dm:u_1|u_2", "Adam", room = false), FromFamily.of(alert("Adam", convo = "dm:u_1|u_2")))
  }

  @Test
  fun jarvisOwnAlertsAreNotFamilyMessages() {
    assertNull(FromFamily.of(alert("Done: the report", source = "job", convo = null)))
    assertNull(FromFamily.of(alert("Aisha", source = "routine")))
    assertNull(FromFamily.of(alert("Aisha", convo = "")))
  }

  @Test
  fun aSenderWithoutANameStillShows() {
    assertEquals("Someone", FromFamily.of(alert(", to the family"))!!.sender)
  }

  @Test
  fun eachConversationKeepsItsNewestMessages() {
    val m = Messages(keep = 3)
    for (i in 1..4) m.add("family", Messages.Line("Aisha", "m$i", i.toLong()))
    m.add("dm:x", Messages.Line("Adam", "hi", 9))
    assertEquals(listOf("m2", "m3", "m4"), m.of("family").map { it.text })
    assertEquals(listOf("hi"), m.of("dm:x").map { it.text })
    m.clear("family")
    assertEquals(emptyList<Messages.Line>(), m.of("family"))
    assertEquals(1, m.of("dm:x").size)
  }
}
