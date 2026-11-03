package app.jarvis.glasses.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.jarvis.glasses.chat.Shown
import app.jarvis.glasses.hub.Hub
import app.jarvis.glasses.hub.MadePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What Jarvis put on the screen, drawn under its answer in Chat ([Shown]), as the web's stage
 * shows it (ui/Stage.ts there): a map or Street View to tap into Google Maps, a camera live for a
 * while, a picture to save, a page to open.
 */
@Composable
internal fun ShownCard(hub: Hub, s: Shown, key: String, person: Boolean) {
  when (s) {
    is Shown.Place -> PlaceCard(hub, s)
    is Shown.Camera -> CameraCard(hub, s, key, person)
    is Shown.Picture -> PictureCard(hub, s)
    is Shown.Page -> PageCard(hub, MadePage(s.id, s.title))
    Shown.Close -> Unit
  }
}

/**
 * Pictures already fetched, by address, so scrolling past one doesn't fetch it again: a camera's
 * last frame by its card. Forgotten with the chat, as the web's are, since a camera's or a made
 * picture shouldn't outlive it on a shared phone.
 */
internal object ShownPictures {
  val kept =
      object : android.util.LruCache<String, ImageBitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
      }

  /** When each camera's card stops refreshing ([CAMERA_LIVE_MS] after it came, or after Watch again). */
  val liveUntil = HashMap<String, Long>()

  /** A card drawn while its answer came ([from]) is now under the answer ([to]): it goes on as it was. */
  fun moved(from: String, to: String) {
    liveUntil.remove(from)?.let { liveUntil[to] = it }
    kept.remove("camera:$from")?.let { kept.put("camera:$to", it) }
  }

  fun forget() {
    kept.evictAll()
    liveUntil.clear()
  }
}

/** How long a camera keeps refreshing: the web's fuse for a picture in the chat (Stage.ts armDismiss). */
private const val CAMERA_LIVE_MS = 180_000L

/**
 * With a device token every request counts against the device's 20 a minute and 500 a day, which
 * a frame a second would spend at once: a frame every 10 s for a minute instead.
 */
private const val DEVICE_FRAME_MS = 10_000L
private const val DEVICE_LIVE_MS = 60_000L

/** A camera that fails this many frames in a row is given up on, as the web's is. */
private const val CAMERA_GIVE_UP = 3

private suspend fun Hub.bitmap(path: String): ImageBitmap {
  val bytes = picture(path)
  return withContext(Dispatchers.Default) {
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: throw IllegalStateException("not a picture")
  }
}

/** [bitmap], where a card that has gone (scrolled away, the app put down) is not a failure. */
private suspend fun Hub.fetched(path: String): Result<ImageBitmap> =
    runCatching { bitmap(path) }.onFailure { if (it is CancellationException) throw it }

/** A map or a street-level photo; a tap opens it in Google Maps, where it pans. */
@Composable
private fun PlaceCard(hub: Hub, s: Shown.Place) {
  val ctx = LocalContext.current
  var picture by remember(s.path) { mutableStateOf(ShownPictures.kept[s.path]) }
  var why by remember(s.path) { mutableStateOf<String?>(null) }
  LaunchedEffect(s.path) {
    if (picture != null) return@LaunchedEffect
    hub.fetched(s.path)
        .onSuccess {
          ShownPictures.kept.put(s.path, it)
          picture = it
        }
        .onFailure { why = "Could not load that view: ${it.message}" }
  }
  val open = { ctx.view(s.open, instead = s.openOnWeb) }
  Frame(
      picture,
      640f / Shown.HEIGHT,
      s.label,
      status = why ?: if (picture == null) "Getting the picture…" else null,
      onClick = open,
  ) {
    Act(if (s.street) "Open in Street View" else "Open in Maps", onClick = open)
  }
}

/**
 * A camera, a frame at a time as the web's stage has it: the next asked for once the last has
 * come, about one a second, a short wait after one that fails. Only while the card is on screen
 * and the app in front, and for three minutes; Watch again starts it again. With a device token,
 * slower and shorter ([DEVICE_FRAME_MS]); Jarvis saying "too many" stops it at once.
 */
