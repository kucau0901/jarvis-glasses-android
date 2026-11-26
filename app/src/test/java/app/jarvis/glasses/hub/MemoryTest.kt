package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import app.jarvis.glasses.ui.draftOfCard
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MemoryTest {
  private val server = MockWebServer()
  private lateinit var hub: Hub

  @Before
  fun setUp() {
    server.start()
    hub = Hub(JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
  }

  @After fun tearDown() = server.shutdown()

  private fun ok(body: String = """{"ok":true}""") = server.enqueue(MockResponse().setBody(body))

  @Test
  fun aSpecialtyIsReadWholeSavedWithItsFilesAndPutBack() = runBlocking {
    ok("""{"specialties":[{"id":"s1","name":"Weekly report","when":["the weekly report"],"steps":["Read the mail"],"source":"learned","more":true,"files":[{"n":1,"name":"make.py","size":120}],"before":2,"updatedAt":9}]}""")
    assertEquals(2, hub.ways().single().before)
    server.takeRequest()
    ok("""{"specialty":{"id":"s1","name":"Weekly report","steps":["Read the mail"]},"source":"learned","text":"Notes","files":[{"n":1,"name":"make.py","size":120}],"before":[{"at":5,"name":"Old","steps":["x"],"files":[]}]}""")
    val w = hub.way("s1")
    assertEquals("Notes", w.text)
    assertEquals(5L, w.before.single().at)
    server.takeRequest()
    ok()
    hub.saveWay(WayDraft("s1", "Weekly report", listOf("the weekly report"), listOf("Read the mail"), "", "Notes", kept = listOf(WayFile(1, "make.py", 120)), added = listOf(NewFile("new.txt", "hi".toByteArray())), source = "written"))
    assertEquals(
        """{"id":"s1","name":"Weekly report","when":["the weekly report"],"steps":["Read the mail"],"why":"","text":"Notes","files":[{"n":1},{"name":"new.txt","data":"aGk"}],"source":"written"}""",
        server.takeRequest().body.readUtf8(),
    )
    ok()
    hub.putBackWay("s1", 5)
    assertEquals("""{"id":"s1","at":5}""", server.takeRequest().body.readUtf8())
  }

  @Test
  fun anImportAndAJobsWayAreDraftsToReadOver() = runBlocking {
    ok("""{"draft":{"name":"Tidy photos","when":["tidy my photos"],"steps":["Group by day"],"text":"","files":[{"name":"run.sh","data":"ZWNobw"}],"notes":["Its allowed-tools are not used."]}}""")
    val d = hub.importWay("skill.zip", byteArrayOf(1, 2))
    assertEquals("imported", d.source)
    assertArrayEquals("echo".toByteArray(), d.added.single().bytes)
    assertEquals("""{"name":"skill.zip","data":"AQI"}""", server.takeRequest().body.readUtf8())
    ok("""{"draft":{"id":"s1","name":"Weekly report","when":[],"steps":["Read the mail"],"text":"","files":[],"notes":[]}}""")
    assertEquals("learned", hub.wayOfJob("j1").source)
    assertEquals("/api/memory/specialties/draft?job=j1", server.takeRequest().path)
  }

  @Test
  fun whatIsTrueNowIsChosenWithWhatWasShown() = runBlocking {
    ok("""{"pairs":[{"older":{"id":"f1","text":"The gym is on Jalan Satu","createdAt":1,"updatedAt":2},"newer":{"id":"f2","text":"The gym moved to Jalan Dua","createdAt":3,"updatedAt":4}}],"passed":[{"fact":{"id":"f3","text":"Exam on 3 March","updatedAt":5},"day":"2026-03-03"}],"byMeaning":true}""")
    val d = hub.doubts(family = true)
    assertEquals("/api/memory/true?book=family", server.takeRequest().path)
    ok("""{"ok":true,"forgotten":["f1"]}""")
    assertEquals(listOf("f1"), hub.keepOfPair(false, d.pairs[0].older, d.pairs[0].newer, listOf("f2")))
    assertEquals("""{"pair":["f1","f2"],"keep":["f2"],"seen":{"f1":2,"f2":4}}""", server.takeRequest().body.readUtf8())
    ok("""{"ok":true,"forgotten":[]}""")
    hub.keepPassed(false, d.passed[0].fact, keep = true)
    assertEquals("""{"id":"f3","keep":true,"seen":{"f3":5}}""", server.takeRequest().body.readUtf8())
  }

  @Test
  fun aProjectAndItsFilesAndASiteAreChanged() = runBlocking {
    ok("""{"projects":[{"id":"p1","name":"Kitchen","rules":["Under RM20k"],"files":[{"n":1,"name":"plan.pdf","size":1000}]}]}""")
    assertEquals("Kitchen", hub.projects().single().name)
    server.takeRequest()
    repeat(4) { ok() }
    hub.saveProject(null, "Garden", listOf("No pesticides"))
    assertEquals("""{"name":"Garden","rules":["No pesticides"]}""", server.takeRequest().body.readUtf8())
    hub.addProjectFile("p1", NewFile("a.txt", "hi".toByteArray()))
    assertEquals("""{"id":"p1","name":"a.txt","data":"aGk"}""", server.takeRequest().body.readUtf8())
    hub.keepJobFile("p1", "j1", 2)
    assertEquals("""{"id":"p1","job":"j1","n":2}""", server.takeRequest().body.readUtf8())
    hub.dropProjectFile("p1", 1)
    val req = server.takeRequest()
    assertEquals("DELETE", req.method)
    assertEquals("/api/v1/projects/file?id=p1&n=1", req.path)
    ok("""{"address":"https://sites.example.com","sites":[{"name":"bake-sale","at":5,"files":3,"bytes":2048,"before":4}]}""")
    assertEquals(4L, hub.sites().sites!!.single().before)
    server.takeRequest()
    ok()
    hub.siteOp("bake-sale", "back")
    assertEquals("""{"name":"bake-sale","op":"back"}""", server.takeRequest().body.readUtf8())
    ok("""{"address":null,"sites":null}""")
    assertEquals(null, hub.sites().sites)
  }

  @Test
  fun aLearnedOffersCardIsADraftToChangeFirst() {
    val args = buildJsonObject {
      put("id", "s1")
      put("at", 9)
      put("name", "Weekly report")
      putJsonArray("when") { add("the weekly report") }
      putJsonArray("steps") { add("Read the mail"); add("Only this week's") }
    }
    val d = draftOfCard(Proposal("p1", "Keep how this was done", action = ProposalAction("specialty_keep", args)))
    assertEquals("s1", d.id)
    assertEquals(listOf("Read the mail", "Only this week's"), d.steps)
    assertEquals("p1", d.card)
    assertEquals("learned", d.source)
    assertArrayEquals("hello".toByteArray(), fromB64u(b64u("hello".toByteArray())))
  }
}
