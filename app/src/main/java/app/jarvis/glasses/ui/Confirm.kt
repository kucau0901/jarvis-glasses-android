package app.jarvis.glasses.ui

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** A button pressed twice: first it asks [sure], then it does [act]. */
@Composable
internal fun Confirm(label: String, sure: String, act: () -> Unit) {
  var asking by remember { mutableStateOf(false) }
  LaunchedEffect(asking) {
    if (asking) {
      delay(4_000)
      asking = false
    }
  }
  OutlinedButton(onClick = { if (asking) { asking = false; act() } else asking = true }) { Text(if (asking) sure else label) }
}
