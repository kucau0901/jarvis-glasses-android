package app.jarvis.glasses.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jarvis.glasses.JarvisApp
import app.jarvis.glasses.location.Here
import app.jarvis.glasses.location.Offer
import kotlinx.coroutines.launch

/**
 * Settings → Location, for a signed-in person: whether this phone tells Jarvis when they arrive at
 * or leave a place they saved, the two permissions Android wants for it (asked here, when the
 * switch goes on, never at first launch), their places, and whether every arrival is also offered
 * to Jarvis. The phone does the watching ([app.jarvis.glasses.location.Fences]); Jarvis keeps the
 * list ([app.jarvis.glasses.location.Places]).
 */
@Composable
fun LocationChoices(app: JarvisApp, hooks: Hooks) {
  var on by remember { mutableStateOf(app.prefs.places) }
  var offers by remember { mutableStateOf(app.prefs.placeOffers) }
  var name by remember { mutableStateOf("") }
  var note by remember { mutableStateOf("") }
  var busy by remember { mutableStateOf(false) }
  val places by app.places.list.collectAsStateWithLifecycle()
  val refused by app.fences.note.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  suspend fun sync() {
    runCatching { app.places.sync() }.onFailure { note = it.message ?: "Couldn't reach Jarvis" }
  }

  // When Settings opens and when the switch goes on: Jarvis's list, then every fence again from it,
  // which also puts back any that Android dropped (Location switched off for a while).
  LaunchedEffect(on) {
    if (!on) return@LaunchedEffect
    sync()
    app.fences.refresh()
  }

  // With the offers on, the routine that answers them is there: made now if it is missing (the
  // switch was on before it made one, or the routine was removed elsewhere).
  LaunchedEffect(on, offers) {
    if (on && offers) runCatching { app.offer.ensure() }.onFailure { note = it.message ?: "Couldn't reach Jarvis" }
  }

  Section("Location") {
    Toggle("Tell Jarvis when I arrive at or leave my places", on) {
      on = it
      app.prefs.places = it
      note = ""
      if (it) {
        if (!hooks.locationAllowed) hooks.allowLocation()
      } else {
        app.outbox.clear()
        app.fences.refresh()
      }
    }
    Text("Sent to Jarvis: only that you arrived at or left a place you saved here, when it happens.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
    Text("Not sent: where you are the rest of the time. The phone does the watching, and nobody keeps a trail.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
    if (!on) return@Section

    Line("Location", if (hooks.locationAllowed) "allowed" else "not allowed")
    Line("All the time", if (hooks.locationAlwaysAllowed) "allowed" else "not allowed")
    if (!hooks.locationAllowed) {
      Text("First, allow precise location while you use the app.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
      OutlinedButton(onClick = hooks::allowLocation) { Text("Allow location") }
    } else if (!hooks.locationAlwaysAllowed) {
      Text(
          "Then choose \"Allow all the time\" on Android's page, which it asks for separately: a place is crossed with the phone in your pocket and Jarvis closed. Until then nothing is watched.",
          style = MaterialTheme.typography.bodySmall,
          color = Even.colors.dim,
      )
      OutlinedButton(onClick = hooks::allowLocationAlways) { Text("Allow all the time") }
    }
    if (refused.isNotEmpty()) Text(refused, style = MaterialTheme.typography.bodySmall, color = Even.colors.danger)

    Text("Your places", style = MaterialTheme.typography.bodyMedium)
    if (places.isEmpty()) Text("None yet. Stand in one and save it below.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
    for (p in places) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name.ifEmpty { p.slug }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("within ${p.radius.toInt()} m", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
        TextButton(
            enabled = !busy,
            onClick = {
              busy = true
              note = ""
              scope.launch {
                runCatching { app.hub.removePlace(p.slug) }.onFailure { note = it.message ?: "Not removed" }
                sync()
                busy = false
              }
            },
        ) {
          Text("Remove")
        }
      }
    }

    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Save where I am now as…") }, placeholder = { Text("Corner Grocer") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      OutlinedButton(
          enabled = !busy && name.isNotBlank() && hooks.locationAllowed,
          onClick = {
            busy = true
            note = "Finding where you are…"
            scope.launch {
              // The one position the app reads and sends: this place's, because you asked.
              runCatching {
                    val at = Here.find(context)
                    app.hub.savePlace(name.trim(), at.lat, at.lng)
                  }
                  .onSuccess { name = ""; note = ""; sync() }
                  // Jarvis's own words when it refuses (a twenty-first place, a name it can't use).
                  .onFailure { note = it.message ?: "Not saved" }
              busy = false
            }
          },
      ) {
        Text("Save")
      }
      Text("The same name again moves that place.", style = MaterialTheme.typography.bodySmall, color = Even.colors.dim)
    }
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = if (busy) Even.colors.dim else Even.colors.danger)

    Toggle("Offer something useful when I arrive", offers) {
      offers = it
      app.prefs.placeOffers = it
      note = ""
      // Going on is the effect above; going off takes its routine away.
      if (!it) scope.launch { runCatching { app.offer.stop() }.onFailure { e -> note = e.message ?: "Couldn't reach Jarvis" } }
    }
    Text(
        "Every arrival is then also put to Jarvis, which costs an answer each time, and it tells you only what is worth an alert. This keeps a routine named \"${Offer.NAME}\" with your others; ask Jarvis to change what it offers.",
        style = MaterialTheme.typography.bodySmall,
        color = Even.colors.dim,
    )
  }
}
