package app.jarvis.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jarvis.glasses.ui.Even
import app.jarvis.tv.jarvis.Panel

/**
 * A panel's to-dos, as the phone's notes sheet: OK ticks or unticks one; a new one is said
 * (Add a to-do), and the ticked ones cleared together. Back closes it.
 */
@Composable
fun NotesPage(panel: Panel, onTick: (key: String) -> Unit, onAdd: () -> Unit, onClear: () -> Unit) {
  val c = Even.colors
  val notes = notesOf(panel)
  val first = remember { FocusRequester() }
  LaunchedEffect(Unit) { first.requestFocus() }
  Column(Modifier.fillMaxSize().background(c.page).padding(horizontal = 48.dp, vertical = 27.dp)) {
    Title("${panel.title}: to-dos")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Even.gutter * 2), contentPadding = PaddingValues(vertical = 8.dp)) {
      // By place, not by to-do: a ticked one moves to the end, and the next OK should tick the one now here.
      itemsIndexed(notes) { i, n ->
        val done = n.done == true
        FocusBar({ onTick(n.key) }, if (i == 0) Modifier.focusRequester(first) else Modifier) {
          Text(
              (if (done) "☑  " else "☐  ") + n.label,
              style = TextStyle(fontSize = 19.sp, color = if (done) c.dim else c.ink, textDecoration = if (done) TextDecoration.LineThrough else null),
              modifier = Modifier.align(Alignment.CenterStart),
          )
        }
      }
      item {
        FocusBar(onAdd, if (notes.isEmpty()) Modifier.focusRequester(first) else Modifier) {
          Label("Add a to-do: press, then say it", Modifier.align(Alignment.CenterStart))
        }
      }
      if (notes.any { it.done == true }) item { FocusBar(onClear) { Label("Clear the ticked ones", Modifier.align(Alignment.CenterStart)) } }
    }
  }
}
