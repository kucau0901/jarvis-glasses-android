package app.jarvis.glasses.glasses

import app.jarvis.glasses.glasses.GlassesState.Link
import com.meta.wearable.dat.core.types.ChargingState
import com.meta.wearable.dat.core.types.Device
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceType
import com.meta.wearable.dat.core.types.DonState
import com.meta.wearable.dat.core.types.HingeState
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.core.types.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesTest {
  private fun meta(
      link: LinkState = LinkState.CONNECTED,
      battery: Int = 78,
      charging: ChargingState = ChargingState.NOT_CHARGING,
      don: DonState = DonState.DONNED,
      hinge: HingeState = HingeState.OPEN,
      thermal: ThermalLevel = ThermalLevel.NONE,
      compatibility: DeviceCompatibility = DeviceCompatibility.COMPATIBLE,
  ) = Device("Test Glasses 01", link, DeviceType.RAYBAN_META, "1.0", compatibility, thermal, battery, charging, don, hinge)

  @Test fun metasGlassesAreDescribedInAnyMakersWords() {
    val g = GlassesLink.stateOf(meta())
    assertEquals("Test Glasses 01", g.name)
    assertTrue(g.model.isNotEmpty())
    assertEquals(Link.CONNECTED, g.link)
    assertEquals(78, g.battery)
    assertEquals(false, g.charging)
    assertEquals(true, g.worn)
    assertEquals(false, g.folded)
    assertFalse(g.hot)
    assertNull(g.needsUpdate)
  }

  @Test fun whatTheToolkitDoesntKnowIsLeftOut() {
    val g = GlassesLink.stateOf(meta(battery = -1, charging = ChargingState.UNKNOWN, don = DonState.UNKNOWN, hinge = HingeState.UNKNOWN))
    assertNull(g.battery)
    assertNull(g.charging)
    assertNull(g.worn)
    assertNull(g.folded)
  }

  @Test fun linkHeatAndUpdatesAreSaid() {
    assertEquals(Link.AWAY, GlassesLink.stateOf(meta(link = LinkState.DISCONNECTED)).link)
    assertEquals(Link.CONNECTING, GlassesLink.stateOf(meta(link = LinkState.CONNECTING)).link)
    assertTrue(GlassesLink.stateOf(meta(thermal = ThermalLevel.MODERATE)).hot)
    assertFalse(GlassesLink.stateOf(meta(thermal = ThermalLevel.LIGHT)).hot)
    assertTrue(GlassesLink.stateOf(meta(compatibility = DeviceCompatibility.DEVICE_UPDATE_REQUIRED)).needsUpdate != null)
  }

  @Test fun theConnectedPairIsShownTheWornOneFirst() {
    val away = GlassesState("A", link = Link.AWAY)
    val on = GlassesState("B", link = Link.CONNECTED, worn = true)
    val off = GlassesState("C", link = Link.CONNECTED, worn = false)
    assertEquals("B", shownGlasses(listOf(away, off, on), null)!!.name)
    assertEquals("A", shownGlasses(listOf(away), null)!!.name)
    assertNull(shownGlasses(emptyList(), null))
  }

  @Test fun aHeadsetNoToolkitKnowsStillCounts() {
    assertEquals(GlassesState("Some Glasses", link = Link.CONNECTED), shownGlasses(emptyList(), "Some Glasses"))
    val stale = GlassesState("A", model = "Ray-Ban Meta", link = Link.AWAY, battery = 50)
    assertEquals(Link.CONNECTED, shownGlasses(listOf(stale), "A")!!.link)
  }

  @Test fun glassesTakenOffOrFoldedAreSaidSo() {
    fun pair(link: Link = Link.CONNECTED, worn: Boolean? = null, folded: Boolean? = null) = GlassesState("RB Meta 0000", link = link, worn = worn, folded = folded)
    assertFalse("worn", takenOff(listOf(pair(worn = true, folded = false))))
    assertTrue("off the face", takenOff(listOf(pair(worn = false))))
    assertTrue("folded", takenOff(listOf(pair(folded = true))))
    // A maker that says neither: never taken for off.
    assertFalse("not said", takenOff(listOf(pair())))
    assertFalse("none connected", takenOff(listOf(pair(link = Link.AWAY, worn = false))))
    assertFalse("another pair is being worn", takenOff(listOf(pair(folded = true), pair(worn = true))))
  }
}
