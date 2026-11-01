package app.jarvis.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandsTest {
  private val netflix = TvApp("com.netflix.ninja", "Netflix")
  private val youtube = TvApp("com.google.android.youtube.tv", "YouTube")
  private val kids = TvApp("com.google.android.youtube.tvkids", "YouTube Kids")
  private val hotstar = TvApp("in.startv.hotstar.dplus.tv", "Disney+ Hotstar")
  private val music = TvApp("com.google.android.music", "Play Music")
  private val apps = listOf(netflix, kids, youtube, hotstar, music)

  @Test
  fun opensByVerbInEnglishAndMalay() {
    assertEquals(netflix, appAsked("Open Netflix", apps))
    assertEquals(youtube, appAsked("buka YouTube", apps))
    assertEquals(netflix, appAsked("launch the Netflix app please", apps))
    assertEquals(kids, appAsked("go to YouTube Kids.", apps))
    assertEquals(youtube, appAsked("tolong bukak aplikasi you tube", apps))
  }

  @Test
  fun aBareNameOpensOnlyWhenItIsTheWholeName() {
    assertEquals(netflix, appAsked("Netflix", apps))
    assertNull(appAsked("music", apps))
  }

  @Test
  fun openFindsTheClosestShortName() {
    assertEquals(youtube, appAsked("open you", apps))
    assertEquals(hotstar, appAsked("open hotstar", apps))
    assertEquals(hotstar, appAsked("open Disney plus", apps))
    assertEquals(music, appAsked("open music", apps))
  }

  @Test
  fun anythingElseIsForJarvis() {
    assertNull(appAsked("what's the weather tomorrow", apps))
    assertNull(appAsked("open the gate", apps))
    assertNull(appAsked("open", apps))
    assertNull(appAsked("", apps))
  }
}
