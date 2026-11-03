package app.jarvis.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.jarvis.glasses.g2.G2Painter
import app.jarvis.glasses.g2.g2Strip
import app.jarvis.glasses.ui.DemoScreen
import app.jarvis.glasses.ui.EvenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The G2 demo camera: the phone's camera filmed with what Jarvis has on the G2 drawn in along the
 * bottom ([G2Painter]), as it changes, so one video shows the person and their glasses' display in
 * step, with nothing to edit after. Filmed in landscape, either way up. Saved to Movies/Jarvis, to
 * share from here.
 *
 * The G2 screen is the app's own ([app.jarvis.glasses.g2.G2Link.screen]): what it sends the
 * glasses, not a picture taken of them. Jarvis on the G2 goes on as ever meanwhile; it runs in the
 * app, not this screen.
 */
class DemoActivity : ComponentActivity() {
  private val drawing = HandlerThread("G2 overlay").apply { start() }
  private val painter = G2Painter()
  private val effect =
      OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, Handler(drawing.looper)) { Log.w(TAG, "overlay failed", it) }
          .apply {
            setOnDrawListener { frame ->
              draw(frame)
              true
            }
          }

  private lateinit var preview: PreviewView
  private var shown: Preview? = null
  private var video: VideoCapture<Recorder>? = null
  private var recording: Recording? = null

  private var front by mutableStateOf(false)
  private var recordingNow by mutableStateOf(false)
  private var elapsedNanos by mutableLongStateOf(0L)
  private var saved by mutableStateOf<Uri?>(null)
  private var problem by mutableStateOf("")

  // Turned the other way up (landscape both ways, so Android doesn't start the screen again): the
  // picture turned with it, unless a recording is going, which keeps the way it began.
  private val turned =
      object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
          val rotation = display.rotation
          shown?.targetRotation = rotation
          if (recording == null) video?.targetRotation = rotation
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
      }

  private val ask =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (allowed(Manifest.permission.CAMERA)) bind() else problem = "The camera isn't allowed: Android's settings for Jarvis can allow it."
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    // At the left, so the space left on the right is the controls' and the picture isn't under them.
    preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_START }
    getSystemService(DisplayManager::class.java).registerDisplayListener(turned, null)
    setContent {
      EvenTheme(dark = true) {
        DemoScreen(
            preview = preview,
            recording = recordingNow,
            elapsedNanos = elapsedNanos,
            front = front,
            saved = saved,
            problem = problem,
            g2 = jarvis.g2Link != null,
            onRecord = ::toggleRecording,
            onFlip = {
              front = !front
              bind()
            },
            onShare = ::share,
            onClose = ::finish,
        )
      }
    }
    if (allowed(Manifest.permission.CAMERA)) bind() else ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
  }

  override fun onStop() {
    super.onStop()
    // Off screen, the camera is let go of: what was filmed is kept.
    recording?.stop()
  }

  override fun onDestroy() {
    super.onDestroy()
    getSystemService(DisplayManager::class.java).unregisterDisplayListener(turned)
    effect.close()
    drawing.quitSafely()
  }

  private fun allowed(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

  /** The camera chosen, with the preview and the recorder, both drawn on by [effect]. */
  private fun bind() {
    val future = ProcessCameraProvider.getInstance(this)
    future.addListener(
        {
          val provider = future.get()
          val p = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
          p.targetRotation = display.rotation
          val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build()
          // The front camera's video as its preview shows it, mirrored, so the G2 screen reads the same in both.
          val v = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).setTargetRotation(display.rotation).build()
          val group = UseCaseGroup.Builder().addUseCase(p).addUseCase(v).addEffect(effect).build()
          val camera = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
          runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, camera, group)
                shown = p
                video = v
                problem = ""
              }
              .onFailure {
                Log.w(TAG, "the camera couldn't be opened", it)
                problem = "The camera couldn't be opened: ${it.message}"
              }
        },
        ContextCompat.getMainExecutor(this),
    )
  }

  /** On the camera's frame: what the G2 shows now, upright in the picture as it will be seen. */
  private fun draw(frame: Frame) {
    val canvas = frame.overlayCanvas
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    val boxes = jarvis.g2Link?.screen?.value ?: return
    val crop = frame.cropRect
    val turn = frame.rotationDegrees
    val width = if (turn % 180 == 0) crop.width() else crop.height()
    val height = if (turn % 180 == 0) crop.height() else crop.width()
    canvas.save()
    // The frame is turned by [turn] clockwise to be seen upright: drawn turned back, so it is upright then.
    canvas.translate(crop.exactCenterX(), crop.exactCenterY())
    canvas.rotate(-turn.toFloat())
    canvas.translate(-width / 2f, -height / 2f)
    if (frame.isMirroring) canvas.scale(-1f, 1f, width / 2f, 0f)
    painter.draw(canvas, boxes, g2Strip(width, height))
    canvas.restore()
  }

  @SuppressLint("MissingPermission") // The microphone is checked first: without it, the video has no sound.
  private fun toggleRecording() {
    recording?.let {
      it.stop()
      return
    }
    val output = video?.output ?: return
    val name = "jarvis-g2-demo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val values =
        ContentValues().apply {
          // With its type said: MediaStore won't take a video it can't tell is one.
          put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Jarvis")
        }
    val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
    saved = null
    problem = ""
    recording =
        output.prepareRecording(this, options).apply { if (allowed(Manifest.permission.RECORD_AUDIO)) withAudioEnabled() }.start(ContextCompat.getMainExecutor(this)) { event ->
          when (event) {
            is VideoRecordEvent.Start -> recordingNow = true
            is VideoRecordEvent.Status -> elapsedNanos = event.recordingStats.recordedDurationNanos
            is VideoRecordEvent.Finalize -> {
              recordingNow = false
              elapsedNanos = 0
              recording = null
              if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE) {
                Log.w(TAG, "recording failed: ${event.error}", event.cause)
                problem = "The video couldn't be saved (${event.error})."
              } else {
                saved = event.outputResults.outputUri
              }
            }
          }
        }
  }

  private fun share() {
    val uri = saved ?: return
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(send, "Share the demo"))
  }

  private companion object {
    const val TAG = "Demo"
  }
}
