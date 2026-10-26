package app.jarvis.glasses.chat

/**
 * What Chat shows while Jarvis works: one short pill per tool call, in words, never the tool's own
 * name (src/app/steps.ts in jarvis-voice-agent, which keeps the same list).
 */
object Steps {
  private val DOING =
      mapOf(
          "calendar_check" to "Checking your calendar",
          "calendar_add" to "Adding to your calendar",
          "mail_check" to "Checking your mail",
          "mail_search" to "Searching your mail",
          "mail_send" to "Sending that email",
          "mail_manage" to "Tidying your mail",
          "car_state" to "Checking the car",
          "car_command" to "Sending that to the car",
          "directions" to "Checking the route",
          "place_info" to "Looking up the place",
          "show_place" to "Finding it on the map",
          "hide_display" to "Clearing the screen",
          "look_at_camera" to "Looking at the camera",
          "show_camera" to "Bringing up the camera",
          "music_state" to "Checking the music",
          "music_play" to "Playing that",
          "music_control" to "Changing the music",
          "remember" to "Remembering that",
          "recall" to "Checking your notes",
          "forget" to "Forgetting that",
          "look_back" to "Looking back through our chat",
          "control_home" to "Asking home",
          "ask_hermes" to "Handing it to Hermes",
          "start_job" to "Starting a job",
          "job_results" to "Checking your jobs",
          "routine_add" to "Setting up the routine",
          "routine_list" to "Checking your routines",
          "routine_remove" to "Removing the routine",
          "send_note" to "Sending a note",
          "family_messages" to "Checking family messages",
          "answer_message" to "Answering that",
          "pass_on" to "Passing that on",
          "use_pass" to "Checking the pass",
          "chore_points" to "Checking the chores",
          "panel_set" to "Updating your Home",
          "panel_remove" to "Updating your Home",
          "panel_list" to "Checking your Home",
          "contacts_lookup" to "Looking up contacts",
          "make_image" to "Making the picture",
          "web_search" to "Searched the web",
      )

  /** A tool call, as words. Another server's tool (an MCP one, "label__name") waits for its own note. */
  fun doing(name: String): String = DOING[name] ?: if ("__" in name) "Checking" else "Working"

  /**
   * A progress note as the pill says it: "checking Home" → "Checking Home". What follows a ";" is
   * advice for the voice ("home is still thinking; say the user can ask you to drop it"), not for
   * reading.
   */
  fun said(text: String): String {
    val t = text.substringBefore(';').trim().trimEnd('.', '…')
    return t.replaceFirstChar { it.uppercase() }
  }
}

/** One pill: a tool call while it runs, ticked once done. */
data class Step(val key: String, val words: String, val running: Boolean)

/**
 * The pills of one question being answered: a tool event adds or ticks its pill, a note rewords
 * the pill it belongs to, else the newest running one, else "Thinking".
 */
class Work {
  var steps: List<Step> = emptyList()
    private set

  /** The words of the "Thinking" pill, which a note with no running call rewords. */
  var thinking = "Thinking"
    private set

  /** "Thinking" shows while no call is running. */
  val thinks: Boolean
    get() = steps.none { it.running }

  fun tool(name: String, id: String?, phase: String) {
    val key = id ?: name
    val running = phase == "start"
    steps = if (steps.any { it.key == key }) steps.map { if (it.key == key) it.copy(running = running) else it } else steps + Step(key, Steps.doing(name), running)
  }

  fun note(text: String, call: String?) {
    val words = Steps.said(text)
    if (words.isEmpty()) return
    val target = steps.firstOrNull { call != null && it.key == call } ?: steps.lastOrNull { it.running }
    if (target == null) thinking = words else steps = steps.map { if (it.key == target.key) it.copy(words = words) else it }
  }
}
