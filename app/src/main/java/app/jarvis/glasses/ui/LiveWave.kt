package app.jarvis.glasses.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.conversation.Conversation as Talk
import app.jarvis.glasses.live.LiveVoice
import app.jarvis.glasses.live.Voices
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * While Jarvis is in a conversation, Live or Talk: a small pill floating over every tab, in the
 * dots of Home's status panel, with a speech wave that moves with the voices, what is going on in
 * words, and a stop. Driven by levels, as the web app's orb is (src/app/orb/Orb.ts there): in a
 * Live session both sides can speak at once, and the wave blends them rather than switching.
 *
 * The wearer's voice makes a quick, jagged wave and Jarvis's a smooth swell, so the two read
 * differently at a glance; while Jarvis works on an answer a bump sweeps across, and quiet is a
 * flat line.
 */
@Composable
fun LiveWave(app: JarvisApp, modifier: Modifier = Modifier) {
  val talk by app.conversation.status.collectAsStateWithLifecycle()
  val live by app.live.state.collectAsStateWithLifecycle()
  val voices by app.live.voices.collectAsStateWithLifecycle()
  val mic by app.audio.level.collectAsStateWithLifecycle()
  FloatingWave(waveInputs(talk, live, voices, mic), modifier) {
    app.conversation.stop()
    app.live.stop()
  }
}

/** The pill, placed by [modifier], while there are [inputs]. */
@Composable
internal fun FloatingWave(inputs: WaveInputs?, modifier: Modifier = Modifier, onStop: () -> Unit) {
  // Kept through the closing animation, when there are no inputs any more.
  var shown by remember { mutableStateOf(WaveInputs()) }
  if (inputs != null) shown = inputs
  AnimatedVisibility(
      visible = inputs != null,
      enter = fadeIn() + slideInVertically { it / 2 },
      exit = fadeOut() + slideOutVertically { it / 2 },
      modifier = modifier,
  ) {
    Wave(shown, onStop)
  }
}

@Composable
private fun Wave(inputs: WaveInputs, onStop: () -> Unit) {
  val c = Even.colors
  val ink = c.hudInk
  val ghost = ink.copy(alpha = 0.07f)
  val target by rememberUpdatedState(inputs)
  // Its own clock, and the levels eased toward their targets so a late frame never jumps.
  var time by remember { mutableFloatStateOf(0f) }
  val eased = remember { MutableWave(inputs) }
  var label by remember { mutableStateOf(waveLabel(eased)) }
  LaunchedEffect(Unit) {
    var last = 0L
    while (true) {
      withFrameNanos { now ->
        val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        eased.ease(target, dt)
        time += dt
        // Set only when it changes: the words are composed, the wave is only drawn.
        waveLabel(eased).let { if (it != label) label = it }
      }
    }
  }
  val pill = RoundedCornerShape(50)
  Row(
      Modifier.height(40.dp)
          .clip(pill)
          .background(c.hud)
          .border(1.dp, c.line, pill)
          .padding(start = 16.dp, end = 4.dp)
          .semantics(mergeDescendants = true) {
            contentDescription = "Jarvis"
            stateDescription = waveSpoken(label)
          },
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp, color = ink), maxLines = 1, modifier = Modifier.width(86.dp).clearAndSetSemantics {})
    Canvas(Modifier.size(width = 100.dp, height = 28.dp)) {
      // Read here, so each frame redraws without composing again.
      drawWave(eased, time, ink, ghost)
    }
    Box(Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).clickable(onClickLabel = "Stop", role = Role.Button, onClick = onStop), contentAlignment = Alignment.Center) {
      Icon(Icons.Filled.Close, contentDescription = "Stop", tint = ink, modifier = Modifier.size(18.dp))
    }
  }
}

private fun DrawScope.drawWave(w: MutableWave, t: Float, ink: Color, ghost: Color) {
  val pitch = size.height / WAVE_ROWS
  val cols = (size.width / pitch).toInt()
  val r = pitch * 0.36f
  for (x in 0 until cols) {
    val reach = waveReach(x, cols, t, w)
    for (y in 0 until WAVE_ROWS) drawCircle(if (abs(y - WAVE_ROWS / 2) <= reach) ink else ghost, r, Offset((x + 0.5f) * pitch, (y + 0.5f) * pitch))
  }
}

/** Dots tall: odd, so the wave has a middle row, lit as a flat line when it is quiet. */
internal const val WAVE_ROWS = 7