@Composable
private fun CameraCard(hub: Hub, s: Shown.Camera, key: String, person: Boolean) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val frameKey = "camera:$key"
  var frame by remember(key) { mutableStateOf(ShownPictures.kept[frameKey]) }
  var status by remember(key) { mutableStateOf<String?>(if (frame == null) "Getting the picture…" else null) }
  var live by remember(key) { mutableStateOf(true) }
  var again by remember(key) { mutableIntStateOf(0) }
  LaunchedEffect(key, again) {
    val until = ShownPictures.liveUntil.getOrPut(key) { SystemClock.elapsedRealtime() + if (person) CAMERA_LIVE_MS else DEVICE_LIVE_MS }
    live = SystemClock.elapsedRealtime() < until
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      // Counted afresh each time the app comes back: being put down is not the camera failing.
      var failures = 0
      while (SystemClock.elapsedRealtime() < until && failures < CAMERA_GIVE_UP) {
        val started = SystemClock.elapsedRealtime()
        hub.fetched(s.path)
            .onSuccess {
              ShownPictures.kept.put(frameKey, it)
              frame = it
              status = null
              failures = 0
            }
            .onFailure {
              failures = if (it is Hub.TooMany) CAMERA_GIVE_UP else failures + 1
              status = when {
                it is Hub.TooMany -> "Paused: Jarvis says too many requests just now."
                failures < CAMERA_GIVE_UP -> "Slow to answer, trying again…"
                else -> "Camera unavailable: ${it.message}"
              }
            }
        if (failures >= CAMERA_GIVE_UP) break
        val every = if (person) 1100L else DEVICE_FRAME_MS
        delay(if (failures == 0) maxOf(500L, every - (SystemClock.elapsedRealtime() - started)) else 3000L)
      }
      live = false
    }
  }
  val watch: () -> Unit = {
    ShownPictures.liveUntil[key] = SystemClock.elapsedRealtime() + if (person) CAMERA_LIVE_MS else DEVICE_LIVE_MS
    status = if (frame == null) "Getting the picture…" else null
    again++
  }
  Frame(frame, 16f / 9f, s.label, status = status, onClick = if (live) null else watch) {
    if (live) Text("● Live", style = MaterialTheme.typography.labelSmall, color = Even.colors.danger)
    else Act("Watch again", onClick = watch)
  }
}

/** A picture Jarvis made: as it came, and Save puts it in the phone's Pictures. */
@Composable
private fun PictureCard(hub: Hub, s: Shown.Picture) {
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var picture by remember(s.path) { mutableStateOf(ShownPictures.kept[s.path]) }
  var why by remember(s.path) { mutableStateOf<String?>(null) }
  var saved by remember(s.path) { mutableStateOf<String?>(null) }
  LaunchedEffect(s.path) {
    if (picture != null) return@LaunchedEffect
    hub.fetched(s.path)
        .onSuccess {
          ShownPictures.kept.put(s.path, it)
          picture = it
        }
        .onFailure { why = "Could not load the picture: ${it.message}" }
  }
  val aspect = picture?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
  Frame(picture, aspect, s.label, status = why ?: saved ?: if (picture == null) "Getting the picture…" else null, onClick = null) {
    if (picture != null) {
      Act("Save") {
        scope.launch {
          saved = runCatching { savePicture(ctx, "jarvis-${s.id}", hub.picture(s.path)) }.fold({ "Saved to Pictures." }, { "Not saved: ${it.message}" })
        }
      }
    }
  }
}

/** A page Jarvis made, as a card like a link's: a tap asks Jarvis for a fresh link and opens it. */
@Composable
internal fun PageCard(hub: Hub, page: MadePage) {
  val h = Hark.colors
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var why by remember(page.id) { mutableStateOf<String?>(null) }
  Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
        Modifier.widthIn(max = 280.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(h.bubble)
            .clickable {
              why = null
              scope.launch { runCatching { hub.pageLink(page.id) }.onSuccess { ctx.view(it) }.onFailure { why = "Could not open it: ${it.message}" } }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Box(Modifier.size(28.dp).clip(CircleShape).background(h.bubbleYou), contentAlignment = Alignment.Center) {
        Text("P", style = MaterialTheme.typography.labelLarge, color = h.ink)
      }
      Column {
        Text(page.title, style = MaterialTheme.typography.bodyMedium, color = h.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("A page Jarvis made · Open", style = MaterialTheme.typography.bodySmall, color = h.dim, maxLines = 1)
      }
    }
    why?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = h.dim) }
  }
}

/** The picture in a rounded frame, held at its shape while it comes, with its caption and what it can do. */
@Composable
private fun Frame(
    picture: ImageBitmap?,
    aspect: Float,
    label: String,
    status: String?,
    onClick: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
  val h = Hark.colors
  Column(
      Modifier.padding(top = 6.dp).widthIn(max = 310.dp).fillMaxWidth().clip(Hark.bubble).background(h.bubble),
      verticalArrangement = Arrangement.spacedBy(0.dp),
  ) {
    val shape = Modifier.fillMaxWidth().aspectRatio(aspect).background(h.bubbleYou).let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Box(shape, contentAlignment = Alignment.Center) {
      if (picture != null) Image(picture, contentDescription = label.ifEmpty { null }, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(aspect))
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodyMedium, color = h.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
      status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = h.dim) }
      actions()
    }
  }
}

@Composable
private fun Act(label: String, onClick: () -> Unit) {
  Text(label, style = MaterialTheme.typography.labelLarge, color = Even.colors.tintOpen, modifier = Modifier.clickable(onClick = onClick))
}

/** Into the phone's Pictures/Jarvis, as its own file: no permission is needed for one's own. */
private suspend fun savePicture(ctx: Context, name: String, bytes: ByteArray) =
    withContext(Dispatchers.IO) {
      val png = bytes.size > 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
      val webp = bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
      val (ext, mime) = when {
        png -> "png" to "image/png"
        webp -> "webp" to "image/webp"
        else -> "jpg" to "image/jpeg"
      }
      val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.$ext")
        put(MediaStore.Images.Media.MIME_TYPE, mime)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Jarvis")
      }
      val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Pictures isn't available")
      ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    }
