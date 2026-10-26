package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.hub.Panel

// Holding a tile to move it, as Jarvis's web app does (src/app/ui/HomeWall.ts): hold still for the
// long-press time and it lifts; a finger that moves first is scrolling the page. While it is held a
// copy follows the finger, its place shows as a dashed gap, and the others make way.

/** What is held: a panel (by its title, [thing] null) or one of its things, and where the finger is. */
@Stable
internal class Held(
    val cell: String,
    val panel: String,
    val thing: String?,
    /** Where in the tile it was held, so the copy stays under the finger at that spot. */
    val grab: Offset,
    val size: IntSize,
    /** The panels as they were when it was lifted, to go back to if Jarvis can't keep the move. */
    val before: List<Panel>,
    finger: Offset,
) {
  /** The finger, in window coordinates. */
  var finger by mutableStateOf(finger)
}

/** The Home grid's moving state: what is held, and where each tile is on screen to move it onto. */
@Stable
internal class Mover {
  var held by mutableStateOf<Held?>(null)
  val places = HashMap<String, Rect>()

  /** The tile under [finger] other than the held one, if any. */
  fun under(finger: Offset): String? = places.entries.firstOrNull { (id, r) -> id != held?.cell && r.contains(finger) }?.key
}

/** A layout's coordinates as last placed, kept outside Compose's state (they change every frame). */
internal class Place {
  var at: LayoutCoordinates? = null
}

/**
 * Tap, scroll or hold. A quick lift is [onTap]; moving past the touch slop first is a scroll and is
 * left alone; holding still for the long-press time is [onHold] (where in the tile, and the finger in
 * the window), then, if it lifted the tile, each move is [onMove] until the finger lifts or the
 * gesture is cut, [onDrop]. A hold that lifts nothing (another finger holds a tile already) ends there.
 * A press a button inside already took is not looked at, so a button is never a handle.
 */
internal suspend fun PointerInputScope.holdToMove(
    place: Place,
    onTap: () -> Unit,
    onHold: (grab: Offset, finger: Offset, size: IntSize) -> Boolean,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
) = awaitEachGesture {
  val down = awaitFirstDown()
  var at = down.position
  var tapped = false
  val held =
      withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
          val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
          if (change.isConsumed) break
          if (!change.pressed) {
            change.consume()
            tapped = true
            break
          }
          if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
          at = change.position
        }
        false
      } ?: true
  if (!held) {
    if (tapped) onTap()
    return@awaitEachGesture
  }
  val coords = place.at ?: return@awaitEachGesture
  if (!onHold(at, coords.localToWindow(at), coords.size)) return@awaitEachGesture
  try {
    while (true) {
      val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
      change.consume()
      if (!change.pressed) break
      place.at?.let { onMove(it.localToWindow(change.position)) }
    }
  } finally {
    onDrop()
  }
}

/**
 * A thin outlined pill button, as the Even app's, that acts only on a quick tap: a long hold, a
 * slide (even one that comes back), or a scroll of the page never presses it. For buttons that open
 * a gate or a lock. It does what it offered when the finger came down, even if the panels are read
 * again meanwhile; and callers key each pill by its action, so should another action take its place
 * (Lock become Unlock) it is a new button and the press under way ends there.
 */
@Composable
internal fun SafePill(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, selected: Boolean = false, onPress: () -> Unit) {
  val c = Even.colors
  val press by rememberUpdatedState(onPress)
  Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = if (!enabled) c.dim else if (selected) c.tile else c.ink,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier =
          modifier
              .clip(RoundedCornerShape(50))
              .let { if (selected) it.background(c.ink) else it.outlinePill(c.line) }
              .semantics(mergeDescendants = true) {
                role = Role.Button
                if (enabled) onClick { press(); true }
              }
              .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                  val down = awaitFirstDown()
                  down.consume()
                  val fire = press
                  val tapped =
                      withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                          val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                          if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                          if (!change.pressed) {
                            change.consume()
                            return@withTimeoutOrNull true
                          }
                        }
                        @Suppress("UNREACHABLE_CODE") false
                      } == true
                  if (tapped) fire()
                }
              }
              .padding(horizontal = 12.dp, vertical = 6.dp),
  )
}

/** The place a held tile left: no fill, a dashed outline, as the web's `.hgap`. */
internal fun Modifier.dashedGap(color: Color): Modifier = drawBehind {
  val w = 2.dp.toPx()
  drawRoundRect(
      color,
      topLeft = Offset(w / 2, w / 2),
      size = size.copy(width = size.width - w, height = size.height - w),
      cornerRadius = CornerRadius(6.dp.toPx()),
      style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
  )
}
