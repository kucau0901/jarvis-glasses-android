package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// What is kept for the person to use in jobs, and kept true: their specialties (lib/specialties.ts and
// specialty-more.ts in jarvis-voice-agent), "Which is true now?" (lib/true-now.ts), their projects
// (lib/projects.ts) and the sites jobs built (lib/sites.ts). Reading needs `memory.read` (sites,
// `workshop`), changing `memory.write`; importing a specialty is an admin's.

@Serializable data class SpecialtyList(val specialties: List<Way> = emptyList())

/** A specialty as the list shows it: how it is asked for ([when]), its [steps], where it came from, and how many earlier versions it keeps. */
@Serializable
data class Way(
    val id: String,
    val name: String = "",
    val `when`: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val why: String? = null,
    val source: String = "written",
    val files: List<WayFile> = emptyList(),
    val before: Int = 0,
    val updatedAt: Long = 0,
)

@Serializable data class WayFile(val n: Int, val name: String = "", val size: Long = 0)

/** One whole: with its notes at length, its files, and what it was before each change. */
@Serializable
data class WayWhole(val specialty: Way, val source: String = "written", val text: String? = null, val files: List<WayFile> = emptyList(), val before: List<WayBefore> = emptyList())

@Serializable
data class WayBefore(val at: Long, val name: String = "", val `when`: List<String> = emptyList(), val steps: List<String> = emptyList(), val why: String? = null, val text: String? = null, val files: List<WayFile> = emptyList())

/**
 * A specialty being written, changed, imported or learned, for its person to read over and save:
 * its files kept by number ([WayFile]) or new, as bytes ([NewFile]). [card], a learned offer it came
 * from, answered when it is saved; [notes], what to know before saving it.
 */
data class WayDraft(
    val id: String? = null,
    val name: String = "",
    val `when`: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val why: String = "",
    val text: String = "",
    val kept: List<WayFile> = emptyList(),
    val added: List<NewFile> = emptyList(),
    val source: String? = null,
    val card: String? = null,
    val notes: List<String> = emptyList(),
) {
  fun body(): JsonObject = buildJsonObject {
    id?.let { put("id", it) }
    put("name", name)
    put("when", JsonArray(`when`.map { JsonPrimitive(it) }))
    put("steps", JsonArray(steps.map { JsonPrimitive(it) }))
    put("why", why)
    put("text", text)
    put("files", JsonArray(kept.map { buildJsonObject { put("n", it.n) } } + added.map { buildJsonObject { put("name", it.name); put("data", b64u(it.bytes)) } }))
    source?.let { put("source", it) }
  }
}

/** A file chosen from the phone, to go with a specialty or a project. */
class NewFile(val name: String, val bytes: ByteArray)

/** How a draft comes back from Jarvis (an import, a finished job's way): its files as base64url, to read over. */
@Serializable
data class DraftReply(val draft: DraftView, val title: String? = null)

@Serializable
data class DraftView(
    val id: String? = null,
    val name: String = "",
    val `when`: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    val why: String? = null,
    val text: String = "",
    val files: List<DraftFile> = emptyList(),
    val notes: List<String> = emptyList(),
) {
  fun draft(source: String): WayDraft =
      WayDraft(id, name, `when`, steps, why.orEmpty(), text, kept = files.mapNotNull { f -> f.n?.let { WayFile(it, f.name, f.size) } }, added = files.mapNotNull { f -> f.data?.let { NewFile(f.name, fromB64u(it)) } }, source = source, notes = notes)
}

@Serializable data class DraftFile(val name: String = "", val n: Int? = null, val size: Long = 0, val data: String? = null)

suspend fun Hub.specialtyNames(): List<Named> = ways().map { Named(it.id, it.name) }

suspend fun Hub.ways(): List<Way> = get<SpecialtyList>("/api/memory/specialties").specialties

suspend fun Hub.way(id: String): WayWhole = get("/api/memory/specialties?id=${enc(id)}")

suspend fun Hub.saveWay(d: WayDraft) {
  post<JsonObject>("/api/memory/specialties", d.body())
}

suspend fun Hub.forgetWay(id: String) {
  send<JsonObject>("DELETE", "/api/memory/specialties?id=${enc(id)}", buildJsonObject {})
}

/** As it was before the change made at [at]. */
suspend fun Hub.putBackWay(id: String, at: Long) {
  post<JsonObject>("/api/memory/specialties/back", buildJsonObject { put("id", id); put("at", at) })
}

/** A SKILL.md, or a zip with one, as a draft to read over: nothing is kept. An admin's. */
suspend fun Hub.importWay(name: String, bytes: ByteArray): WayDraft =
    post<DraftReply>("/api/memory/specialties/import", buildJsonObject { put("name", name); put("data", b64u(bytes)) }).draft.draft("imported")

