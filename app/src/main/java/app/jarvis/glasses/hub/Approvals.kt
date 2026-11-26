package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What background jobs and briefs propose, for their person's tap (src/worker/lib/proposals.ts in
// jarvis-voice-agent, and src/app/proposal.ts there, which draws the same cards). Only a tap does
// one: nothing a model says can.

/**
 * `GET /api/hub/proposals`: what waits for an OK ([open]), what was decided lately ([recent]), the
 * kinds jobs may do without asking ([allowed], "car_command:lock"), what waits to be put to them
 * later, and what their jobs ask them ([asking]).
 */
@Serializable
data class Proposals(
    val open: List<Proposal> = emptyList(),
    val recent: List<Proposal> = emptyList(),
    val later: List<LaterProposal> = emptyList(),
    val allowed: List<String> = emptyList(),
    val asking: List<Asking> = emptyList(),
)

/**
 * One card. [body] is what it would send or change, shown whole (an email's words, the files a site
 * replaces); [risk] "irreversible" can't be undone. [verb] is its button's word ("Publish"), Approve
 * without one; [about], a brief's card, whose decline is remembered. Once tapped: [state], [by] ("tap",
 * or "standing" for a kind allowed without asking), [decidedAt] and [outcome]. [undoes] and [undone]
 * pair a Put back with what it put back.
 */
@Serializable
data class Proposal(
    val id: String,
    val says: String,
    val why: String = "",
    val body: String? = null,
    val risk: String = "reversible",
    val verb: String? = null,
    val about: String? = null,
    val expires: Long = 0,
    val state: String = "pending",
    val outcome: Outcome? = null,
    val action: ProposalAction? = null,
    val category: String? = null,
    val by: String? = null,
    val decidedAt: Long? = null,
    val undoes: String? = null,
    val undone: String? = null,
)

/** The call it would make, as it was stored: which tool, and its arguments (a learned specialty's steps, say). Shown, never run from here. */
@Serializable data class ProposalAction(val tool: String = "", val args: JsonObject = JsonObject(emptyMap()))

@Serializable data class Outcome(val ok: Boolean, val text: String)

@Serializable
data class LaterProposal(val id: String, val says: String, val why: String = "", val `when`: String = "", val body: String? = null, val risk: String = "reversible")

@Serializable data class ProposalReply(val proposal: Proposal)

@Serializable data class AllowedReply(val allowed: List<String> = emptyList())

/** How long Put back is offered once done (lib/proposals.ts UNDO_MS). */
const val UNDO_MS = 7 * 86_400_000L

/** What a Put back can undo: archived mail and a published site. */
private val PUT_BACK = setOf("mail_archive_many", "site_publish")

/**
 * Whether it must be read before it is approved: it has words or files to read, it can't be undone,
 * or it is a way of doing things to be kept. Never approved where that can't be read (the watch).
 */
val Proposal.needsPhone: Boolean
  get() = !body.isNullOrBlank() || risk == "irreversible" || action?.tool == "specialty_keep"

/** Approved by a tap and done, of a kind Put back undoes, not put back yet, and within the week. */
fun Proposal.canPutBack(now: Long): Boolean =
    state == "done" && by == "tap" && action?.tool in PUT_BACK && undone == null && now - (decidedAt ?: 0) < UNDO_MS

/** Approved by a tap and done, and reversible of a kind: jobs may then be let do it without asking. */
fun Proposal.canAllow(allowed: List<String>): Boolean = state == "done" && by == "tap" && risk == "reversible" && category != null && category !in allowed

/** What came of it, in words, as the web's card says; empty while it waits. */
fun Proposal.stateWords(): String {
  val reason = outcome?.text?.takeIf { (state == "expired" || state == "withdrawn") && it.isNotBlank() }
  val said =
      reason
          ?: when {
            state == "declined" && about != null -> "Not suggested again."
            state == "declined" && action?.tool == "specialty_keep" -> "Not kept from this card."
            else ->
                when (state) {
                  "running" -> "Doing it…"
                  "done" -> "✓ Done"
                  "failed" -> "Didn't work"
                  "declined" -> "Not done: you said not now."
                  "expired" -> "Not done: its time ran out."
                  "withdrawn" -> "Not done: its job was cancelled."
                  else -> ""
                }
          }
  val standing = if (by == "standing") " without asking (you allow it)" else ""
  val outcomeText = outcome?.text?.takeIf { reason == null && it.isNotBlank() }?.let { ": $it" }.orEmpty()
  return said + standing + outcomeText
}

/** "5 h left", or "less than an hour left". */
fun hoursLeft(expires: Long, now: Long): String {
  val h = Math.round((expires - now) / 3_600_000.0)
  return if (h >= 1) "$h h left" else "less than an hour left"
}

/** Let jobs make this kind of call without asking, from a card of it approved and done: the kinds allowed now. */
suspend fun Hub.allow(id: String): List<String> = post<AllowedReply>("/api/hub/proposals/allow", buildJsonObject { put("id", id) }).allowed

/** No more of [category] without asking. */
suspend fun Hub.stopAllowing(category: String) {
  send<JsonObject>("DELETE", "/api/hub/proposals/allow?category=${enc(category)}", buildJsonObject {})
}
