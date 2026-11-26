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
)

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

@Serializable data class Told(val at: Long = 0, val text: String = "", val taken: Boolean = false, val to: String? = null, val card: Boolean = false)

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
