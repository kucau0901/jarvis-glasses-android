package app.jarvis.glasses.g2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import app.jarvis.glasses.glasses.GlassesSource
import app.jarvis.glasses.glasses.GlassesState
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Even Realities G2 glasses, reached over Bluetooth for as long as Jarvis has something to show:
 * [open] connects both arms (paired already, by the Even app), signs in and keeps the link alive;
 * [show] draws a page of text; [touches] are what the wearer does on it; [micOn] streams the
 * glasses' microphone as LC3; [close] puts the page away and lets go.
 *
 * The Even app stays connected all along, on the same link, and the glasses go back to it when
 * the page closes. Only sign-in, the time and heartbeats are sent to the glasses' settings: nothing
 * Even's app set (its dashboard, "Hey Even") is changed. The protocol is in [G2Wire].
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Link(context: Context) : GlassesSource {
  private val context = context.applicationContext
  private val bluetooth = context.getSystemService(BluetoothManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private val writer = Executors.newSingleThreadExecutor()

  private class Arm(val side: String) {
    var gatt: BluetoothGatt? = null
    @Volatile var write: BluetoothGattCharacteristic? = null
    val ops = ArrayDeque<() -> Unit>()
    var busy = false
    var ready = false
    val reassembler = G2Reassembler()
  }

  private val left = Arm("L")
  private val right = Arm("R")
  private var syncId = 0
  private var magic = 0
  private var signedIn: CompletableDeferred<Boolean>? = null
  private var pageShown = false
  private var mic: Channel<ByteArray>? = null
  private var lastAudio: ByteArray? = null

  private val _touches = MutableSharedFlow<G2Touch>(extraBufferCapacity = 16)
  val touches: SharedFlow<G2Touch> = _touches

  private val _glasses = MutableStateFlow<List<GlassesState>>(emptyList())
  /** The G2 for Home: paired or not, with the phone or not, and its battery when it was last held. */
  override val glasses: StateFlow<List<GlassesState>> = _glasses

  private var info: G2Info? = null
  private var infoAt = 0L
  /** The right arm's firmware, as the glasses last said. */
  var firmware: String? = null
    private set

  init {
    val filter =
        IntentFilter().apply {
          addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
          addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
          addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
    this.context.registerReceiver(
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, i: Intent) = refresh()
        },
        filter,
        Context.RECEIVER_NOT_EXPORTED,
    )
    refresh()
  }

  /** What Home shows of the G2, again: after a link change or what the glasses said. */
  private fun refresh() {
    val (l, _) = arms() ?: return run { _glasses.value = emptyList() }
    // The battery as last heard, while it can still be right.
    val fresh = info.takeIf { System.currentTimeMillis() - infoAt < FRESH_MS }
    _glasses.value =
        listOf(
            GlassesState(
                name = l.name.substringBefore("_L_").ifBlank { "Even G2" },
                model = "Even Realities G2",
                link = if (reachable()) GlassesState.Link.CONNECTED else GlassesState.Link.AWAY,
                battery = fresh?.battery,
                charging = fresh?.charging,
                display = true,
            )
        )
  }

  /** Both arms of a G2, as the phone paired them ("Even G2_32_L_…", "…_R_…"), or null. */
  private fun arms(): Pair<BluetoothDevice, BluetoothDevice>? {
    val paired = runCatching { bluetooth.adapter.bondedDevices }.getOrNull().orEmpty().filter { it.name?.contains("G2") == true }
    val l = paired.firstOrNull { it.name.contains("_L_") } ?: return null
    val r = paired.firstOrNull { it.name.contains("_R_") } ?: return null
    return l to r
  }

  /** A G2 is paired and connected to this phone now (through the Even app), so [open] can reach it. */
  fun reachable(): Boolean {
    val (l, r) = arms() ?: return false
    return runCatching { listOf(l, r).all { bluetooth.getConnectionState(it, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED } }
        .getOrDefault(false)
  }

  /** Connects and signs in; false when the glasses can't be reached within [timeoutMs]. */
  suspend fun open(timeoutMs: Long = 10_000): Boolean {
    val started = CompletableDeferred<CompletableDeferred<Boolean>?>()
    main.post {
      signedIn?.let {
        started.complete(it)
        return@post
      }
      val pair = arms()
      if (pair == null) {
        started.complete(null)
        return@post
      }
      val signing = CompletableDeferred<Boolean>()
      signedIn = signing
      started.complete(signing)
      connect(left, pair.first)
      connect(right, pair.second)
      // Still connected from a moment ago (closed, then opened again before letting go).
      if (left.ready && right.ready) signIn()
    }
    val signing = started.await() ?: return false
    val ok = withTimeoutOrNull(timeoutMs) { signing.await() } ?: false
    if (!ok) close()
    return ok
  }

  /** Draws [text] on the glasses, filling the display. */
  fun show(text: String) {
    main.post {
      if (signedIn?.isCompleted != true) return@post
      send(G2Service.EVEN_HUB, EvenHub.page(text, first = !pageShown, magic = nextMagic()), true)
      pageShown = true
    }
  }

  /**
   * The microphone on, as 200-byte pieces of LC3 ([G2_MIC_BYTES]), until [micOff]. It needs a page
   * on the glasses; the firmware arms it on an off-then-on.
   */
  fun micOn(): ReceiveChannel<ByteArray> {
    val channel = Channel<ByteArray>(Channel.UNLIMITED)
    main.post {
      mic?.close()
      mic = channel
      lastAudio = null
      send(G2Service.EVEN_HUB, EvenHub.mic(false, nextMagic()), true)
      main.postDelayed({ if (mic === channel) send(G2Service.EVEN_HUB, EvenHub.mic(true, nextMagic()), true).also { Log.i(TAG, "mic on") } }, 500)
    }
    return channel
  }

  fun micOff() {
    main.post {
      if (mic == null) return@post
      mic?.close()
      mic = null
      send(G2Service.EVEN_HUB, EvenHub.mic(false, nextMagic()), true)
    }
  }

  /** The page put away and both arms let go; the Even app has the glasses to itself again. */
  fun close() {
    main.post {
      main.removeCallbacks(heartbeats)
      mic?.close()
      mic = null
      if (pageShown) {
        send(G2Service.EVEN_HUB, EvenHub.shutdown(), true)
        pageShown = false
      }
      signedIn?.complete(false)
      signedIn = null
      // After the shutdown has gone out.
      main.postDelayed({ if (signedIn == null) disconnect() }, 500)
    }
  }

  private fun disconnect() {
    for (arm in listOf(left, right)) {
      arm.gatt?.disconnect()
      arm.gatt?.close()
      arm.gatt = null
      arm.write = null
      arm.ready = false
      arm.ops.clear()
      arm.busy = false
    }
  }

  private fun connect(arm: Arm, device: BluetoothDevice) {
    if (arm.gatt != null) return
    arm.gatt = device.connectGatt(context, false, callback(arm), BluetoothDevice.TRANSPORT_LE)
  }

  private fun callback(arm: Arm) =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          main.post {
            if (arm.gatt !== gatt) return@post
            if (newState == BluetoothProfile.STATE_CONNECTED) {
              if (!gatt.requestMtu(247)) gatt.discoverServices()
              gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
              Log.i(TAG, "${arm.side}: disconnected ($status)")
              // One arm gone is the glasses gone: the page can't be drawn or heard any more.
              _touches.tryEmit(G2Touch.CLOSED)
              pageShown = false
              main.removeCallbacks(heartbeats)
              mic?.close()
              mic = null
              signedIn?.complete(false)
              signedIn = null
              disconnect()
            }
          }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
          main.post { if (arm.gatt === gatt) gatt.discoverServices() }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          main.post {
            if (arm.gatt !== gatt) return@post
            // By characteristic, not service: a firmware update in 2026 moved them between services.
            for (c in gatt.services.flatMap { it.characteristics }) {
              when (c.uuid) {
                G2Uuid.WRITE -> arm.write = c
                G2Uuid.NOTIFY, G2Uuid.AUDIO -> enqueue(arm) { subscribe(arm, gatt, c) }
              }
            }
            enqueue(arm) {
              arm.ready = arm.write != null
              arm.busy = false
              runNext(arm)
              if (left.ready && right.ready) signIn()
            }
          }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
          main.post {
            arm.busy = false
            runNext(arm)
          }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
          main.post {
            if (arm.gatt !== gatt) return@post
            when (c.uuid) {
              G2Uuid.AUDIO -> onAudio(value)
              G2Uuid.NOTIFY -> onNotify(arm, value)
            }
          }
        }
      }

  // Android allows one GATT operation at a time per connection.
  private fun enqueue(arm: Arm, op: () -> Unit) {
    arm.ops.addLast(op)
    if (!arm.busy) runNext(arm)
  }

  private fun runNext(arm: Arm) {
    val op = arm.ops.removeFirstOrNull() ?: return
    arm.busy = true
    op()
  }

  private fun subscribe(arm: Arm, gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
    gatt.setCharacteristicNotification(c, true)
    val cccd = c.getDescriptor(G2Uuid.CCCD)
    if (cccd == null || gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) != 0) {
      Log.w(TAG, "${arm.side}: couldn't subscribe to ${c.uuid}")
      arm.busy = false
      runNext(arm)
    }
  }

  private fun nextMagic() = magic++ and 0xFF

  private fun send(service: Byte, payload: ByteArray, reserveFlag: Boolean, toLeft: Boolean = false, toRight: Boolean = true) {
    val packets = g2Packets(syncId++ and 0xFF, service, payload, reserveFlag)
    val l = left.gatt?.let { it to left.write }
    val r = right.gatt?.let { it to right.write }
    writer.execute {
      for (p in packets) {
        if (toRight && r != null) write(r.first, r.second, p)
        if (toLeft && l != null) write(l.first, l.second, p)
        Thread.sleep(8)
      }
    }
  }

  private fun write(gatt: BluetoothGatt, c: BluetoothGattCharacteristic?, packet: ByteArray) {
    if (c == null) return
    repeat(5) {
      if (gatt.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0) return
      Thread.sleep(8)
    }
    Log.w(TAG, "a write to the glasses was dropped")
  }

  /** As the Even app does, as far as Jarvis needs: both arms, the right taking commands, the time. */
  private fun signIn() {
    val signing = signedIn ?: return
    if (signing.isCompleted) return
    send(G2Service.DEVICE_SETTINGS, DevSettings.auth(nextMagic()), false, toLeft = true, toRight = false)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.auth(nextMagic()), false) }, 200)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.rightTakesCommands(nextMagic()), false) }, 400)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.timeSync(nextMagic()), false, toLeft = true) }, 600)
    main.postDelayed(
        {
          if (signedIn === signing) {
            Log.i(TAG, "signed in")
            pageShown = false
            main.removeCallbacks(heartbeats)
            main.postDelayed(heartbeats, 5_000)
            send(G2Service.G2_SETTING, G2Setting.ask(nextMagic()), true)
            signing.complete(true)
          }
        },
        800,
    )
  }

  private val heartbeats =
      object : Runnable {
        var n = 0

        override fun run() {
          if (signedIn?.isCompleted != true) return
          send(G2Service.DEVICE_SETTINGS, DevSettings.heartbeat(nextMagic()), false)
          if (n++ % 2 == 1) send(G2Service.EVEN_HUB, EvenHub.heartbeat(), true)
          main.postDelayed(this, 5_000)
        }
      }

  private fun onAudio(value: ByteArray) {
    val channel = mic ?: return
    if (value.size < G2_MIC_BYTES || lastAudio?.contentEquals(value) == true) return
    lastAudio = value
    channel.trySend(value.copyOf(G2_MIC_BYTES))
  }

  private fun onNotify(arm: Arm, raw: ByteArray) {
    val (service, payload) = arm.reassembler.accept(raw, arm.side) ?: return
    if (service == G2Service.G2_SETTING) {
      g2Info(payload)?.let {
        info = G2Info(it.battery ?: info?.battery, it.charging ?: info?.charging, it.firmware ?: info?.firmware)
        infoAt = System.currentTimeMillis()
        firmware = info?.firmware
        refresh()
      }
      return
    }
    if (service != G2Service.EVEN_HUB) {
      // What the glasses say that Jarvis doesn't read yet (whether they are worn is in one of these).
      if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "${arm.side}: service ${"%02x".format(service)}: ${payload.joinToString("") { "%02x".format(it) }}")
      return
    }
    val touch = g2Touch(payload) ?: return
    Log.i(TAG, "touch: $touch")
    if (touch == G2Touch.CLOSED) pageShown = false
    _touches.tryEmit(touch)
  }

  private companion object {
    const val TAG = "G2"
    /** How long a battery reading is shown for: it is only heard while an alert is up. */
    const val FRESH_MS = 30 * 60_000L
  }
}
