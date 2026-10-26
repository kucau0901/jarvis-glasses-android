package app.jarvis.glasses.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.hub.AlertSpec
import app.jarvis.glasses.hub.History
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.PanelAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What an opened tile shows beyond words and buttons, as on Jarvis's web app: a number's last day as
// a line, where the car is as a map, "Alert me", and buttons that take a slider's number or typed
// words. Whatever acts on the house, the car or the account is a SafePill: a quick tap only.

/** A chart's last day is fetched again after this long, as on the web. */
private const val HISTORY_MS = 5 * 60_000L

/** Charts and maps already fetched while the app runs, so a sheet opened again draws at once: a few maps only, each a bitmap of some megabytes. */
private object Kept {
  val histories = HashMap<String, Pair<Long, History>>()
  val pictures =
      object : android.util.LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
      }
}

/** Opens [uri] in whatever app takes it, or [instead] if none does (a phone with no maps app), never crashing. */
internal fun android.content.Context.view(uri: String, instead: String? = null) {
  runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
      .recoverCatching { if (instead != null) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(instead))) else throw it }
}

/** A line through [values], oldest first: the latest large, the low and high beside it, its two ends labelled. */
@Composable
internal fun LineChart(values: List<Double>, unit: String, title: String, from: String, to: String) {
  val c = Even.colors
  val line = c.tintOpen
  Column(Modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.labelSmall, color = c.dim)
      Text(chartNumber(values.last()) + unit, style = MaterialTheme.typography.titleMedium)
      Text("low ${chartNumber(values.min())} · high ${chartNumber(values.max())}", style = MaterialTheme.typography.labelSmall, color = c.dim)
    }
    Canvas(Modifier.fillMaxWidth().height(90.dp).padding(top = 4.dp)) {
      val at = chartLine(values, size.width, size.height, 6.dp.toPx())
      val path = Path().apply { at.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) } }
      val area = Path().apply {
        addPath(path)
        lineTo(at.last().first, size.height)
        lineTo(at.first().first, size.height)
        close()
      }
      drawPath(area, line.copy(alpha = 0.14f))
      drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    Row(Modifier.fillMaxWidth()) {
      Text(from, style = MaterialTheme.typography.labelSmall, color = c.dim)
      Spacer(Modifier.weight(1f))
      Text(to, style = MaterialTheme.typography.labelSmall, color = c.dim)
    }
  }
}

/** A number's last day from the house, fetched when its sheet opens. */
@Composable
internal fun HistoryChart(hub: Hub, panel: String, key: String) {
  val c = Even.colors
  val at = "$panel\n$key"
  var history by remember(at) { mutableStateOf(Kept.histories[at]?.takeIf { System.currentTimeMillis() - it.first < HISTORY_MS }?.second) }
  var why by remember(at) { mutableStateOf<String?>(null) }
  LaunchedEffect(at) {
    if (history != null) return@LaunchedEffect
    runCatching { hub.history(panel, key) }
        .onSuccess {
          Kept.histories[at] = System.currentTimeMillis() to it
          history = it
        }
        .onFailure { why = "No chart just now: ${it.message}" }
  }
  val h = history
  when {
    why != null -> Text(why!!, style = MaterialTheme.typography.bodySmall, color = c.dim)
    h == null -> Text("…", style = MaterialTheme.typography.bodySmall, color = c.dim)
    h.points.size < 2 -> Text("Not enough readings yet for a chart.", style = MaterialTheme.typography.bodySmall, color = c.dim)
    else -> LineChart(h.points.map { it.v }, if (h.unit.isEmpty()) "" else " ${h.unit}", "Last 24 hours", clock(h.points.first().t), "now")
  }
}

private fun clock(t: Long): String = DateTimeFormatter.ofPattern("h:mm a").format(Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()))

/** Where something is, as a map from Jarvis; a tap opens it in Maps. Nothing shows if none can be had. */
@Composable
internal fun MapPicture(hub: Hub, lat: Double, lng: Double) {
  val c = Even.colors
  val context = LocalContext.current
  val path = Hub.mapPath(lat, lng)
  // Not keyed by place: as the car moves, the last map stays until the new one comes, so the sheet doesn't jump.
  var picture by remember { mutableStateOf(Kept.pictures[path]) }
  LaunchedEffect(path) {
    Kept.pictures[path]?.let {
      picture = it
      return@LaunchedEffect
    }
    runCatching { hub.map(lat, lng) }
        .mapCatching { bytes -> withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.asImageBitmap() } }
        .onSuccess {
          Kept.pictures.put(path, it)
          picture = it
        }
  }
  val open = { context.view("geo:$lat,$lng?q=$lat,$lng", instead = "https://maps.google.com/?q=$lat,$lng") }
  picture?.let {
    Image(it, contentDescription = "Map", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(640f / 300f).clip(RoundedCornerShape(12.dp)).clickable(onClick = open))
  }
  Text("Open in Maps", style = MaterialTheme.typography.labelSmall, color = c.tintOpen, modifier = Modifier.clickable(onClick = open))
}

/** A reading's picture, sent as a data: URL (a camera's, say). */
@Composable
internal fun ReadingPicture(image: String) {
  val picture = remember(image) {
    runCatching {
          val bytes = Base64.decode(image.substringAfter("base64,"), Base64.DEFAULT)
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!.asImageBitmap()
        }
        .getOrNull()
  }
  picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) }
}

