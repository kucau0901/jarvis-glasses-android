package app.jarvis.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import app.jarvis.link.Thing
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

/**
 * Jarvis on the watch face and in the Tiles: told to draw again whenever what they show may have
 * changed ([refresh]), and kept fresh through the phone, at most once a minute ([freshen]), so
 * that a redraw doesn't ask the phone again and again.
 */
object Faces {
  private val complications = listOf(TalkComplication::class.java, ApprovalsComplication::class.java, JobsComplication::class.java, ThingComplication::class.java)
  private var freshened = 0L

  fun refresh(context: Context) {
    runCatching { TileService.getUpdater(context).requestUpdate(JarvisTile::class.java) }
    for (c in complications) runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, c)).requestUpdateAll() }
  }

  /** Home's things and the counts fetched again, unless they were a moment ago; [refresh] follows. */
  fun freshen(app: WatchApp) {
    val now = SystemClock.elapsedRealtime()
    if (freshened != 0L && now - freshened < FRESH_MS) return
    freshened = now
    app.scope.launch {
      app.wrist.fetchCounts()
      app.wrist.fetchHome()
    }
  }

  /**
   * Opens Jarvis on the watch: to talk, or at [open] ([MainActivity.OPEN]'s screens). Each with an
   * address of its own: with Jarvis already open, Android takes an intent that differs from the one
   * that opened it only in its extras for the same, and brings Jarvis forward without passing it on.
   */
  fun intent(context: Context, open: String? = null): Intent =
      Intent(context, MainActivity::class.java)
          .setData(Uri.fromParts("jarvis", open ?: "talk", null))
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          .apply { open?.let { putExtra(MainActivity.OPEN, it) } }

  /**
   * A tap that opens Jarvis at [open]. [code] tells one complication's apart from another's: two with
   * the same code are one PendingIntent, the later's extras written over the earlier's.
   */
  fun tap(context: Context, open: String?, code: Int): PendingIntent =
      PendingIntent.getActivity(context, code, intent(context, open), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

  private const val FRESH_MS = 60_000L
}

/**
 * The Tile: a round Talk button, the things pinned to it (a tap presses a thing's first button, or
 * opens it when it has none), and the approvals waiting. A press is done through the phone after
 * the Tile is drawn, which draws again once Home has been fetched.
 */
