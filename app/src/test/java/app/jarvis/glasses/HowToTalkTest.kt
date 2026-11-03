package app.jarvis.glasses

import app.jarvis.glasses.glasses.GlassesState
import app.jarvis.glasses.glasses.GlassesState.Link
import org.junit.Assert.assertEquals
import org.junit.Test

class HowToTalkTest {
  private val g2 = GlassesState("G2", model = "Even Realities G2", link = Link.CONNECTED, display = true)
  private val rayBan = GlassesState("RB Meta 012H", model = "Ray-Ban Meta", link = Link.CONNECTED)

  @Test
  fun aG2IsOpenedThroughEvenAi() {
    assertEquals("Say \"Hey Even, open Jarvis\" on the G2, or tap Talk", JarvisService.howToTalk(listOf(g2, rayBan.copy(link = Link.AWAY)), metaHeard = false, g2 = true))
  }

  @Test
  fun rayBansAreOpenedThroughMetaAi() {
    assertEquals("Say \"Hey Meta, open Jarvis\", or tap Talk", JarvisService.howToTalk(listOf(rayBan), metaHeard = false, g2 = true))
    // Heard as a headset before Meta's toolkit says so.
    assertEquals("Say \"Hey Meta, open Jarvis\", or tap Talk", JarvisService.howToTalk(emptyList(), metaHeard = true, g2 = true))
  }

  @Test
  fun bothSaySo() {
    assertEquals("Say \"Hey Even, open Jarvis\" on the G2, or \"Hey Meta, open Jarvis\"", JarvisService.howToTalk(listOf(g2, rayBan), metaHeard = true, g2 = true))
  }

  @Test
  fun aG2WithItsAlertsSwitchedOffOrAwayIsLeftOut() {
    assertEquals("Tap Talk to ask Jarvis", JarvisService.howToTalk(listOf(g2), metaHeard = false, g2 = false))
    assertEquals("Tap Talk to ask Jarvis", JarvisService.howToTalk(listOf(g2.copy(link = Link.AWAY)), metaHeard = false, g2 = true))
  }
}
