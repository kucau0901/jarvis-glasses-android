package app.jarvis.glasses.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Background jobs (src/worker/lib/jobs.ts in jarvis-voice-agent, `jobView` in routes/jobs.ts; the
// web app's src/app/jobWords.ts names the same fields). Only what the app uses.

@Serializable data class Jobs(val jobs: List<Job> = emptyList())

@Serializable data class OneJob(val job: Job)

/**
 * A job as Jarvis shows it to whoever started it. [status] stays "running" while it [waiting]s for
 * its person (a question in [asks]) or for the ChatGPT plan. A "failed" one with [hasResult] (or a
 * [result]) is part done: [error] says what it ran out of. [missing] and [assumed] are what it could
 * not do and took for granted, kept apart from [result]. [result] comes only with one job asked for
 * by its id, with [asked], every question and what came of it.
 */
@Serializable
data class Job(
    val id: String,
    val title: String = "",
    val task: String = "",
    val status: String = "running",
    val engine: String = "jarvis",
    val createdAt: Long = 0,
    val finishedAt: Long? = null,
    val summary: String? = null,
    val result: String? = null,
    val hasResult: Boolean = false,
    val error: String? = null,
    val missing: List<String> = emptyList(),
    val assumed: List<String> = emptyList(),
    /** "web" or "private"; research is always the web's. */
    val reach: String? = null,
    val waiting: Waiting? = null,
    val asks: List<JobAsk> = emptyList(),
    val asked: List<JobAsked> = emptyList(),
    /** What its person told it while it worked; `to` an answer to a question, `taken` once a step had it. */
    val told: List<Told> = emptyList(),
    /** Dollars so far on the API key; null for a model with no listed price. */
    val cost: Double? = null,
    val steps: Int = 0,
    /** "thorough" (the stronger model) or "files" (it makes files in the workshop). */
    val mode: String? = null,
    /** It builds something, to publish with a tap; [repo], the repository it works on, with no web search. */
    val build: Boolean = false,
    val repo: String? = null,
    val routine: Named? = null,
    val project: Named? = null,
    /** The specialty it follows. */
    val using: Named? = null,
    val helpers: List<Helper> = emptyList(),
    /** How often a long one went on in a new conversation, from its notes. */
    val carried: Int = 0,
    val files: List<JobFile> = emptyList(),
    val ran: List<Ran> = emptyList(),
    val onKey: Boolean = false,
    val table: TableInfo? = null,
    val team: Team? = null,
    /** What it set out to do, which its person may change while it works, and its checklist. */
    val card: JobCard? = null,
    val todo: List<Todo> = emptyList(),
    /** The tools its step is running now; what it may take; and, out of that, of what (it is writing what it has). */
    val doing: List<String> = emptyList(),
    val limits: Limits? = null,
    val wrapping: String? = null,
    val deliveredBy: String? = null,
)

@Serializable data class JobCard(val outcome: String = "", val limits: List<String> = emptyList(), val assumptions: List<String> = emptyList())

/** A line of its checklist: [state] "open", "doing", "done" or "dropped". */
@Serializable data class Todo(val text: String = "", val state: String = "open", val why: String? = null)

/** One of its helpers: [state] "working", "reported" or "stopped". */
@Serializable
data class Helper(val n: Int, val name: String = "", val state: String = "working", val doing: List<String> = emptyList(), val todo: List<Todo> = emptyList(), val why: String? = null)

/** A command it ran in the workshop, and how it ended: no [code] while it runs; [by], its helper of that number. */
@Serializable
data class Ran(
    val at: Long = 0,
    val cmd: String = "",
    val p: String? = null,
    val by: Int? = null,
    val code: kotlinx.serialization.json.JsonPrimitive? = null,
    val ms: Long? = null,
    val late: Boolean = false,
    val full: Boolean = false,
)

/** A file it made, to save. */
@Serializable data class JobFile(val n: Int, val name: String = "", val size: Long = 0, val at: Long = 0)

@Serializable data class Limits(val steps: Int = 0, val going: Int = 0, val minutes: Int = 0)

/** Research into a table: its columns, and how many rows came back, to download as CSV. */
@Serializable data class TableInfo(val columns: List<String> = emptyList(), val rows: Int = 0)

/** Research as a team: how many teams reported, and which part it is in while it runs. */
@Serializable data class Team(val of: Int = 0, val reported: Int = 0, val phase: String? = null)

/**
 * A job to start, as the web's New job form makes one (src/app/ui/Jobs.ts there), its rules kept by
 * [body]: research is always the web's and ordinary, building is the web's, and one on a
 * repository looks neither way. [kind] "ordinary", "thorough", "files" or "build"; [research] "no",
 * "deep", "team" or "table".
 */
data class NewJob(
    val task: String,
    val reach: String = "web",
    val kind: String = "ordinary",
    val helpers: Boolean = false,
    val research: String = "no",
    val repo: String? = null,
    val specialty: String? = null,
    val project: String? = null,
) {
  fun body(): JsonObject {
    val research = research != "no"
    val kind = if (research) "ordinary" else kind
    return buildJsonObject {
      put("task", task)
      put("reach", if (research || kind == "build") "web" else reach)
      if (kind != "ordinary") put("mode", kind)
      if (kind == "build" && repo != null) put("repo", repo)
      if (helpers && !research) put("helpers", true)
      if (!research) specialty?.let { put("specialty", it) }
      if (!research) project?.let { put("project", it) }
      if (research) put("engine", "research")
      if (this@NewJob.research == "team" || this@NewJob.research == "table") put("team", true)
      if (this@NewJob.research == "table") put("table", true)
    }
  }

  companion object {
    /** The longest brief Jarvis takes whole (src/app/ui/Jobs.ts MAX_BRIEF). */
    const val MAX_BRIEF = 4_000
  }
}

/** Waiting until [until]: `for` "you" (a question) or "plan" (the ChatGPT plan's limit; [key] when it may go on with the API key). */
@Serializable data class Waiting(@SerialName("for") val forWhat: String = "", val until: Long = 0, val key: KeyCost? = null)

/** What its tokens so far would have cost on the key; null when unknown. */
@Serializable data class KeyCost(val soFar: Double? = null)

/** A question a job put to its person, with the choices it gave, if any. */
@Serializable data class JobAsk(val id: String, val at: Long = 0, val text: String = "", val choices: List<String> = emptyList())

/** One it put, and what came of it: their [answer], or [over] "skipped" (went on without) or "late" (none came in time). */
@Serializable
data class JobAsked(val id: String, val at: Long = 0, val text: String = "", val choices: List<String> = emptyList(), val answer: Answered? = null, val over: String? = null)

@Serializable data class Answered(val at: Long = 0, val text: String = "")

/** What its person told it: [to] an answer to a question, [card] a change to its card, [taken] once a step had it, [helped] its helpers have it. */
@Serializable data class Told(val at: Long = 0, val text: String = "", val taken: Boolean = false, val to: String? = null, val card: Boolean = false, val helped: Boolean = false)

/** What one of their jobs asks them, as `GET /api/hub/proposals` lists it: the job and its title with the question. */
@Serializable
data class Asking(val job: String, val title: String = "", val reach: String? = null, val id: String, val at: Long = 0, val text: String = "", val choices: List<String> = emptyList())

/** A question's place: its job and its id, which begin again in every job. */
@Serializable data class AskRef(val job: String, val id: String)

/** Their answer to question [question] of job [id], in their own words (a choice is that choice's words). */
suspend fun Hub.answerJob(id: String, question: String, text: String) {
  post<JsonObject>("/api/v1/jobs/tell", buildJsonObject { put("id", id); put("question", question); put("text", text.take(TELL_MAX)) })
}

/** Go on without an answer to it: the job finishes without, and says what that leaves open. */
suspend fun Hub.skipQuestion(id: String, question: String) {
  post<JsonObject>("/api/v1/jobs/tell", buildJsonObject { put("id", id); put("question", question); put("skip", true) })
}

/** Gone from Jobs, with what it made. */
suspend fun Hub.removeJob(id: String) {
  send<JsonObject>("DELETE", "/api/v1/jobs?id=${enc(id)}", buildJsonObject {})
}

/** As much as a job is told at once (routes/jobs.ts). */
const val TELL_MAX = 1_000

suspend fun Hub.startJob(job: NewJob): Job {
  if (job.task.length > NewJob.MAX_BRIEF) throw app.jarvis.glasses.jarvis.JarvisClient.JarvisException("It is ${job.task.length} characters, and a brief is ${NewJob.MAX_BRIEF} at most: shorten it, or its end would be left out.")
  return post<OneJob>("/api/v1/jobs", job.body()).job
}

/** Tells one still working [text], in its person's own words: it takes it in at its next step. */
suspend fun Hub.tellJob(id: String, text: String) {
  post<JsonObject>("/api/v1/jobs/tell", buildJsonObject { put("id", id); put("text", text.take(TELL_MAX)) })
}

/** Its card changed while it works (each list given whole): what changed reaches it at its next step. */
suspend fun Hub.changeCard(id: String, card: JobCard) {
  post<JsonObject>(
      "/api/v1/jobs/card",
      buildJsonObject {
        put("id", id)
        put("outcome", card.outcome)
        put("limits", kotlinx.serialization.json.JsonArray(card.limits.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("assumptions", kotlinx.serialization.json.JsonArray(card.assumptions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
      },
  )
}

/** Stops its helper [n]: the others go on, and the job is told. */
suspend fun Hub.stopHelper(id: String, n: Int) {
  post<JsonObject>("/api/v1/jobs/cancel", buildJsonObject { put("id", id); put("helper", n) })
}

/** Waiting for the ChatGPT plan: goes on with the API key now. */
suspend fun Hub.useKey(id: String) {
  post<JsonObject>("/api/v1/jobs/key", buildJsonObject { put("id", id) })
}

/** File [n] it made, as bytes to save. */
suspend fun Hub.jobFile(id: String, n: Int): ByteArray = bytes("/api/v1/jobs/file?id=${enc(id)}&n=$n")

/** Research as a table, as CSV. */
suspend fun Hub.jobCsv(id: String): ByteArray = bytes("/api/v1/jobs/csv?id=${enc(id)}")