class JarvisTile : TileService() {
  override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
    val app = application as WatchApp
    val clicked = requestParams.currentState.lastClickableId
    val id = clicked.removePrefix(PRESS).takeIf { clicked.startsWith(PRESS) }
    if (id != null) {
      val thing = app.wrist.things.value.firstOrNull { it.id == id }
      val b = thing?.buttons?.firstOrNull()
      // One press at a time per thing: a second tap while the first is on its way would undo it.
      if (thing != null && b != null && pressing.add(id)) {
        app.scope.launch {
          try {
            app.wrist.act(thing, b.action)
          } finally {
            pressing.remove(id)
            // Drawn again however it went: the chip's "…" is never left behind.
            Faces.refresh(app)
          }
        }
      } else if (id !in pressing) Faces.refresh(app)
    } else Faces.freshen(app)
    return CallbackToFutureAdapter.getFuture { done ->
      done.set(
          TileBuilders.Tile.Builder()
              .setResourcesVersion(RESOURCES)
              .setFreshnessIntervalMillis(FRESHNESS_MS)
              .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(app.wrist.pinnedThings(), app.wrist.counts.value.approvals, pressing)))
              .build(),
      )
      "jarvis tile"
    }
  }

  override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
      CallbackToFutureAdapter.getFuture { done ->
        done.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())
        "jarvis tile resources"
      }

  private fun layout(things: List<Thing>, approvals: Int, pressed: Set<String>): LayoutElementBuilders.LayoutElement {
    val col = LayoutElementBuilders.Column.Builder().setWidth(expand()).setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
    col.addContent(text("JARVIS", 12f, DIM))
    col.addContent(gap(6f))
    col.addContent(talkButton())
    for (t in things) {
      col.addContent(gap(6f))
      col.addContent(thingChip(t, busy = t.id in pressed))
    }
    if (things.isEmpty()) {
      col.addContent(gap(6f))
      col.addContent(text("Pin things from Home in Jarvis", 11f, DIM))
    }
    if (approvals > 0) {
      col.addContent(gap(6f))
      col.addContent(
          LayoutElementBuilders.Box.Builder()
              .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open("approvals", MainActivity.APPROVALS)).build())
              .addContent(text(if (approvals == 1) "1 to approve" else "$approvals to approve", 13f, WARM))
              .build(),
      )
    }
    return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand()).addContent(col.build()).build()
  }

  private fun talkButton(): LayoutElementBuilders.LayoutElement =
      LayoutElementBuilders.Box.Builder()
          .setWidth(dp(52f))
          .setHeight(dp(52f))
          .setModifiers(
              ModifiersBuilders.Modifiers.Builder()
                  .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(TILE)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(26f)).build()).build())
                  .setClickable(open("talk", null))
                  .build(),
          )
          .addContent(text("Talk", 13f, INK))
          .build()

  private fun thingChip(t: Thing, busy: Boolean): LayoutElementBuilders.LayoutElement {
    // A thing with a button is pressed from here; one without opens in Jarvis.
    val click =
        if (t.buttons.isNotEmpty()) ModifiersBuilders.Clickable.Builder().setId(PRESS + t.id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()
        else open("thing:${t.id}", MainActivity.THING + t.id)
    val status = if (busy) "…" else t.status
    val line = LayoutElementBuilders.Row.Builder().setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
    if (t.active) {
      line.addContent(
          LayoutElementBuilders.Box.Builder()
              .setWidth(dp(6f))
              .setHeight(dp(6f))
              .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(ModifiersBuilders.Background.Builder().setColor(argb(ON)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(3f)).build()).build()).build())
              .build(),
      )
      line.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
    }
    line.addContent(text(t.label, 13f, INK))
    if (status.isNotBlank()) {
      line.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
      line.addContent(text(status, 12f, DIM))
    }
    return LayoutElementBuilders.Box.Builder()
        .setWidth(dp(150f))
        .setHeight(dp(34f))
        .setModifiers(
            ModifiersBuilders.Modifiers.Builder()
                .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(TILE)).setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(17f)).build()).build())
                .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(12f)).setEnd(dp(12f)).build())
                .setClickable(click)
                .build(),
        )
        .addContent(line.build())
        .build()
  }

  private fun open(id: String, open: String?): ModifiersBuilders.Clickable {
    // Through OpenActivity: a Tile's launch can carry extras but no address of its own (Faces.intent).
    val activity = ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(OpenActivity::class.java.name)
    open?.let { activity.addKeyToExtraMapping(MainActivity.OPEN, ActionBuilders.stringExtra(it)) }
    return ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build()).build()
  }

  private fun text(s: String, size: Float, color: Int): LayoutElementBuilders.LayoutElement =
      LayoutElementBuilders.Text.Builder()
          .setText(s)
          .setMaxLines(1)
          .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
          .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
          .build()

  private fun gap(h: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(h)).build()

  private companion object {
    /** The things whose press is on its way, by id. */
    val pressing: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    const val RESOURCES = "1"
    const val PRESS = "press:"
    /** Drawn again at least this often, for what changed at home meanwhile. */
    const val FRESHNESS_MS = 15 * 60_000L
    // DarkEven's ink, dim, tile, on and warm.
    const val INK = 0xFFEDEDED.toInt()
    const val DIM = 0xFF8C8C8C.toInt()
    const val TILE = 0xFF1B1B1B.toInt()
    const val ON = 0xFF4CC46E.toInt()
    const val WARM = 0xFFFBB01B.toInt()
  }
}

