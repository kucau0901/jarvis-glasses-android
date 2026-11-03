package app.jarvis.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The watch asking Jarvis through the phone app, over Wear OS's Data Layer. The watch hears the
 * question itself (its own recogniser) and sends the words ([Ask]); the phone asks Jarvis with its
 * own sign-in, as the watch's screen ([Ask.origin], labelled "Watch"), keeps the exchange in Chat,
 * and streams Jarvis's events back as [Reply]s: progress, the answer, its voice a sentence at a
 * time, then [Reply.Done] (or [Reply.Failed] instead). [Hello] asks the phone whether it can answer
 * at all.
 *
 * Both apps are `app.jarvis.glasses`, signed with the same key: the Data Layer joins only those.
 * The phone app says it is there with the [PHONE] capability (res/values/wear.xml).
 *
 * Beyond questions, the watch [Fetch]es what it shows on the wrist (Home's things, approvals, a
 * glance of counts) and acts on it ([Act], [Approve]); each is answered with one [Reply], or
 * [Reply.Failed]. Home and approvals are a person's: a phone signed in with a device token is
 * refused them.
 */
object WatchLink {
  /** The capability the phone app advertises, for the watch to find it by. */
  const val PHONE = "jarvis_phone"

  /** Every path starts with it, so the phone's listener takes only these. */
  const val PREFIX = "/jarvis/"
  const val HELLO = "/jarvis/hello"
  const val ASK = "/jarvis/ask"
  const val STOP = "/jarvis/stop"
  const val REPLY = "/jarvis/reply"
  const val FETCH = "/jarvis/fetch"
  const val ACT = "/jarvis/act"
  const val APPROVE = "/jarvis/approve"

  private val json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    encodeDefaults = true
  }

  fun encode(m: Hello): ByteArray = json.encodeToString(Hello.serializer(), m).toByteArray()

  fun encode(m: Ask): ByteArray = json.encodeToString(Ask.serializer(), m).toByteArray()

  fun encode(m: Stop): ByteArray = json.encodeToString(Stop.serializer(), m).toByteArray()

  fun encode(m: Fetch): ByteArray = json.encodeToString(Fetch.serializer(), m).toByteArray()

  fun encode(m: Act): ByteArray = json.encodeToString(Act.serializer(), m).toByteArray()

  fun encode(m: Approve): ByteArray = json.encodeToString(Approve.serializer(), m).toByteArray()

  /** [m]'s path and bytes, as the watch sends it. */
  fun message(m: FromWatch): Pair<String, ByteArray> =
      when (m) {
        is Hello -> HELLO to encode(m)
        is Ask -> ASK to encode(m)
        is Stop -> STOP to encode(m)
        is Fetch -> FETCH to encode(m)
        is Act -> ACT to encode(m)
        is Approve -> APPROVE to encode(m)
      }

  /** A [Reply.Home] kept on the watch, for its Tile and complications between fetches. */
  fun encodeThings(things: List<Thing>): String = json.encodeToString(ListSerializer(Thing.serializer()), things)

  fun decodeThings(text: String): List<Thing>? = read { json.decodeFromString(ListSerializer(Thing.serializer()), text) }

  fun encode(m: Reply): ByteArray = json.encodeToString(Reply.serializer(), m).toByteArray()

  /** What the watch sent, by its path; null for anything this version doesn't read. */
  fun fromWatch(path: String, data: ByteArray): FromWatch? =
      read {
        when (path) {
          HELLO -> json.decodeFromString(Hello.serializer(), data.decodeToString())
          ASK -> json.decodeFromString(Ask.serializer(), data.decodeToString())
          STOP -> json.decodeFromString(Stop.serializer(), data.decodeToString())
          FETCH -> json.decodeFromString(Fetch.serializer(), data.decodeToString())
          ACT -> json.decodeFromString(Act.serializer(), data.decodeToString())
          APPROVE -> json.decodeFromString(Approve.serializer(), data.decodeToString())
          else -> null
        }
      }

  /** What the phone sent back; null for anything this version doesn't read (a newer phone's kind of reply, say). */
  fun reply(path: String, data: ByteArray): Reply? =
      if (path != REPLY) null else read { json.decodeFromString(Reply.serializer(), data.decodeToString()) }

  private inline fun <T> read(f: () -> T?): T? =
      try {
        f()
      } catch (_: SerializationException) {
        null
      } catch (_: IllegalArgumentException) {
        null
      }
}

/** Something the watch sends: each has the [id] its replies come back under. */
sealed interface FromWatch {
  val id: String
}

/** Can the phone answer? It replies [Reply.Ready]. */
@Serializable data class Hello(override val id: String) : FromWatch

/**
 * A question heard on the watch. [origin] is the watch's own id among the person's screens
 * (`w_…`). [speak]: answered aloud, the voice sent back with it; otherwise in writing only, to fit
 * about [chars] characters on the watch's round screen. [openMic]: a follow-up, heard by the
 * microphone opening again after an answer, so Jarvis answers only what was said to it.
 */
@Serializable
data class Ask(
    override val id: String,
    val text: String,
    val origin: String,
    val speak: Boolean = true,
    val chars: Int = 300,
    val openMic: Boolean = false,
) : FromWatch

/** The question [id] is no longer wanted: the phone hangs up on Jarvis. */
@Serializable data class Stop(override val id: String) : FromWatch

