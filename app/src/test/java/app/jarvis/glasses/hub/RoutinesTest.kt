package app.jarvis.glasses.hub

import app.jarvis.glasses.jarvis.JarvisClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RoutinesTest {
  private val server = MockWebServer()
  private lateinit var hub: Hub

  @Before
  fun setUp() {
    server.start()
    hub = Hub(JarvisClient({ server.url("/").toString() }, { "jss1_test" }, { emptyMap() }))
  }

  @After fun tearDown() = server.shutdown()

  @Test
  fun aRoutineThatStartsAJobIsSentAsJarvisTakesIt() {
    val job = NewRoutine("Friday plan", kind = "daily", time = "18:00", days = listOf(5), does = "job", text = "Go through the week's mail and plan the next.", reach = "private", thorough = true, cap = 0.5, until = "2026-12-14")
    assertNull(job.check())
    assertEquals(
        """{"name":"Friday plan","when":"daily","time":"18:00","days":[5],"job":"private","ask":"Go through the week's mail and plan the next.","thorough":true,"cap":0.5,"until":"2026-12-14"}""",
        job.body().toString(),
    )
    assertEquals("""{"name":"Bins","when":"cron","cron":"0 20 * * 2","say":"Bins out"}""", NewRoutine("Bins", kind = "cron", cron = "0 20 * * 2", does = "say", text = "Bins out").body().toString())
    assertEquals("""{"name":"Home","when":"event","event":"arrived_home","ask":"Anything I should know?","notify":"if-important"}""", NewRoutine("Home", kind = "event", event = "arrived_home", text = "Anything I should know?").body().toString())
    assertEquals("""{"name":"Once","when":"once","localTime":"2026-12-01T07:30","ask":"Brief me"}""", NewRoutine("Once", kind = "once", localTime = "2026-12-01T07:30", text = "Brief me", until = "2026-12-14").body().toString())
  }

  @Test
  fun whatJarvisWouldRefuseIsSaidBeforeItIsSent() = runBlocking {
    assertEquals("A background job starts at a time: not when something happens.", NewRoutine("x", kind = "event", event = "e", does = "job", text = "t").check())
    assertEquals("What one run may cost is \$0.05 to \$2.", NewRoutine("x", does = "job", text = "t", cap = 3.0).check())
    assertEquals("Only a daily or scheduled routine has a last day.", NewRoutine("x", kind = "once", localTime = "2026-12-01T07:30", text = "t", until = "2026-12-14").check())
    assertEquals("The time is HH:MM.", NewRoutine("x", time = "7am", text = "t").check())
    assertEquals("A schedule is five fields: minute hour day month weekday.", NewRoutine("x", kind = "cron", cron = "0 8 *", text = "t").check())
    val refused = runCatching { hub.addRoutine(NewRoutine("x", does = "job", text = "t", cap = 3.0)) }.exceptionOrNull()
    assertEquals("What one run may cost is \$0.05 to \$2.", refused?.message)
    assertEquals(0, server.requestCount)
  }

  @Test
  fun aRoutinesPastRunsAreRead() = runBlocking {
    server.enqueue(MockResponse().setBody("""{"id":"r1","runs":[{"at":5,"ok":true,"detail":"said","said":"Two meetings."},{"at":4,"ok":false,"detail":"missed"}]}"""))
    val runs = hub.routineRuns("r1")
    assertEquals("Two meetings.", runs[0].said)
    assertEquals(false, runs[1].ok)
    assertEquals("/api/v1/routines/runs?id=r1", server.takeRequest().path)
  }
}
