package app.jarvis.glasses.watch

import app.jarvis.glasses.hub.AlarmCode
import app.jarvis.glasses.hub.ButtonInput
import app.jarvis.glasses.hub.Climate
import app.jarvis.glasses.hub.Figure
import app.jarvis.glasses.hub.Level
import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelMore
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.hub.PanelsReply
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.Reading
import app.jarvis.link.Approval
import app.jarvis.link.Button
import app.jarvis.link.Dial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WristTest {
  private fun row(key: String, kind: String = "light", label: String = key, value: String = "", actions: List<PanelAction> = emptyList(), more: PanelMore? = null, read: Boolean = false, note: String? = null, tiles: List<Figure> = emptyList()) =
      PanelRow(key = key, kind = kind, label = label, value = value, actions = actions, more = more, read = read, note = note, tiles = tiles)

  @Test
  fun homesThingsComeInOrderWithoutToDoLists() {
    val panels =
        PanelsReply(
            listOf(
                Panel("home", "Home", listOf(row("light.hall", label = "Hall", value = "On"), row("todo", kind = "note"))),
                Panel("car", "Car", listOf(row("car", kind = "car", label = "Car", tiles = listOf(Figure("Battery", "80%"), Figure("Range", "300 km"))))),
            ),
        )
    val things = HubWrist.thingsOf(panels, emptyMap())
    assertEquals(listOf("home/light.hall/Hall", "car/car/Car"), things.map { it.id })
    assertEquals("On", things[0].status)
    assertEquals("Battery 80% · Range 300 km", things[1].status)
  }

  @Test
  fun tilesSharingAKeyAreThingsOfTheirOwnAndNoneTwice() {
    val family = Panel("p_1", "Family", listOf(row("family", kind = "person", label = "Aisha", value = "Home"), row("family", kind = "person", label = "Adam", value = "Away"), row("family", kind = "person", label = "Adam", value = "Away")))
    assertEquals(listOf("p_1/family/Aisha", "p_1/family/Adam"), HubWrist.thingsOf(PanelsReply(listOf(family)), emptyMap()).map { it.id })
  }

  @Test
  fun anAirconsFanSpeedsAreKeptBesideAllItsModes() {
    val modes = listOf("off", "heat", "cool", "heat_cool", "dry", "fan_only")
    val aircond = row("climate.bed", actions = listOf(PanelAction("toggle", "Toggle")), more = PanelMore(climate = Climate(target = 24.0, min = 16.0, max = 30.0, step = 1.0, modes = modes, mode = "cool", fans = listOf("auto", "low", "medium", "high"))))
    val actions = HubWrist.thingOf(Panel("home", "Home"), aircond, null).buttons.map { it.action }
    assertEquals(listOf("toggle") + modes.map { "mode:$it" } + listOf("fan:auto", "fan:low", "fan:medium", "fan:high"), actions)
  }

  @Test
  fun aBigRoomSaysHowManyOfItsThingsStayOnThePhone() {
    val room = row("room.big", more = PanelMore(members = (1..15).map { row("light.$it", label = "Light $it") }))
    val t = HubWrist.thingOf(Panel("home", "Home"), room, null)
    assertEquals(12, t.members.size)
    assertEquals(3, t.moreMembers)
  }

  @Test
  fun homeIsCutToFitOneMessageToTheWatchLastThingsFirst() {
    val things = (1..50).map { app.jarvis.link.Thing("home", "light.$it", "Light number $it", status = "x".repeat(200)) }
    val fitted = HubWrist.fitted(things, maxBytes = 5_000)
    assertEquals(things.take(fitted.size), fitted)
    assert(fitted.size in 1 until 50)
    assert(app.jarvis.link.WatchLink.encodeThings(fitted).length <= 5_000)
  }

  @Test
  fun aRoomKeepsItsThingsEachWithItsOwnButtons() {
    val room =
        row(
            "room.master",
            kind = "room",
            label = "Master Bed",
            value = "2 on",
            more =
                PanelMore(
                    members =
                        listOf(
                            row("light.bed", label = "Bed light", value = "On", actions = listOf(PanelAction("turn_off", "Off"), PanelAction("dim", "Dim to", ButtonInput("slider")))).copy(active = true),
                            row("light.desk", label = "Desk", value = "Off", actions = listOf(PanelAction("turn_on", "On"))),
                        ),
                ),
        )
    val t = HubWrist.thingOf(Panel("home", "Home"), room, null)
    assertEquals("2 on", t.status)
    assertEquals(
        listOf(
            app.jarvis.link.Member("light.bed", "Bed light", "On", active = true, buttons = listOf(Button("turn_off", "Off"))),
            app.jarvis.link.Member("light.desk", "Desk", "Off", buttons = listOf(Button("turn_on", "On"))),
        ),
        t.members,
    )
  }

  @Test
  fun onlyButtonsThatNeedNothingMoreAreKept() {
    val r =
        row(
            "alarm",
            actions = listOf(PanelAction("arm_away", "Arm"), PanelAction("disarm", "Disarm"), PanelAction("set", "Set to", ButtonInput("slider"))),
            more = PanelMore(code = AlarmCode(forActions = listOf("disarm")), actions = listOf(PanelAction("arm_away", "Arm"), PanelAction("panic", "Panic"))),
        )
    assertEquals(listOf(Button("arm_away", "Arm"), Button("panic", "Panic")), HubWrist.thingOf(Panel("home", "Home"), r, null).buttons)
  }

  @Test
  fun aLevelOrASetPointBecomesTheBezelsDial() {
    val lamp = row("light.desk", more = PanelMore(level = Level("Brightness", 40.0, 0.0, 100.0, 1.0, "%")))
    assertEquals(Dial("Brightness", 40.0, 0.0, 100.0, 1.0, "%", "level:"), HubWrist.thingOf(Panel("home", "Home"), lamp, null).dial)
    val aircond = row("climate.bed", more = PanelMore(climate = Climate(now = 27.0, target = 24.0, min = 16.0, max = 30.0, step = 0.5, modes = listOf("cool", "fan_only"), mode = "cool")))
    val t = HubWrist.thingOf(Panel("home", "Home"), aircond, null)
    assertEquals(Dial("Temperature", 24.0, 16.0, 30.0, 0.5, "°", "temp:"), t.dial)
    assertEquals(listOf(Button("mode:cool", "Cool"), Button("mode:fan_only", "Fan only")), t.buttons)
    val withFans = HubWrist.thingOf(Panel("home", "Home"), aircond.copy(more = aircond.more!!.copy(climate = aircond.more!!.climate!!.copy(fans = listOf("auto", "high")))), null)
    assertEquals(listOf("mode:cool", "mode:fan_only", "fan:auto", "fan:high"), withFans.buttons.map { it.action })
    assertEquals("Fan auto", withFans.buttons[2].label)
    // Off, it has no set point to turn.
    assertNull(HubWrist.thingOf(Panel("home", "Home"), aircond.copy(more = aircond.more!!.copy(climate = aircond.more!!.climate!!.copy(mode = "off"))), null).dial)
  }

  @Test
  fun aWarningComesFirstThenAValueThenAReading() {
    val p = Panel("home", "Home")
    assertEquals("Offline", HubWrist.statusOf(row("x", value = "On", note = "Offline"), null))
    assertEquals("22°", HubWrist.statusOf(row("x", read = true), Reading(text = "22°")))
    assertEquals("PM2.5 12", HubWrist.statusOf(row("x", read = true), Reading(figures = listOf(Figure("PM2.5", "12")))))
    assertEquals("", HubWrist.statusOf(row("x", read = true), null))
    // Only one with nothing of its own to show is read.
    assertEquals(listOf("home/x"), HubWrist.readsOf(PanelsReply(listOf(p.copy(rows = listOf(row("x", read = true), row("y", value = "On", read = true)))))).map { (pp, r) -> "${pp.id}/${r.key}" })
  }

  @Test
  fun anApprovalKeepsItsWordForDoingIt() {
    assertEquals(Approval("p1", "Send the email", "You asked", "Send", "reversible"), HubWrist.approvalOf(Proposal("p1", "Send the email", "You asked", verb = "Send")))
    assertEquals("Approve", HubWrist.approvalOf(Proposal("p1", "x")).verb)
  }
}