/** How a finished job of theirs was done, as a draft to read over: nothing is kept. */
suspend fun Hub.wayOfJob(job: String): WayDraft = get<DraftReply>("/api/memory/specialties/draft?job=${enc(job)}").draft.draft("learned")

/** One as a zip other agents take. */
suspend fun Hub.exportWay(id: String): ByteArray = bytes("/api/memory/specialties/export?id=${enc(id)}")

// Which is true now?

@Serializable data class Fact(val id: String, val text: String = "", val address: String? = null, val createdAt: Long = 0, val updatedAt: Long = 0)

@Serializable data class Pair2(val older: Fact, val newer: Fact)

@Serializable data class Passed(val fact: Fact, val day: String = "")

/** Facts that may say one thing twice or disagree ([pairs]), and those whose day has passed. */
@Serializable data class Doubts(val pairs: List<Pair2> = emptyList(), val passed: List<Passed> = emptyList(), val byMeaning: Boolean = false)

@Serializable data class Forgotten(val forgotten: List<String> = emptyList())

private fun truePath(family: Boolean) = "/api/memory/true" + if (family) "?book=family" else ""

suspend fun Hub.doubts(family: Boolean = false): Doubts = get(truePath(family))

/** Of a pair, which are true ([keep], both, one or neither); the rest go to Recently forgotten. 409 when one changed since. */
suspend fun Hub.keepOfPair(family: Boolean, older: Fact, newer: Fact, keep: List<String>): List<String> =
    post<Forgotten>(
            truePath(family),
            buildJsonObject {
              put("pair", JsonArray(listOf(JsonPrimitive(older.id), JsonPrimitive(newer.id))))
              put("keep", JsonArray(keep.map { JsonPrimitive(it) }))
              put("seen", buildJsonObject { put(older.id, older.updatedAt); put(newer.id, newer.updatedAt) })
            },
        )
        .forgotten

/** One whose day has passed: still true, or forgotten. */
suspend fun Hub.keepPassed(family: Boolean, fact: Fact, keep: Boolean): List<String> =
    post<Forgotten>(truePath(family), buildJsonObject { put("id", fact.id); put("keep", keep); put("seen", buildJsonObject { put(fact.id, fact.updatedAt) }) }).forgotten

// Projects

@Serializable data class ProjectList(val projects: List<Project> = emptyList())

@Serializable data class Project(val id: String, val name: String = "", val rules: List<String> = emptyList(), val files: List<WayFile> = emptyList())

suspend fun Hub.projectNames(): List<Named> = projects().map { Named(it.id, it.name) }

suspend fun Hub.projects(): List<Project> = get<ProjectList>("/api/v1/projects").projects

/** New ([id] null), or changed: its name and rules, up to ten. */
suspend fun Hub.saveProject(id: String?, name: String, rules: List<String>) {
  post<JsonObject>("/api/v1/projects", buildJsonObject { id?.let { put("id", it) }; put("name", name); put("rules", JsonArray(rules.map { JsonPrimitive(it) })) })
}

/** Gone, its files with it; its jobs stay. */
suspend fun Hub.forgetProject(id: String) {
  send<JsonObject>("DELETE", "/api/v1/projects?id=${enc(id)}", buildJsonObject {})
}

suspend fun Hub.addProjectFile(id: String, f: NewFile) {
  post<JsonObject>("/api/v1/projects/file", buildJsonObject { put("id", id); put("name", f.name); put("data", b64u(f.bytes)) })
}

/** File [n] of job [job] of theirs, kept in project [id]. */
suspend fun Hub.keepJobFile(id: String, job: String, n: Int) {
  post<JsonObject>("/api/v1/projects/file", buildJsonObject { put("id", id); put("job", job); put("n", n) })
}

suspend fun Hub.projectFile(id: String, n: Int): ByteArray = bytes("/api/v1/projects/file?id=${enc(id)}&n=$n")

suspend fun Hub.dropProjectFile(id: String, n: Int) {
  send<JsonObject>("DELETE", "/api/v1/projects/file?id=${enc(id)}&n=$n", buildJsonObject {})
}

// Sites

/** What jobs built and a tap published: [sites] null where there is no sites folder. */
@Serializable data class SitesReply(val address: String? = null, val sites: List<Site>? = null)

@Serializable data class Site(val name: String, val at: Long = 0, val files: Int = 0, val bytes: Long = 0, val down: Boolean = false, val before: Long? = null)

suspend fun Hub.sites(): SitesReply = get("/api/v1/sites")

/** [op] "back" (as it was before) or "down" (taken down, its last version kept). */
suspend fun Hub.siteOp(name: String, op: String) {
  post<JsonObject>("/api/v1/sites", buildJsonObject { put("name", name); put("op", op) })
}

/** Bytes as base64url, as Jarvis takes a file in JSON. */
fun b64u(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun fromB64u(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s.trimEnd('='))
