package app.jarvis.glasses.chat

import app.jarvis.glasses.hub.Job
import app.jarvis.glasses.hub.JobWords
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * What came of the background jobs' questions this phone has seen settled: answered here (a card,
 * a notification), or anywhere else, as a job's view later says. Every card for one question, its
 * notification and the G2's page read it, so all of them say so once it is answered anywhere, as
 * the web's cards do (src/app/ui/JobAsk.ts there). Kept while the app runs.
 */
class Asks {
  private val _settled = MutableStateFlow<Map<String, String>>(emptyMap())
  /** What came of each, by [key]. */
  val settled: StateFlow<Map<String, String>> = _settled

  /** [said] of question [id] of job [job]: "You answered: …", "You chose to go on without an answer." */
  fun settle(job: String, id: String, said: String) = _settled.update { it + (key(job, id) to said) }

  fun said(job: String, id: String): String? = _settled.value[key(job, id)]

  /** What a job's whole view says of its questions: those answered, skipped or late, or left when it ended, are settled. */
  fun seen(j: Job) {
    for (q in j.asked) JobWords.cameOf(q, j)?.let { settle(j.id, q.id, it) }
    if (j.status != "running") for (q in j.asks) settle(j.id, q.id, "It ended before this was answered.")
  }

  companion object {
    /** A question's own name: its ids begin again in every job. */
    fun key(job: String, id: String) = "$job:$id"
  }
}
