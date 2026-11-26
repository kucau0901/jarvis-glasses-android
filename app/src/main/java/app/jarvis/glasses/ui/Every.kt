package app.jarvis.glasses.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * [look] at once and then every [ms] (read afresh each time, so it can slow down while a job waits),
 * only while the screen is in front, as the web looks again only while its page is seen. Starts
 * over when any of [keys] changes. [look] answers whether to go on.
 */
@Composable
fun Every(vararg keys: Any?, ms: () -> Long, look: suspend () -> Boolean) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val again by rememberUpdatedState(look)
  val wait by rememberUpdatedState(ms)
  LaunchedEffect(lifecycle, *keys) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (again()) delay(wait())
    }
  }
}
