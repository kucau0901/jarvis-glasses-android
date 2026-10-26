package app.jarvis.glasses.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.jarvis.glasses.chat.Markdown
import app.jarvis.glasses.chat.Markdown.Block
import app.jarvis.glasses.chat.Markdown.Span
import app.jarvis.glasses.chat.Step

/** One line of an answer, its bold, italic, code and links drawn; a link opens in the browser. */
private fun annotated(text: String, ink: Color, wash: Color): AnnotatedString = buildAnnotatedString {
  for (s in Markdown.spans(text)) {
    when (s) {
      is Span.Plain -> append(s.text)
      is Span.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) }
      is Span.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.text) }
      is Span.Mono -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = wash)) { append(s.text) }
      is Span.Link -> withLink(LinkAnnotation.Url(s.url, TextLinkStyles(SpanStyle(color = ink, textDecoration = TextDecoration.Underline)))) { append(s.label) }
    }
  }
}

/** An answer as the web app draws it: plain words as they are; headings, lists, tables, code and quotes as meant. */
@Composable
fun MarkdownText(text: String, ink: Color, dim: Color, wash: Color) {
  val body = MaterialTheme.typography.bodyLarge.copy(color = ink)
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    for (b in Markdown.blocks(text)) {
      when (b) {
        is Block.Para -> Text(annotated(b.text, ink, wash), style = body)
        is Block.Heading ->
            Text(annotated(b.text, ink, wash), style = (if (b.level == 1) MaterialTheme.typography.titleMedium else body).copy(color = ink, fontWeight = FontWeight.SemiBold))
        is Block.Items ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              b.items.forEachIndexed { i, item ->
                Row {
                  Text(if (b.numbered) "${i + 1}." else "•", style = body, modifier = Modifier.width(22.dp))
                  Text(annotated(item, ink, wash), style = body)
                }
              }
            }
        is Block.Table -> Table(b, ink, dim, wash)
        is Block.Code ->
            Text(
                b.text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = ink),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(wash).horizontalScroll(rememberScrollState()).padding(10.dp),
            )
        is Block.Quote ->
            Row(Modifier.height(IntrinsicSize.Min)) {
              Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(dim))
              Text(annotated(b.text, ink, wash), style = body.copy(color = dim), modifier = Modifier.padding(start = 10.dp))
            }
        Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(wash))
      }
    }
  }
}

/** A table, its columns lined up, sliding sideways when wider than the bubble. */
@Composable
private fun Table(t: Block.Table, ink: Color, dim: Color, wash: Color) {
  val cell = Modifier.width(150.dp).padding(horizontal = 8.dp, vertical = 6.dp)
  val style = MaterialTheme.typography.bodySmall.copy(color = ink)
  Column(Modifier.clip(RoundedCornerShape(12.dp)).horizontalScroll(rememberScrollState())) {
    Row(Modifier.background(wash)) { for (h in t.head) Text(annotated(h, ink, wash), style = style.copy(fontWeight = FontWeight.SemiBold), modifier = cell) }
    t.rows.forEachIndexed { i, r ->
      if (i > 0) Box(Modifier.width(150.dp * t.head.size).height(1.dp).background(wash))
      Row { for (c in r) Text(annotated(c, ink, wash), style = style, modifier = cell) }
    }
  }
}

/** A pill that breathes while its work goes on, as the web app's do. */
@Composable
private fun Breathing(on: Boolean, content: @Composable (Modifier) -> Unit) {
  if (!on) return content(Modifier)
  val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.55f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "alpha")
  content(Modifier.alpha(pulse))
}

/**
 * Jarvis at work on the question: "Jarvis", then a pill per tool call in words, running ones
 * breathing, finished ones ticked, and "Thinking" while none runs.
 */
@Composable
fun WorkingRow(steps: List<Step>, thinking: String) {
  val h = Hark.colors
  val ok = Even.colors.on
  Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp)) {
    Text("Jarvis", style = MaterialTheme.typography.labelSmall, color = h.dim)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      for (s in steps) {
        Breathing(s.running) { m ->
          Pill(m) {
            if (!s.running) Text("✓ ", style = MaterialTheme.typography.labelLarge, color = ok)
            Text(s.words, style = MaterialTheme.typography.labelLarge, color = if (s.running) h.ink else h.dim)
          }
        }
      }
      if (steps.none { it.running }) Breathing(true) { m -> Pill(m) { Text(thinking, style = MaterialTheme.typography.labelLarge, color = h.ink) } }
    }
  }
}

@Composable
private fun Pill(modifier: Modifier, content: @Composable () -> Unit) {
  Row(
      modifier.heightIn(min = 30.dp).clip(CircleShape).background(Hark.colors.bubble).padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}