/**
 * How many dots column [x] of [cols] reaches above and below the middle row at [t] seconds: the
 * wearer's voice jagged and quick, Jarvis's a smooth travelling swell, the louder of the two; a bump
 * sweeping across while Jarvis works; one dot stepping along while connecting. Tallest in the middle.
 */
internal fun waveReach(x: Int, cols: Int, t: Float, w: MutableWave): Int {
  val middle = sin(PI.toFloat() * (x + 0.5f) / cols).coerceAtLeast(0f).pow(0.8f)
  val jagged = 0.5f + 0.5f * sin(t * 13f + x * 2.7f) * sin(t * 7.1f + x * 1.3f)
  val swell = 0.5f + 0.5f * sin(x * 0.55f - t * 5f)
  val user = w.user * middle * (0.3f + 0.7f * jagged)
  val agent = w.agent * middle * (0.4f + 0.6f * swell)
  val sweep = (t * 0.9f % 1f) * (cols + 6) - 3
  val think = w.think * 0.55f * exp(-(x - sweep) * (x - sweep) / 2f)
  val step = w.connecting * if ((t * 8f).toInt() % cols == x) 0.35f else 0f
  // A little more than the levels: speech sits mid-scale, and a wave a dot high hardly moves.
  return ((maxOf(user, agent) * 1.3f).coerceAtLeast(maxOf(think, step)).coerceIn(0f, 1f) * (WAVE_ROWS / 2)).roundToInt()
}

/** Jarvis speaking a Talk reply: there is no level to read from it, so a steady swell. */
internal const val TALK_SPEAKING = 0.55f

/** What drives the wave, each 0..1 ([connecting] too, so it can fade). */
internal data class WaveInputs(val user: Float = 0f, val agent: Float = 0f, val think: Float = 0f, val connecting: Float = 0f)

/** The inputs eased frame by frame, from [start]: a pill that opens connecting says so from its first frame. */
internal class MutableWave(start: WaveInputs = WaveInputs()) {
  var user = start.user
  var agent = start.agent
  var think = start.think
  var connecting = start.connecting

  /**
   * The voices again for the words, letting go slowly: the wave may settle between words, but the
   * words flicking between "listening" and "Jarvis" at each pause read as noise.
   */
  var userHeld = start.user
  var agentHeld = start.agent

  /** Toward [to] over [dt] seconds, at the web orb's rates per 60 fps frame: voices quickly, thinking slowly. */
  fun ease(to: WaveInputs, dt: Float) {
    fun k(perFrame: Float) = 1f - (1f - perFrame).pow(dt * 60f)
    user += (to.user - user) * k(0.25f)
    agent += (to.agent - agent) * k(0.25f)
    think += (to.think - think) * k(0.08f)
    connecting += (to.connecting - connecting) * k(0.15f)
    userHeld += (to.user - userHeld) * k(if (to.user > userHeld) 0.25f else 0.03f)
    agentHeld += (to.agent - agentHeld) * k(if (to.agent > agentHeld) 0.25f else 0.03f)
  }
}

/** What the wave shows for a conversation, or null with none going: then it is hidden. */
internal fun waveInputs(talk: Talk.Status, live: LiveVoice.State, voices: Voices, mic: Float): WaveInputs? =
    when {
      live == LiveVoice.State.CONNECTING -> WaveInputs(connecting = 1f)
      live == LiveVoice.State.LIVE -> WaveInputs(voices.user, voices.agent, if (voices.thinking) 1f else 0f)
      talk == Talk.Status.OPENING -> WaveInputs(connecting = 1f)
      talk == Talk.Status.LISTENING -> WaveInputs(user = mic)
      talk == Talk.Status.THINKING -> WaveInputs(think = 1f)
      talk == Talk.Status.SPEAKING -> WaveInputs(agent = TALK_SPEAKING)
      else -> null
    }

/** What is going on, in the status panel's words: Jarvis speaking wins over working on an answer. */
internal fun waveLabel(w: MutableWave): String =
    when {
      w.connecting > 0.5f -> "CONNECTING"
      w.agentHeld > 0.15f && w.agentHeld >= w.userHeld -> "JARVIS"
      w.think > 0.5f -> "THINKING"
      else -> "LISTENING"
    }

/** The words for TalkBack, after "Jarvis": "Jarvis, jarvis" said nothing. */
internal fun waveSpoken(label: String): String = if (label == "JARVIS") "speaking" else label.lowercase()
