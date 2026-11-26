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

  @Test
  fun aCardToReadFirstIsShownAsOneForThePhone() {
    val publish = Proposal("p1", "Publish the site “bake-sale” (12 files)", body = "index.html\nstyle.css", verb = "Publish")
    assertEquals(Approval("p1", publish.says, "", HubWrist.ON_PHONE, "reversible", phone = true), HubWrist.approvalOf(publish))
    val lock = Proposal("p2", "Lock the car", verb = "Lock")
    assertEquals(Approval("p2", "Lock the car", "", "Lock", "reversible", phone = false), HubWrist.approvalOf(lock))
  }

  @Test
  fun anOldWatchsYesToACardToReadFirstIsRefusedAndItsNoTaken() = kotlinx.coroutines.runBlocking {
    val server = okhttp3.mockwebserver.MockWebServer()
    server.start()
    try {
      val hub = app.jarvis.glasses.hub.Hub(app.jarvis.glasses.jarvis.JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
      val open = """{"open":[{"id":"p1","says":"Open a pull request in owner/name (3 files): Footer","risk":"irreversible","state":"pending"}],"recent":[],"later":[]}"""
      server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(open))
      val refused = runCatching { HubWrist(hub).approve("p1", yes = true) }.exceptionOrNull()
      assertEquals(HubWrist.READ_ON_PHONE, refused?.message)
      assertEquals("/api/hub/proposals", server.takeRequest().path)
      // Nothing was answered; a no goes straight through.
      server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"proposal":{"id":"p1","says":"x","state":"declined"}}"""))
      HubWrist(hub).approve("p1", yes = false)
      val req = server.takeRequest()
      assertEquals("/api/hub/proposals/answer", req.path)
      assertEquals("""{"id":"p1","answer":"decline"}""", req.body.readUtf8())
      assertEquals(2, server.requestCount)
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun theWatchFaceCountsJobsAtWorkNotThoseWaitingForYou() = kotlinx.coroutines.runBlocking {
    val server = okhttp3.mockwebserver.MockWebServer()
    server.start()
    try {
      val hub = app.jarvis.glasses.hub.Hub(app.jarvis.glasses.jarvis.JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
      server.dispatcher =
          object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse().setBody(
                    if (request.path == "/api/v1/jobs") """{"jobs":[{"id":"j1","status":"running"},{"id":"j2","status":"running","waiting":{"for":"you","until":9}},{"id":"j3","status":"done"}]}"""
                    else """{"open":[],"recent":[],"later":[]}""",
                )
          }
      assertEquals(0 to 1, HubWrist(hub).glance())
    } finally {
      server.shutdown()
    }
  }

  @Test
  fun aTileJarvisWontReadWithNobodyPressingSaysWhyOnTheWrist() = kotlinx.coroutines.runBlocking {
    val server = okhttp3.mockwebserver.MockWebServer()
    server.start()
    try {
      val hub = app.jarvis.glasses.hub.Hub(app.jarvis.glasses.jarvis.JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
      server.dispatcher =
          object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                if (request.path.orEmpty().startsWith("/api/hub/panels/read")) okhttp3.mockwebserver.MockResponse().setResponseCode(409).setBody("""{"error":"It may do more than read"}""")
                else okhttp3.mockwebserver.MockResponse().setBody("""{"panels":[{"id":"p_home","title":"Home","rows":[{"key":"music","kind":"tile","label":"Music","read":true,"tool":"music_play"}]}]}""")
          }
      assertEquals("It may do more than read", HubWrist(hub).home().single().status)
    } finally {
      server.shutdown()
    }
  }
}
