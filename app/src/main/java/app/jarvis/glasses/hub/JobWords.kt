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

  /** The words for a tool its step is running: Chat's (chat/Steps.kt), "Searching the web" for a search. */
  private fun doings(tools: List<String>): String =
      tools.map { if (it == "web_search") "Searching the web" else app.jarvis.glasses.chat.Steps.doing(it) }.distinct().joinToString(", ").ifEmpty { "Thinking" }

  /** What it is doing this moment. */
  fun nowDoing(j: Job): String {
    val w = j.waiting
    if (w != null) return if (w.forWhat == "you") "Waiting for your answer" else "Waiting for the ChatGPT plan"
    j.wrapping?.let { return "Writing what it has: $it" }
    val out = j.helpers.count { it.state == "working" }
    if (out > 0) return "$out of ${j.helpers.size} helpers at work"
    return doings(j.doing)
  }

  /** What one of its helpers is doing, or how it ended. */
  fun helperNow(h: Helper, j: Job): String =
      when {
        h.state == "reported" -> h.why?.let { "Part done: $it, and it reported what it had" } ?: "Done: it has reported to the job"
        h.state == "stopped" -> "Came back with nothing: ${h.why ?: "it was stopped"}"
        j.status != "running" -> "Ended with the job"
        j.waiting?.forWhat == "plan" -> "Waiting for the ChatGPT plan"
        else -> doings(h.doing)
      }

  /** How far down its list, "3 of 5"; null with none. A dropped line is settled, and not counted as done. */
  fun progress(todo: List<Todo>): String? {
    val of = todo.count { it.state != "dropped" }
    return if (of == 0) null else "${todo.count { it.state == "done" }} of $of"
  }

  /** A line of its checklist's state, said: "now", "done", "not yet", "not done" ([over]: its job has ended). */
  fun todoWord(t: Todo, over: Boolean): String =
      if (over && (t.state == "doing" || t.state == "open")) "not done"
      else when (t.state) { "doing" -> "now"; "done" -> "done"; "dropped" -> "not done"; else -> "not yet" }

  /** What it may look at, do and take, in code's words, as the web's card says. */
  fun powers(j: Job): String {
    val looks =
        if (j.repo != null) "Has the files of ${j.repo}, and nothing else of yours but what it was asked. Does not search the web."
        else if (j.reach == "web") "Searches the web, and has nothing of yours but what it was asked."
        else "Reads your own things, as far as you may: mail, calendar, memory, house, car and cameras. Does not search the web."
    val makes =
        if (j.build) " Builds with programs of its own, on a machine with no network, and puts what it made to you to publish."
        else if (j.mode == "files") " Makes files with programs of its own, on a machine with no network, for you to save." else ""
    val splits = if (j.helpers.isNotEmpty()) " Splits its work between up to three helpers of its own, who may read what it may and nothing more." else ""
    val way = j.using?.let { " Follows “${it.name}”, of your specialties${if (j.reach == "web") ", which goes with it to the web" else ""}." }.orEmpty()
    val part = j.project?.let { " Part of “${it.name}”: its rules, its files${if (j.reach == "web") ", which go with it to the web" else " and what its last jobs found"}." }.orEmpty()
    val takes = j.limits?.let { " Up to ${it.minutes} minutes and ${it.steps} steps, ${it.going} while its list moves." }.orEmpty()
    return "$looks$makes$splits$way$part Never acts: what it would do, it proposes.${if (j.mode == "thorough") " On the stronger model." else ""}$takes"
  }

  /** A file's size, as a person says it. */
  fun sizeSaid(bytes: Long): String =
      if (bytes < 1000) "$bytes bytes" else if (bytes < 1_000_000) "${Math.round(bytes / 1000.0)} KB" else "%.1f MB".format(Locale.US, bytes / 1_000_000.0)

  /** How a command it ran ended; [over], its job has too. */
  fun ended(r: Ran, over: Boolean = false): String {
    val ms = r.ms
    val took = if (ms == null || ms < 100) "" else if (ms < 60_000) "%.1f s".format(Locale.US, ms / 1000.0) else "${Math.round(ms / 60_000.0)} min"
    if (r.full) return "stopped: it filled its folder, or the disk"
    if (r.late) return "stopped, out of time" + if (took.isNotEmpty()) " after $took" else ""
    val code = r.code
    if (code == null) return if (over) "ended with the job" else if (r.p != null) "left running" else "running"
    val c = if (code is kotlinx.serialization.json.JsonNull) null else code.content
    return if (c == "0") took.ifEmpty { "done" } else "failed (${c ?: "stopped"})" + if (took.isNotEmpty()) ", $took" else ""
  }

  /** The last three things its person told it, oldest first, and whether each has reached it. */
  fun heard(j: Job): List<Pair<String, String>> {
    val late = j.status != "running" || j.wrapping != null
    val atWork = j.helpers.count { it.state == "working" }
    return j.told.takeLast(3).map { t ->
      val said = if (t.card) "You changed its card" else if (t.to != null) "Your answer: ${t.text}" else "You said: ${t.text}"
      val state =
          when {
            t.taken -> "taken in"
            late -> "came too late"
            atWork == 0 -> "reaches it at its next step"
            t.to != null -> "reaches it when its helpers are done"
            t.helped -> "its helpers have it; it does when they are done"
            else -> "reaches its helpers at their next step"
          }
      said to state
    }
  }

  /** The facts under its title: what it is, how long, what it cost, what it reads. */
  fun facts(j: Job, now: Long): String {
    val kind =
        when {
          j.team != null -> "Research team"
          j.engine == "hermes" -> "Hermes"
          j.engine == "research" -> "Research"
          j.mode == "thorough" -> "Jarvis, thorough"
          j.build -> "Jarvis, building"
          j.mode == "files" -> "Jarvis, making files"
          else -> "Jarvis"
        }
    val out = mutableListOf(kind)
    if (j.status == "running") out += "${maxOf(1, Math.round((now - j.createdAt) / 60_000.0))} min so far" + if (j.engine == "jarvis") ", step ${j.steps}" else ""
    j.team?.let { t -> if (t.phase == "teams") out += "${t.reported} of ${t.of} teams done" else t.phase?.let { out += it } }
    j.finishedAt?.let { out += "took ${maxOf(1, Math.round((it - j.createdAt) / 60_000.0))} min" }
    j.cost?.takeIf { it > 0 }?.let { out += "about " + if (it < 0.01) "under 1¢" else "$" + "%.2f".format(Locale.US, it) }
    if (j.engine != "hermes") out += if (j.repo != null) "on ${j.repo}, no web" else if (j.engine == "research" || j.reach == "web") "the web only" else "your own things, no web"
    if (j.onKey) out += "on the API key"
    j.routine?.let { out += "from “${it.name}”" }
    if (j.carried > 0) out += "went on from its notes ${if (j.carried == 1) "once" else "${j.carried} times"}"
    return out.joinToString(" · ")
  }
}