/** What the wrist shows, fetched now: [HOME] ([Reply.Home]), [APPROVALS] ([Reply.Approvals]) or [GLANCE] ([Reply.Glance]). */
@Serializable
data class Fetch(override val id: String, val what: String) : FromWatch {
  companion object {
    const val HOME = "home"
    const val APPROVALS = "approvals"
    const val GLANCE = "glance"
  }
}

/**
 * A thing's button pressed, or its dial set: [action] as [Thing.buttons] or [Dial.action] has it.
 * [member]: the button is one of its room's ([Thing.members]), by that member's key.
 */
@Serializable
data class Act(override val id: String, val panel: String, val key: String, val action: String, val value: String? = null, val member: String? = null) : FromWatch

/** An approval answered: yes to do it, or no. */
@Serializable data class Approve(override val id: String, val proposal: String, val yes: Boolean) : FromWatch

/**
 * One of Home's things, as the wrist has room for it: its name, a line of state, whether it is on,
 * its buttons (those needing nothing typed or chosen), a [dial] when it has a level or a set point
 * the bezel can turn, and for a room, its [members]: the lights and the like in it, each with its
 * own buttons, as the phone's sheet lists them.
 */
@Serializable
data class Thing(
    val panel: String,
    val key: String,
    val label: String,
    val icon: String = "dot",
    val status: String = "",
    val active: Boolean = false,
    val buttons: List<Button> = emptyList(),
    val dial: Dial? = null,
    val members: List<Member> = emptyList(),
    /** A room's things beyond [members], left on the phone for room. */
    val moreMembers: Int = 0,
) {
  /**
   * Its own name among the watch's things: its panel, key and name, as the TV's thingKey has it. A
   * panel's key may repeat across panels, and within one: a car's or a family's tiles share theirs.
   */
  val id: String
    get() = "$panel/$key/$label"
}

@Serializable data class Button(val action: String, val label: String)

/** One of a room's things: pressed through the room, by its [key] ([Act.member]). */
@Serializable
data class Member(val key: String, val label: String, val status: String = "", val active: Boolean = false, val buttons: List<Button> = emptyList())

/**
 * A level or a set point: [value] between [min] and [max] in [step]s. Set by acting [action] with no
 * value: the number goes after it ("level:40", "temp:24"), as the phone's own controls send it.
 */
@Serializable
data class Dial(val name: String, val value: Double, val min: Double, val max: Double, val step: Double, val unit: String = "", val action: String) {
  /** [v] moved [by] steps, kept between [min] and [max] and on a step. */
  fun stepped(v: Double, by: Int): Double {
    val s = step.takeIf { it > 0 } ?: 1.0
    val n = Math.round((v - min) / s) + by
    return (min + n * s).coerceIn(min, max)
  }

  /** [v] as the dial sends it: whole when its steps are, else to their decimals ("24", "22.5"). */
  fun words(v: Double): String {
    val s = step.takeIf { it > 0 } ?: 1.0
    if (s % 1.0 == 0.0) return Math.round(v).toString()
    val places = s.toBigDecimal().stripTrailingZeros().scale().coerceIn(1, 3)
    return v.toBigDecimal().setScale(places, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
  }

  /** The action that sets it to [v]. */
  fun actionFor(v: Double): String = action + words(v)
}

/** Something Jarvis wants an OK for: what it says it will do, why, and the word for doing it. */
@Serializable data class Approval(val id: String, val says: String, val why: String = "", val verb: String = "Approve", val risk: String = "")

/** What the phone sends back, under the [id] of what the watch sent. */
@Serializable
sealed interface Reply {
  val id: String

  /** Whether the phone can ask Jarvis: signed in, and as whom ("" for a device token). */
  @Serializable
  @SerialName("ready")
  data class Ready(override val id: String, val signedIn: Boolean, val name: String = "") : Reply

  /** What Jarvis is doing on the way to the answer ("Looking at the calendar"). */
  @Serializable @SerialName("progress") data class Progress(override val id: String, val text: String) : Reply

  /**
   * The answer's words. [ok] false for a failure, which is still shown. [notForMe]: nothing heard
   * was for Jarvis (an older Jarvis never says so).
   */
  @Serializable
  @SerialName("answer")
  data class Answer(override val id: String, val text: String, val ok: Boolean = true, val notForMe: Boolean = false) : Reply

  /** Sentence [seq] of the spoken answer: base64 audio as Jarvis sent it ([mime], mp3). */
  @Serializable
  @SerialName("audio")
  data class Audio(override val id: String, val seq: Int, val last: Boolean, val mime: String, val base64: String) : Reply

  @Serializable @SerialName("home") data class Home(override val id: String, val things: List<Thing>) : Reply

  @Serializable @SerialName("approvals") data class Approvals(override val id: String, val open: List<Approval>) : Reply

  /** Counts for the watch face: approvals waiting, jobs running. */
  @Serializable @SerialName("glance") data class Glance(override val id: String, val approvals: Int, val jobs: Int) : Reply

  /** An [Act] or [Approve] done: what Jarvis said of it, when it said anything. */
  @Serializable @SerialName("acted") data class Acted(override val id: String, val text: String = "") : Reply

  /** Jarvis has said all it will for the question: after its answer and the last of its voice. */
  @Serializable @SerialName("done") data class Done(override val id: String) : Reply

  /** The phone couldn't ask: not signed in, Jarvis out of reach, a refusal. [text] says why, to show. */
  @Serializable @SerialName("failed") data class Failed(override val id: String, val text: String) : Reply
}
