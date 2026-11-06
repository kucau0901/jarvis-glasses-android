package app.jarvis.tv.jarvis

import kotlinx.serialization.Serializable

// Jarvis's Home panels as the phone app reads them (app/…/hub/Models.kt, from the jarvis-voice-agent
// repository's src/shared/panels.ts). A copy until those models move into core with the phone's
// own changes to them.

// Home panels (src/shared/panels.ts)

@Serializable data class PanelsReply(val panels: List<Panel> = emptyList(), val can: Can? = null)

/** What a panel could show for this person, for an empty Home to say so. */
@Serializable data class Can(val home: Boolean = false, val car: Boolean = false)

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
    /** A tile whose reading comes on its own (GET /api/hub/panels/read): [Reading]. */
    val read: Boolean = false,
    /** That reading's tool, run each time it is read… */
    val tool: String? = null,
    /** …or, for one Jarvis looks up, how many minutes old it may get before it is looked up again… */
    val every: Int? = null,
    /** …and whether it is kept fresh with Home closed. */
    val refresh: Boolean = false,
    /** The alerts set on it, a few words each ("above 200", "opens"). */
    val alerts: List<String> = emptyList(),
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
    /** A number read over the last day: its chart comes from GET /api/hub/panels/history. */
    val history: Boolean = false,
    /** "Alert me": its two states in words, a number to go above or below, or words (POST /api/hub/panels/alert). */
    val alert: AlertSpec? = null,
    /** Where it is (the car). */
    val map: LatLng? = null,
    /** A light's colour, when it can take one or a shade of white; never for a switch or an on/off light. */
    val color: LightColor? = null,
)

@Serializable data class AlertSpec(val kind: String, val on: String = "", val off: String = "", val unit: String = "", val value: Double? = null)

@Serializable data class LatLng(val lat: Double, val lng: Double)

@Serializable
data class Climate(
    val now: Double? = null,
    val target: Double? = null,
    val min: Double,
    val max: Double,
    val step: Double,
    val modes: List<String> = emptyList(),
    val mode: String = "",
    val fans: List<String> = emptyList(),
    val fan: String? = null,
)

@Serializable data class AlarmCode(val digits: Boolean = true, @kotlinx.serialization.SerialName("for") val forActions: List<String> = emptyList())

/**
 * A light's colour as Jarvis has it (src/shared/panels.ts): [wheel] if it takes a colour, at [hue]
 * (0-359) and [sat] (0-100); [white] if it takes a shade of white, at [kelvin], from [warm] to [cool].
 * Set with "color:<hue>,<sat>" and "kelvin:<k>"; [on], whether it is on now. Whole numbers, read as
 * any number so one sent as 120.0 never stops the panels being read.
 */
@Serializable
data class LightColor(
    val wheel: Boolean = false,
    val hue: Double = 0.0,
    val sat: Double = 0.0,
    val white: Boolean = false,
    val kelvin: Double = 4000.0,
    val warm: Double = 2000.0,
    val cool: Double = 6500.0,
    val on: Boolean = false,
)

@Serializable data class Level(val name: String, val value: Double, val min: Double, val max: Double, val step: Double, val unit: String = "")

@Serializable data class ActReply(val text: String = "")

/**
 * A tile's reading (GET /api/hub/panels/read, src/worker/lib/panel-tiles.ts): a line, figures, a
 * list, perhaps a chart, a map or a picture, and when it was looked up. [stale] (asked for the kept
 * one) means it is due to be looked up again; [pending], that it is being looked up. [failed] is the
 * app's own: the reading could not be had, and [text] says why.
 */
@Serializable
data class Reading(
    val text: String = "",
    val figures: List<Figure> = emptyList(),
    val list: List<String> = emptyList(),
    val image: String? = null,
    val chart: ReadingChart? = null,
    val map: LatLng? = null,
    val at: Long? = null,
    val source: String? = null,
    val stale: Boolean = false,
    val pending: Boolean = false,
    @kotlinx.serialization.Transient val failed: Boolean = false,
)

@Serializable data class ReadingChart(val title: String = "", val points: List<ChartPoint> = emptyList())

@Serializable data class ChartPoint(val x: String, val y: Double)

/** A number's last day (GET /api/hub/panels/history). */
@Serializable data class History(val points: List<HistoryPoint> = emptyList(), val unit: String = "")

@Serializable data class HistoryPoint(val t: Long, val v: Double)

@Serializable data class OrderReply(val order: List<String> = emptyList())
