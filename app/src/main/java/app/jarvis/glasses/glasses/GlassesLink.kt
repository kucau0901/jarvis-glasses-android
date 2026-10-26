package app.jarvis.glasses.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import app.jarvis.glasses.conversation.Eyes
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What only the Wearables Device Access Toolkit gives: registration with Meta AI, "Hey Meta, open
 * Jarvis" (Voice Invocations), and the glasses camera. Audio does not go through it (see
 * [app.jarvis.glasses.audio.GlassesAudio]).
 *
 * [start] boots the toolkit; it must come after the Bluetooth permission is granted, or the SDK
 * reads "no glasses on this phone" for the life of the process (Meta's BirdSpotter sample,
 * `MainActivity`).
 */
class GlassesLink(private val context: Context, private val scope: CoroutineScope) : Eyes {
  private val _registered = MutableStateFlow(false)
  val registered: StateFlow<Boolean> = _registered

  private val _problem = MutableStateFlow("")
  /** Why connecting to Meta AI last failed, if it did. */
  val problem: StateFlow<String> = _problem

  @Volatile private var started = false

  fun start(onHeyMeta: () -> Unit) {
    if (started) return
    Wearables.initialize(context.applicationContext).fold(
        onSuccess = {
          started = true
          scope.launch {
            Wearables.registrationState.collect {
              Log.i(TAG, "registration: $it")
              _registered.value = it == RegistrationState.REGISTERED
              if (_registered.value) _problem.value = ""
            }
          }
          scope.launch {
            Wearables.registrationErrorStream.collect {
              Log.w(TAG, "registration failed: ${it.description}")
              _problem.value = it.description
            }
          }
          scope.launch { heyMeta(onHeyMeta) }
        },
        onFailure = { error, _ ->
          Log.e(TAG, "Wearables toolkit did not start: ${error.description}")
          _problem.value = error.description
        },
    )
  }

  fun register(activity: Activity) {
    if (started) Wearables.startRegistration(activity)
  }

  /**
   * A registration Meta AI starts itself (from its list of apps) arrives as an intent; it is
   * continued here. Anything else is ignored, so every intent can be passed.
   */
  fun handle(activity: Activity, intent: Intent) {
    if (!started) return
    Wearables.handleIntent(intent) { request ->
      request.continueRegistration(activity).onFailure { error, _ -> _problem.value = error.description }
    }
  }

  suspend fun cameraAllowed(): Boolean {
    if (!started) return false
    return Wearables.checkPermissionStatus(Permission.CAMERA).fold(
        onSuccess = { it == PermissionStatus.Granted },
        onFailure = { _, _ -> false },
    )
  }

  /**
   * "Hey Meta, open Jarvis" while the app is running. Meta AI holds each invocation until the app
   * answers, so it is answered at once, then [onLaunch]. A dropped channel is reopened with backoff
   * (an error, not the end of the stream, is how a drop shows). A launch with the app closed comes
   * as an intent instead, to `MainActivity`.
   */
  private suspend fun heyMeta(onLaunch: () -> Unit) {
    var backoff = 500L
    while (scope.isActive) {
      Wearables.registrationState.first { it == RegistrationState.REGISTERED }
      val stream = Wearables.startVoiceInvocationsStream(AutoDeviceSelector())
      try {
        coroutineScope {
          launch {
            stream.invocations.collect { invocation ->
              when (invocation) {
                is LaunchApp -> {
                  invocation.responseHandle.sendSuccess(null)
                  onLaunch()
                }
              }
            }
          }
          stream.errors.collect { error ->
            Log.w(TAG, "voice invocations: ${error.description}")
            throw Dropped
          }
        }
      } catch (_: Dropped) {
        // reopen below
      } finally {
        stream.close()
      }
      delay(backoff)
      backoff = (backoff * 2).coerceAtMost(30_000L)
    }
  }

  override suspend fun photo(): ByteArray? = withTimeoutOrNull(20_000) { takePhoto() }

  private suspend fun takePhoto(): ByteArray? {
    if (!_registered.value || !cameraAllowed()) return null
    val session =
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { it },
            onFailure = { error, _ ->
              Log.w(TAG, "no session: ${error.description}")
              null
            },
        ) ?: return null
    try {
      session.start()
      if (session.state.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.STOPPED } != DeviceSessionState.STARTED) {
        return null
      }
      val camera =
          session.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 15)).fold(
              onSuccess = { it },
              onFailure = { error, _ ->
                Log.w(TAG, "no camera: ${error.description}")
                null
              },
          ) ?: return null
      camera.stream.start()
      if (camera.stream.state.first { it == StreamState.STREAMING || it == StreamState.CLOSED } != StreamState.STREAMING) {
        return null
      }
      val data =
          camera.stream.capturePhoto().fold(
              onSuccess = { it },
              onFailure = { error, _ ->
                Log.w(TAG, "photo failed: ${error.description}")
                null
              },
          ) ?: return null
      return withContext(Dispatchers.Default) {
        when (data) {
          is PhotoData.Bitmap -> jpeg(data.bitmap)
          is PhotoData.HEIC -> {
            val bytes = ByteArray(data.data.remaining()).also { data.data.get(it) }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::jpeg)
          }
        }
      }
    } finally {
      session.stop()
    }
  }

  private object Dropped : CancellationException("voice invocations dropped")

  companion object {
    private const val TAG = "GlassesLink"

    /** About 1024 px on the long side, as Jarvis asks: the model sees no more in a larger one. */
    fun jpeg(bitmap: Bitmap): ByteArray {
      val scale = 1024f / maxOf(bitmap.width, bitmap.height)
      val sized =
          if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
          else bitmap
      return ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
  }
}
