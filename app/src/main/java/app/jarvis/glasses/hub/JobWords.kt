package app.jarvis.glasses.hub

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A background job in words, for whoever started it, as the web app says them (src/app/jobWords.ts
 * and ui/Jobs.ts in jarvis-voice-agent). Written from what Jarvis's view says, never by a model.
 * No Android, so the tests read it.
 */
object JobWords {
  /** The short word beside its title: it asks you, waits, is part done, works, is done, could not finish, was cancelled. */
  fun status(j: Job): String =
      when {
        j.asks.isNotEmpty() -> "asks you"
        j.waiting != null -> "waiting"
        j.status == "failed" && (j.hasResult || j.result != null) -> "part done"
        j.status == "running" -> "working"
        j.status == "done" -> "done"
        j.status == "failed" -> "could not finish"
        j.status == "cancelled" -> "cancelled"
        else -> j.status
      }

  /** Why it waits and until when; null when it doesn't. */
  fun waiting(j: Job, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val w = j.waiting ?: return null
    val at = whenSaid(w.until, zone, locale)
    return if (w.forWhat == "you") "It has done what it can without you. If you have not answered by $at, it finishes without, and says what that leaves open."
    else "The ChatGPT plan is at its limit. This goes on by itself at about $at."
  }

  /** "Thu 15:20", as the web says a time within the week. */
  fun whenSaid(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
      DateTimeFormatter.ofPattern("EEE HH:mm", locale).format(Instant.ofEpochMilli(at).atZone(zone))

  /** What may move it to the API key now: its tokens so far there, in words, for asking first. */
  fun keyCost(soFar: Double?): String = soFar?.let { "About ${if (it < 0.01) "under 1¢" else "$" + "%.2f".format(Locale.US, it)} so far. Sure?" } ?: "It costs money. Sure?"

  /** What came of [q], as whoever was asked reads it; null while it may still be answered. */
  fun cameOf(q: JobAsked, j: Job): String? {
    val a = q.answer
    if (a != null) {
      val unheard = j.status != "running" && j.told.any { it.to != null && it.at == a.at && !it.taken }
      return "You answered: ${a.text}" + if (unheard) " (too late: it ended before it had that)" else ""
    }
    if (q.over == "skipped") return "You chose to go on without an answer."
    if (q.over == "late") return "No answer came in time, and it went on without one."
    return if (j.status == "running") null else "It ended before this was answered."
  }

  /** A question's alert (src/worker/lib/jobs.ts askAlert): the question, then its choices as "- " lines. */
  fun askOf(text: String): Pair<String, List<String>> {
    val at = text.indexOf("\n\n- ")
    if (at < 0) return text to emptyList()
    return text.substring(0, at) to text.substring(at + 4).split("\n- ").map { it.trim() }.filter { it.isNotEmpty() }
  }

  /** The same, said aloud: whose question it is first, so it is never heard as Jarvis's own. */
  fun askSaid(title: String, text: String): String {
    val (question, choices) = askOf(text)
    return "Your background job asks, about ${title.removePrefix("A question: ")}. $question" + if (choices.isNotEmpty()) " ${choices.joinToString(", ")}." else ""
  }

  /**
   * What a job asks, as the model is told it in the conversation: the job's words, which may come from
   * a page it read, so quoted and never Jarvis's own, and a yes to them an answer to that job and no
   * more (the web's askHistory). On a line of its own.
   */
  fun askHistory(title: String, text: String): String =
      "\n${oneLine(title, 100)} (a background job asks the user, in its own words; their reply is its answer, for job_control, and a yes to it agrees to nothing else): \"${oneLine(text, SKILL_SAID)}\"\n"
}
