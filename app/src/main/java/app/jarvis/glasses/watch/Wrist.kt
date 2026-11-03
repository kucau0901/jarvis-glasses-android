package app.jarvis.glasses.watch

import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.Panel
import app.jarvis.glasses.hub.PanelAction
import app.jarvis.glasses.hub.PanelRow
import app.jarvis.glasses.hub.PanelsReply
import app.jarvis.glasses.hub.Proposal
import app.jarvis.glasses.hub.Reading
import app.jarvis.link.Approval
import app.jarvis.link.Button
import app.jarvis.link.Dial
import app.jarvis.link.Member
import app.jarvis.link.Thing
import app.jarvis.link.WatchLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** What the watch shows on the wrist, from the person's Jarvis: what [WatchRelay] answers a fetch with. */
interface Wrist {
  suspend fun home(): List<Thing>

  suspend fun approvals(): List<Approval>

  /** Approvals waiting, and jobs running. */
  suspend fun glance(): Pair<Int, Int>

  /** What Jarvis said of it. [member]: one of a room's things, by its key. */
  suspend fun act(panel: String, key: String, action: String, value: String?, member: String? = null): String

  suspend fun approve(proposal: String, yes: Boolean)
}

/** The wrist from the phone's [Hub], signed in as the person (a device token is refused: [Hub.NotAPerson]). */
class HubWrist(private val hub: Hub) : Wrist {
  override suspend fun home(): List<Thing> {
    val panels = hub.panels()
    // The kept reading of each thing that has one to show, all at once: the watch waits on them.
    val reads = readsOf(panels).take(MAX_READS)
    val readings = coroutineScope {
      // Each given a little while: one slow reading mustn't keep the watch from Home altogether.
      reads.map { (p, r) -> async { withTimeoutOrNull(READ_MS) { runCatching { hub.read(p.id, r.key, cached = true) }.getOrNull() }?.let { "${p.id}/${r.key}" to it } } }.awaitAll()
    }
    return fitted(thingsOf(panels, readings.filterNotNull().toMap()))
  }

  override suspend fun approvals(): List<Approval> = hub.proposals().open.filter { it.state == "pending" }.map(::approvalOf)

  override suspend fun glance(): Pair<Int, Int> = coroutineScope {
    val approvals = async { hub.proposals().open.count { it.state == "pending" } }
    val jobs = async { hub.jobs().count { it.status == "running" } }
    approvals.await() to jobs.await()
  }

  override suspend fun act(panel: String, key: String, action: String, value: String?, member: String?): String = hub.act(panel, key, action, member = member, value = value)

  override suspend fun approve(proposal: String, yes: Boolean) = hub.answer(proposal, if (yes) "approve" else "decline")

  companion object {
    /** As many of Home's things as one message to the watch holds with room to spare. */
    const val MAX_THINGS = 40
    private const val MAX_BUTTONS = 6
    /** An aircond's modes and fan speeds, each with room of their own beside its buttons. */
    private const val MAX_MODES = 6
    private const val MAX_FANS = 4
    /** What one message to the watch may hold of Home: the Data Layer takes about 100 KB. */
    const val MAX_BYTES = 80_000
    /** A room's things the watch lists, and each one's buttons. */
    private const val MAX_MEMBERS = 12
    private const val MAX_MEMBER_BUTTONS = 3
    private const val MAX_READS = 12
    private const val READ_MS = 4_000L

    /** The things whose state is a reading looked up on its own (no value of their own, no warning). */
    fun readsOf(panels: PanelsReply): List<Pair<Panel, PanelRow>> =
        panels.panels.flatMap { p -> p.rows.filter { it.kind != "note" && it.read && it.value.isEmpty() && it.note == null }.map { p to it } }

    /**
     * Home's things as the wrist has them, in Home's order: to-do lists left out (the watch has no
     * room to tick them yet). A button needing a PIN, something typed or something chosen is left
     * out too; a level or an aircond's set point becomes the bezel's [Dial], its modes and fan speeds
     * buttons. A room keeps its things ([Member]s), each with its own buttons, as the phone's sheet.
     */
    fun thingsOf(panels: PanelsReply, readings: Map<String, Reading>): List<Thing> =
        panels.panels
            .flatMap { p -> p.rows.filter { it.kind != "note" }.map { r -> thingOf(p, r, readings["${p.id}/${r.key}"]) } }
            // The watch lists them by id: one of each, should a panel name two tiles alike.
            .distinctBy { it.id }
            .take(MAX_THINGS)

    fun thingOf(panel: Panel, row: PanelRow, reading: Reading?): Thing {
      val more = row.more
      val needsCode = more?.code?.forActions.orEmpty().toSet()
      val plain = (row.actions + more?.actions.orEmpty()).filter { it.input == null && it.action !in needsCode }
      val climate = more?.climate
      val dial =
          more?.level?.let { Dial(it.name, it.value, it.min, it.max, it.step, it.unit, "level:") }
              ?: climate?.takeIf { it.mode != "off" }?.target?.let { t -> Dial("Temperature", t, climate.min, climate.max, climate.step, "°", "temp:") }
      val modes =
          climate?.modes.orEmpty().take(MAX_MODES).map { PanelAction("mode:$it", pretty(it)) } +
              climate?.fans.orEmpty().take(MAX_FANS).map { PanelAction("fan:$it", "Fan " + pretty(it).lowercase()) }
      val members = more?.members.orEmpty().distinctBy { it.key to it.label }
      return Thing(
          panel = panel.id,
          key = row.key,
          label = row.label,
          icon = row.icon,
          status = statusOf(row, reading),
          active = row.active,
          buttons = (plain.distinctBy { it.action }.take(MAX_BUTTONS) + modes).distinctBy { it.action }.map { Button(it.action, it.label) },
          dial = dial,
          members =
              members.take(MAX_MEMBERS).map { m ->
                Member(m.key, m.label, statusOf(m, null), m.active, m.actions.filter { it.input == null }.take(MAX_MEMBER_BUTTONS).map { Button(it.action, it.label) })
              },
          moreMembers = (members.size - MAX_MEMBERS).coerceAtLeast(0),
      )
    }

    /** As many of [things] as fit one message to the watch, in Home's order: the last left out first. */
    fun fitted(things: List<Thing>, maxBytes: Int = MAX_BYTES): List<Thing> {
      var n = things.size
      while (n > 0 && WatchLink.encodeThings(things.take(n)).length > maxBytes) n--
      return things.take(n)
    }

    /** A thing's state in a line, as the TV's tile has it: a warning, its value, its reading, then its figures. */
    fun statusOf(row: PanelRow, reading: Reading?): String =
        when {
          row.note != null -> row.note
          row.value.isNotEmpty() -> row.value
          row.read -> reading?.let { r -> r.text.ifBlank { r.figures.take(3).joinToString(" · ") { "${it.label} ${it.value}".trim() } } }.orEmpty()
          else -> row.tiles.joinToString(" · ") { "${it.label} ${it.value}".trim() }
        }

    fun approvalOf(p: Proposal) = Approval(p.id, p.says, p.why, p.verb ?: "Approve", p.risk)

    /** "fan_only" as "Fan only". */
    private fun pretty(word: String) = word.replace('_', ' ').replaceFirstChar { it.uppercase() }
  }
}
