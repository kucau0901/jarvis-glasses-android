package app.jarvis.tv

/** An app on the TV: what starts it, and the name it shows. */
data class TvApp(val pkg: String, val label: String)

/**
 * The app [said] asks for, if it asks to open one: "open Netflix", "buka YouTube", "launch the
 * Spotify app", or an app's name on its own. Null when it's something for Jarvis instead.
 */
fun appAsked(said: String, apps: List<TvApp>): TvApp? {
  val words = plain(said).split(' ').filter { it.isNotEmpty() && it !in FILLER }
  val verb = VERBS.firstOrNull { v -> words.take(v.size) == v }
  val name = if (verb != null) words.drop(verb.size) else words
  if (name.isEmpty()) return null
  val asked = name.joinToString("")
  val named = apps.map { it to plain(it.label).replace(" ", "") }
  named.firstOrNull { it.second == asked }?.let { return it.first }
  // Only "open …" reaches for a near name: on its own, "music" is a question, not Play Music.
  if (verb == null) return null
  return named.filter { it.second.startsWith(asked) || asked.length >= 4 && asked in it.second }.minByOrNull { it.second.length }?.first
}

/** Lower case, "+" as "plus", and no punctuation: "Disney+ Hotstar!" is "disney plus hotstar". */
private fun plain(s: String): String =
    s.lowercase().replace("+", " plus ").replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex(" +"), " ").trim()

private val VERBS =
    listOf("open", "launch", "start", "go to", "switch to", "buka", "bukak", "pergi ke", "tukar ke")
        .map { it.split(' ') }
        .sortedByDescending { it.size }

private val FILLER = setOf("the", "app", "please", "tolong", "aplikasi", "apps")
