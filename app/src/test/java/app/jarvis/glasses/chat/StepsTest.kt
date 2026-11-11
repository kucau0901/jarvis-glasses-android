package app.jarvis.glasses.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsTest {
  @Test
  fun toolsAreSaidInWords() {
    assertEquals("Checking your mail", Steps.doing("mail_check"))
    assertEquals("Checking", Steps.doing("home-assistant__ha_get_state"))
    assertEquals("Working", Steps.doing("something_new"))
    // Work in the browser is up to twenty of these: each says what it is, not "Working".
    assertEquals(
        listOf("Opening the page", "Reading the page", "Pressing", "Filling in", "Closing the tab", "Signing in", "Showing you the page"),
        listOf("browser_open", "browser_read", "browser_press", "browser_fill", "browser_close_tab", "browser_sign_in", "browser_show").map(Steps::doing),
    )
  }

  @Test
  fun aNoteLosesTheVoicesAdvice() = assertEquals("Home is still thinking", Steps.said("home is still thinking; say the user can ask you to drop it…"))

  @Test
  fun pillsRunThenTick() {
    val w = Work()
    assertTrue(w.thinks)
    w.tool("mail_check", "c1", "start")
    assertFalse(w.thinks)
    w.note("reading 12 new mails", "c1")
    assertEquals(listOf(Step("c1", "Reading 12 new mails", true)), w.steps)
    w.tool("mail_check", "c1", "done")
    assertTrue(w.thinks)
    assertFalse(w.steps.single().running)
  }

  @Test
  fun aNoteWithNoCallRunningRewordsThinking() {
    val w = Work()
    w.note("still looking", null)
    assertEquals("Still looking", w.thinking)
  }
}
