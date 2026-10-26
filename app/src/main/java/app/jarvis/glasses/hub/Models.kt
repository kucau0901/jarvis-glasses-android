package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// The shapes the app reads from Jarvis, as defined in the jarvis-voice-agent repository
// (src/shared/panels.ts and the files in src/worker/lib). Only the fields the app uses.

@Serializable
data class Me(
    val user: Person? = null,
    val role: String = "",
    val scopes: List<String> = emptyList(),
    val live: Boolean = false,
    val locked: Boolean = false,
) {
  fun can(scope: String) = role == "admin" || "*" in scopes || scope in scopes
}

@Serializable data class Person(val id: String, val name: String)

// Home panels (src/shared/panels.ts)

@Serializable data class PanelsReply(val panels: List<Panel> = emptyList())

@Serializable data class Panel(val id: String, val title: String, val rows: List<PanelRow> = emptyList())

@Serializable
data class PanelRow(
    val key: String,
    val kind: String,
    val label: String,
    val value: String = "",
    val icon: String = "dot",
    val active: Boolean = false,
    val level: Double? = null,
    val tiles: List<Figure> = emptyList(),
    val actions: List<PanelAction> = emptyList(),
    val note: String? = null,
    val more: PanelMore? = null,
    val done: Boolean? = null,
)

@Serializable data class Figure(val label: String, val value: String)

@Serializable data class PanelAction(val action: String, val label: String, val input: ButtonInput? = null)

@Serializable data class ButtonInput(val kind: String, val min: Double? = null, val max: Double? = null, val step: Double? = null, val hint: String? = null)

@Serializable
data class PanelMore(
    val members: List<PanelRow>? = null,
    val climate: Climate? = null,
    val actions: List<PanelAction>? = null,
    val code: AlarmCode? = null,
    val level: Level? = null,
)

@Serializable
data class Climate(
    val now: Double? = null,
    val target: Double? = null,
    val min: Double,
    val max: Double,
    val step: Double,
    val modes: List<String> = emptyList(),
    val mode: String = "",
)

@Serializable data class AlarmCode(val digits: Boolean = true, @kotlinx.serialization.SerialName("for") val forActions: List<String> = emptyList())

@Serializable data class Level(val name: String, val value: Double, val min: Double, val max: Double, val step: Double, val unit: String = "")

@Serializable data class ActReply(val text: String = "")

// Chat history (src/worker/lib/conversation.ts)

@Serializable
data class Said(
    val id: String,
    val at: Long = 0,
    val role: String,
    val text: String,
    val links: List<Link>? = null,
    val job: Named? = null,
    val routine: Named? = null,
)

@Serializable data class Link(val url: String, val title: String)

@Serializable data class Named(val id: String, val name: String)

@Serializable data class Conversation(val said: List<Said> = emptyList())

// Family messages (src/worker/lib/chat.ts)

@Serializable data class ChatList(val you: String = "", val convos: List<Convo> = emptyList(), val people: List<Person> = emptyList())

@Serializable data class Convo(val id: String, val title: String, val last: ChatMessage? = null, val unread: Int = 0)

@Serializable data class ChatMessage(val id: String, val at: Long, val from: String, val name: String, val text: String)

@Serializable data class Messages(val messages: List<ChatMessage> = emptyList())

@Serializable data class Sent(val message: ChatMessage)

// Approvals (src/worker/lib/proposals.ts)

@Serializable
data class Proposals(
    val open: List<Proposal> = emptyList(),
    val recent: List<Proposal> = emptyList(),
    val later: List<LaterProposal> = emptyList(),
)

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
)

@Serializable data class Outcome(val ok: Boolean, val text: String)

@Serializable data class LaterProposal(val id: String, val says: String, val why: String = "", val `when`: String = "")

// Jobs and routines (src/worker/lib/jobs.ts, routines.ts)

@Serializable data class Jobs(val jobs: List<Job> = emptyList())

@Serializable data class OneJob(val job: Job)

@Serializable
data class Job(
    val id: String,
    val title: String = "",
    val task: String = "",
    val status: String = "running",
    val createdAt: Long = 0,
    val summary: String? = null,
    val result: String? = null,
    val error: String? = null,
)

@Serializable data class Routines(val routines: List<Routine> = emptyList())

@Serializable
data class Routine(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val `when`: String = "",
    val does: String = "",
    val lastRun: LastRun? = null,
    val trigger: JsonElement? = null,
)

@Serializable data class LastRun(val at: Long, val ok: Boolean, val detail: String = "")