/** The Tile's way into Jarvis: passes its [MainActivity.OPEN] on with an address of its own ([Faces.intent]), and is gone. */
class OpenActivity : android.app.Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    startActivity(Faces.intent(this, intent.getStringExtra(MainActivity.OPEN)))
    finish()
  }
}

/** Added to a complication's tap code for its preview's. */
private const val PREVIEW = 100

/** A complication of Jarvis's: what it shows from what the watch keeps, the phone asked again at most once a minute. */
abstract class JarvisComplication : SuspendingComplicationDataSourceService() {
  final override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    val app = application as WatchApp
    Faces.freshen(app)
    return data(app, request.complicationType)
  }

  final override fun getPreviewData(type: ComplicationType): ComplicationData? = preview(type)

  abstract fun data(app: WatchApp, type: ComplicationType): ComplicationData?

  abstract fun preview(type: ComplicationType): ComplicationData?

  /** A preview's taps, never fired, under codes of their own so they don't change a live one's ([Faces.tap]). */
  protected fun previewShort(text: String, title: String, open: String?, code: Int, about: String) = short(text, title, open, code + PREVIEW, about)

  protected fun short(text: String, title: String, open: String?, code: Int, about: String): ShortTextComplicationData =
      ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), PlainComplicationText.Builder(about).build())
          .setTitle(PlainComplicationText.Builder(title).build())
          .setTapAction(Faces.tap(this, open, code))
          .build()
}

/** Talk to Jarvis from the watch face: a microphone, or the word. */
class TalkComplication : JarvisComplication() {
  override fun data(app: WatchApp, type: ComplicationType): ComplicationData? = talk(type)

  override fun preview(type: ComplicationType): ComplicationData? = talk(type, code = 10 + PREVIEW)

  private fun talk(type: ComplicationType, code: Int = 10): ComplicationData? =
      when (type) {
        ComplicationType.MONOCHROMATIC_IMAGE ->
            MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_mic)).build(), PlainComplicationText.Builder("Talk to Jarvis").build())
                .setTapAction(Faces.tap(this, null, code))
                .build()
        ComplicationType.SHORT_TEXT -> short("Talk", "Jarvis", null, code, "Talk to Jarvis")
        else -> null
      }
}

/** How many of Jarvis's approvals are waiting for an OK; a tap opens them. */
class ApprovalsComplication : JarvisComplication() {
  override fun data(app: WatchApp, type: ComplicationType): ComplicationData? =
      if (type == ComplicationType.SHORT_TEXT) app.wrist.counts.value.approvals.let { short("$it", "OK?", MainActivity.APPROVALS, 11, "$it approvals waiting") } else null

  override fun preview(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) previewShort("2", "OK?", MainActivity.APPROVALS, 11, "Approvals waiting") else null
}

/** How many of Jarvis's jobs are running. */
class JobsComplication : JarvisComplication() {
  override fun data(app: WatchApp, type: ComplicationType): ComplicationData? =
      if (type == ComplicationType.SHORT_TEXT) app.wrist.counts.value.jobs.let { short("$it", "Jobs", MainActivity.MENU, 12, "$it jobs running") } else null

  override fun preview(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) previewShort("1", "Jobs", MainActivity.MENU, 12, "Jobs running") else null
}

/** The first thing pinned to the Tile: its state, under its name; a tap opens it. */
class ThingComplication : JarvisComplication() {
  override fun data(app: WatchApp, type: ComplicationType): ComplicationData? {
    if (type != ComplicationType.SHORT_TEXT) return null
    val t = app.wrist.pinnedThings().firstOrNull() ?: return short("—", "Jarvis", MainActivity.HOME, 13, "Pin a thing in Jarvis")
    return short(t.status.ifBlank { if (t.active) "On" else "—" }.take(7), t.label.take(7), MainActivity.THING + t.id, 13, "${t.label}: ${t.status}")
  }

  override fun preview(type: ComplicationType): ComplicationData? = if (type == ComplicationType.SHORT_TEXT) previewShort("24°", "Bedroom", MainActivity.HOME, 13, "A pinned thing") else null
}
