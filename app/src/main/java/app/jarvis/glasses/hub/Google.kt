package app.jarvis.glasses.hub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The person's own Google accounts (routes/google.ts in jarvis-voice-agent): several may be linked,
// and linking again with another account adds it.

/** `GET /api/google/status`: each linked account, tried live, with what went wrong with one in words. */
@Serializable data class GoogleStatus(val linked: Boolean = false, val accounts: List<GoogleAccount> = emptyList())

@Serializable data class GoogleAccount(val id: String = "", val account: String? = null, val working: Boolean = false, val problem: String? = null)

suspend fun Hub.googleAccounts(): GoogleStatus = get("/api/google/status")

/** One account unlinked, by its id from [googleAccounts]. */
suspend fun Hub.unlinkGoogle(id: String) {
  post<JsonObject>("/api/google/unlink", buildJsonObject { put("account", id) })
}