/**
 * "Alert me" on a thing: when it turns one way or the other, goes above or below a number, or, for a
 * reading Jarvis looks up, when what is typed holds. Jarvis makes it a routine and says so.
 */
@Composable
internal fun AlertMe(spec: AlertSpec, send: suspend (state: String?, value: Double?, condition: String?) -> String) {
  val c = Even.colors
  val scope = rememberCoroutineScope()
  var reply by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  var typed by remember { mutableStateOf("") }
  fun go(state: String?, value: Double?, condition: String?) {
    if (busy) return
    busy = true
    scope.launch {
      reply =
          runCatching { send(state, value, condition) }
              .onSuccess { typed = "" } // as the web: what was sent goes, so a second tap doesn't make a second alert
              .getOrElse { it.message ?: "That didn't work" }
      busy = false
    }
  }
  when (spec.kind) {
    "state" ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
          Text("Alert me when it", style = MaterialTheme.typography.bodySmall, color = c.dim)
          SafePill(pretty(spec.on), enabled = !busy) { go("on", null, null) }
          SafePill(pretty(spec.off), enabled = !busy) { go("off", null, null) }
        }
    "number" -> {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Alert me at", style = MaterialTheme.typography.bodySmall, color = c.dim)
        OutlinedTextField(
            typed,
            { typed = it.take(12) },
            placeholder = { Text(spec.value?.let(::chartNumber) ?: "") },
            singleLine = true,
            // Text, not the number pad: many have no minus, and "below -5" is a fair alert.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.width(110.dp),
        )
        Text(spec.unit, style = MaterialTheme.typography.bodySmall, color = c.dim)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, way) in listOf("When above" to "above", "When below" to "below")) {
          SafePill(label, enabled = !busy) {
            val v = parseNumber(typed)
            if (v == null) reply = "Type the number first." else go(way, v, null)
          }
        }
      }
    }
    else ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(typed, { typed = it.take(500) }, placeholder = { Text("When? e.g. above 150") }, singleLine = true, modifier = Modifier.weight(1f))
          SafePill("Alert me", enabled = !busy) { if (typed.isNotBlank()) go(null, null, typed.trim()) }
        }
  }
  if (reply.isNotEmpty()) Text(reply, style = MaterialTheme.typography.bodySmall, color = c.dim)
}

/**
 * A button that takes a number or words first. A slider only chooses: unlike the web, which sends
 * when it is let go, the number goes only with a tap of its button, so a slide while scrolling the
 * sheet never works the house or the car.
 */
@Composable
internal fun InputAction(action: PanelAction, enabled: Boolean, send: (String) -> Unit) {
  val c = Even.colors
  val input = action.input ?: return
  if (input.kind == "slider") {
    val min = input.min ?: 0.0
    val max = input.max ?: 100.0
    val step = input.step?.takeIf { it > 0 } ?: 1.0
    var v by remember(action.action, min, max, step) { mutableFloatStateOf(sliderStart(min, max, step).toFloat()) }
    // What Jarvis will set: on a step from min, with the step's decimals.
    val chosen = stepWords(onStep(v.toDouble(), min, max, step), step)
    Text(action.label, style = MaterialTheme.typography.labelSmall, color = c.dim)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Slider(value = v, onValueChange = { v = it }, valueRange = min.toFloat()..sliderTop(min, max, step).toFloat(), steps = sliderSteps(min, max, step), modifier = Modifier.weight(1f))
      key(action.action) { SafePill("Set $chosen", enabled = enabled) { send(chosen) } }
    }
  } else {
    var typed by remember(action.action) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(typed, { typed = it.take(500) }, placeholder = { Text(input.hint ?: action.label) }, singleLine = true, modifier = Modifier.weight(1f))
      key(action.action) {
        SafePill(action.label, enabled = enabled) {
          if (typed.isNotBlank()) {
            send(typed.trim())
            typed = ""
          }
        }
      }
    }
  }
}

/** Choices as pills, the chosen one filled, as the web's aircond modes and fan speeds. */
@Composable
internal fun Choices(title: String, options: List<String>, chosen: String?, busy: (String) -> Boolean = { false }, pick: (String) -> Unit) {
  val c = Even.colors
  Text(title, style = MaterialTheme.typography.labelSmall, color = c.dim)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    for (o in options) key(o) { SafePill(pretty(o), enabled = !busy(o), selected = o == chosen) { pick(o) } }
  }
}

/** A panel's to-dos in full: tick them, add one, clear the ticked. */
@Composable
internal fun NotesSheet(title: String, notes: List<app.jarvis.glasses.hub.PanelRow>, tick: (String) -> Unit, add: (String) -> Unit, clear: () -> Unit) {
  Text(title, style = MaterialTheme.typography.titleMedium)
  for (n in notes) NoteLine(n) { tick(n.key) }
  var typed by remember { mutableStateOf("") }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(typed, { typed = it.take(120) }, placeholder = { Text("Add a to-do") }, singleLine = true, modifier = Modifier.weight(1f))
    SafePill("Add") {
      if (typed.isNotBlank()) {
        add(typed.trim())
        typed = ""
      }
    }
  }
  if (notes.any { it.done == true }) SafePill("Clear ticked") { clear() }
}
