package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Routines made on the phone (lib/routines.ts in jarvis-voice-agent, `RoutineInput`): at a time, on
// chosen days, once, on a schedule, or when something happens; saying something, asking Jarvis, or
// starting a background job.

/**
 * A routine to make, as the web's form makes one (src/app/ui/Routines.ts there). [kind] "daily"
 * (with [days], none for every day), "once" ([localTime], "2026-12-01T07:30"), "cron" or "event";
 * [does] "say", "ask" or "job". A job (at a time only) reads one side ([reach]), may be [thorough],
 * stop at about [cap] dollars of the API key, and a daily or scheduled one may end on [until]
 * ("2026-12-14"). [check] says, in words, what Jarvis would refuse, before it is sent.
 */
data class NewRoutine(
    val name: String,
    val kind: String = "daily",
    val time: String = "07:30",
    val days: List<Int> = emptyList(),
    val localTime: String = "",
    val cron: String = "",
    val event: String = "",
    val does: String = "ask",
    val text: String = "",
    val reach: String = "web",
    val thorough: Boolean = false,
    val cap: Double? = null,
    val until: String = "",
) {
  /** Why Jarvis would refuse it, in words; null when it may go. */
  fun check(): String? =
      when {
        name.isBlank() -> "Give it a name."
        text.isBlank() -> if (does == "job") "Write the job's brief." else "Say what it says or asks."
        does == "job" && kind == "event" -> "A background job starts at a time: not when something happens."
        does == "job" && text.length > NewJob.MAX_BRIEF -> "A brief is ${NewJob.MAX_BRIEF} characters at most."
        kind == "daily" && !Regex("\\d{1,2}:\\d{2}").matches(time) -> "The time is HH:MM."
        kind == "once" && !Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}").matches(localTime) -> "When, as 2026-12-01T07:30."
        kind == "cron" && cron.trim().split(Regex("\\s+")).size != 5 -> "A schedule is five fields: minute hour day month weekday."
        kind == "event" && event.isBlank() -> "Name the event."
        cap != null && (cap < 0.05 || cap > 2.0) -> "What one run may cost is \$0.05 to \$2."
        until.isNotBlank() && kind != "daily" && kind != "cron" -> "Only a daily or scheduled routine has a last day."
        until.isNotBlank() && !Regex("20\\d\\d-\\d\\d-\\d\\d").matches(until) -> "Its last day, as 2026-12-14."
        else -> null
      }

  fun body(): JsonObject = buildJsonObject {
    put("name", name.trim())
    put("when", kind)
    when (kind) {
      "daily" -> {
        put("time", time)
        if (days.isNotEmpty()) put("days", JsonArray(days.sorted().map { JsonPrimitive(it) }))
      }
      "once" -> put("localTime", localTime)
      "cron" -> put("cron", cron.trim())
      "event" -> put("event", event.trim())
    }
    when (does) {
      "say" -> put("say", text.trim())
      "job" -> {
        put("job", reach)
        put("ask", text.trim())
        if (thorough) put("thorough", true)
        cap?.let { put("cap", it) }
      }
      else -> {
        put("ask", text.trim())
        // When something happens, said only when it is worth an alert.
        if (kind == "event") put("notify", "if-important")
      }
    }
    if (until.isNotBlank() && (kind == "daily" || kind == "cron")) put("until", until)
  }
}

@Serializable data class RoutineRuns(val runs: List<RoutineRun> = emptyList())

/** One of a routine's past runs: whether it went, what happened, and what it said, when it said something. */
@Serializable data class RoutineRun(val at: Long = 0, val ok: Boolean = true, val detail: String = "", val said: String? = null)

suspend fun Hub.addRoutine(r: NewRoutine) {
  r.check()?.let { throw app.jarvis.glasses.jarvis.JarvisClient.JarvisException(it) }
  post<JsonObject>("/api/v1/routines", r.body())
}

suspend fun Hub.routineRuns(id: String): List<RoutineRun> = get<RoutineRuns>("/api/v1/routines/runs?id=${enc(id)}").runs
