package app.jarvis.glasses.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** What only the activity can do for the screens: Meta AI flows and system settings. */
interface Hooks {
  fun connectMetaAi()

  fun disconnectMetaAi()

  fun allowCamera()

  fun allowBackground()

  fun talk()

  /** Lets the person pick a photo; [onPicked] gets it as a JPEG of about 1024 px. */
  fun pickPhoto(onPicked: (ByteArray) -> Unit)

  val cameraAllowed: Boolean

  val backgroundAllowed: Boolean

  /** "system", "light" or "dark"; setting it restyles the app at once. */
  var theme: String
}

enum class Tab(val label: String, val icon: ImageVector) {
  HOME("Home", Icons.Outlined.Home),
  CHAT("Chat", Glyphs.Chat),
  INBOX("Inbox", Icons.Outlined.Notifications),
  SETTINGS("Settings", Icons.Outlined.Settings),
}

/** Four tabs along the bottom, icons only, as the Even app does; hidden while typing, so the page meets the keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Shell(hooks: Hooks, home: @Composable () -> Unit, chat: @Composable () -> Unit, inbox: @Composable () -> Unit, settings: @Composable () -> Unit) {
  var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
  val c = Even.colors
  val sky = remember { mutableStateOf(false) }
  Column(Modifier.fillMaxSize().let { if (sky.value) it.sky(Hark.colors) else it.background(c.page) }.statusBarsPadding().imePadding()) {
    Box(Modifier.weight(1f).fillMaxWidth()) {
      CompositionLocalProvider(LocalSky provides sky) {
        when (tab) {
          Tab.HOME -> home()
          Tab.CHAT -> chat()
          Tab.INBOX -> inbox()
          Tab.SETTINGS -> settings()
        }
      }
    }
    if (!WindowInsets.isImeVisible) Row(
        Modifier.fillMaxWidth().background(c.tile).navigationBarsPadding().height(60.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      for (t in Tab.entries) {
        Box(Modifier.weight(1f).height(60.dp).clickable { tab = t }, contentAlignment = Alignment.Center) {
          Icon(t.icon, contentDescription = t.label, tint = if (t == tab) c.ink else c.dim, modifier = Modifier.size(24.dp))
        }
      }
    }
  }
}

/** Page heading, Even style: a small mark and a plain title. */
@Composable
fun Heading(title: String, trailing: @Composable () -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
    androidx.compose.material3.Text(title, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
    trailing()
  }
}
