package app.jarvis.glasses.chat

import app.jarvis.glasses.alerts.AskNotice
import app.jarvis.glasses.hub.Answered
import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobAsk
import app.jarvis.glasses.hub.JobAsked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AsksTest {
  @Test
  fun aQuestionAnsweredAnywhereIsSettledForEveryCardOfIt() {
    val asks = Asks()
    assertNull(asks.said("j1", "q1"))
    asks.settle("j1", "q1", "You answered: Phones only")
    assertEquals("You answered: Phones only", asks.said("j1", "q1"))
    // Ids begin again in every job.
    assertNull(asks.said("j2", "q1"))
  }

  @Test
  fun aJobsViewSettlesWhatItSaysCameOfItsQuestions() {
    val asks = Asks()
    asks.seen(Job("j1", asked = listOf(JobAsked("q1", answer = Answered(5, "Under RM800")), JobAsked("q2", over = "skipped"), JobAsked("q3"))))
    assertEquals("You answered: Under RM800", asks.said("j1", "q1"))
    assertEquals("You chose to go on without an answer.", asks.said("j1", "q2"))
    // Still open while it works.
    assertNull(asks.said("j1", "q3"))
    asks.seen(Job("j1", status = "cancelled", asks = listOf(JobAsk("q3", text = "Which?"))))
    assertEquals("It ended before this was answered.", asks.said("j1", "q3"))
  }

  @Test
  fun aQuestionsNotificationOffersTwoChoicesAndReplyOrGoingOnWithout() {
    assertEquals(
        listOf(AskNotice.Act("Phones only", text = "Phones only"), AskNotice.Act("Tablets too", text = "Tablets too"), AskNotice.Act("Reply", reply = true)),
        AskNotice.actionsOf(listOf("Phones only", "Tablets too", "Either")),
    )
    assertEquals(listOf(AskNotice.Act("Reply", reply = true), AskNotice.Act("Go on without me", skip = true)), AskNotice.actionsOf(emptyList()))
  }
}
