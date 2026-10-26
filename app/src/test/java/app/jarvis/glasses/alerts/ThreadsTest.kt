package app.jarvis.glasses.alerts

import app.jarvis.glasses.jarvis.Alert
import app.jarvis.glasses.jarvis.Turn
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadsTest {
  private fun alert(id: String) = Alert(id, "Reminder", "Call the garage at 5", speak = true, urgent = false)

  @Test
  fun aReplyFollowsItsAlert() {
    val t = Threads()
    t.open(alert("a1"))
    t.add("a1", Turn.user("remind me in an hour"), "")
    assertEquals(
        listOf(Turn.jarvis("Call the garage at 5"), Turn.user("remind me in an hour"), Turn.jarvis("Done, at 6.")),
        t.add("a1", Turn.jarvis("Done, at 6."), ""),
    )
  }

  @Test
  fun aForgottenAlertStartsFromItsWords() {
    val t = Threads(keep = 1)
    t.open(alert("a1"))
    t.open(alert("a2"))
    assertEquals(listOf(Turn.jarvis("Call the garage at 5"), Turn.user("ok")), t.add("a1", Turn.user("ok"), "Call the garage at 5"))
  }
}
