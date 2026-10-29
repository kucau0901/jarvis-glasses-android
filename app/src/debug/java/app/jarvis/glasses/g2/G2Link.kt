package app.jarvis.glasses.g2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Spike: holds a pair of G2 glasses over Bluetooth, without the Even app. Connects both arms, signs
 * in, keeps the link alive, draws a page of text, logs touches and records the mic's LC3 frames to
 * a file. Everything is logged under the tag "G2".
 *
 * It sends only sign-in, time and heartbeats to device settings, and the EvenHub page and mic: no
 * dashboard, onboarding or "Hey Even" settings, so the glasses are as the Even app left them.
 */
@SuppressLint("MissingPermission")
class G2Link(context: Context) {
  private val context = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val writer = Executors.newSingleThreadExecutor()
  private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

  private class Arm(val side: String) {
    var gatt: BluetoothGatt? = null
    var write: BluetoothGattCharacteristic? = null
    val ops = ArrayDeque<() -> Unit>()
    var busy = false
    var ready = false
    val reassembler = G2Reassembler()
  }

  private val left = Arm("L")
  private val right = Arm("R")
  private var syncId = 0
  private var magic = 0
  private var signedIn = false
  private var pageShown = false
  private var scanning: ScanCallback? = null

  private val mic = ByteArrayOutputStream()
  private var micOn = false
  private var lastFrame: ByteArray? = null
  private val micCounts = mutableMapOf("L" to 0, "R" to 0, "dup" to 0)
  private var micStartedAt = 0L

  fun connect() {
    if (left.gatt != null || right.gatt != null) return log("already connecting or connected")
    val bonded = adapter.bondedDevices.filter { it.name?.contains("G2") == true }
    log("bonded G2 devices: ${bonded.joinToString { "${it.name} ${it.address}" }.ifEmpty { "none" }}")
    val l = bonded.firstOrNull { it.name.contains("_L_") }
    val r = bonded.firstOrNull { it.name.contains("_R_") }
    if (l != null && r != null) {
      open(left, l)
      open(right, r)
    } else {
      scan()
    }
  }

  private fun scan() {
    val scanner = adapter.bluetoothLeScanner ?: return log("no LE scanner (Bluetooth off?)")
    val found = mutableMapOf<String, BluetoothDevice>()
    val callback =
        object : ScanCallback() {
          override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!name.contains("G2")) return
            val side = if (name.contains("_L_")) "L" else if (name.contains("_R_")) "R" else return
            if (found.put(side, result.device) == null) log("seen $name ${result.device.address} rssi ${result.rssi}")
            if (found.size == 2) {
              stopScan()
              open(left, found.getValue("L"))
              open(right, found.getValue("R"))
            }
          }

          override fun onScanFailed(errorCode: Int) = log("scan failed: $errorCode")
        }
    scanning = callback
    scanner.startScan(callback)
    log("scanning for G2…")
    main.postDelayed({ if (scanning === callback) { stopScan(); log("scan gave up; seen: ${found.keys}") } }, 20_000)
  }

  private fun stopScan() {
    scanning?.let { adapter.bluetoothLeScanner?.stopScan(it) }
    scanning = null
  }

  private fun open(arm: Arm, device: BluetoothDevice) {
    log("${arm.side}: connecting to ${device.name} ${device.address} (bond ${device.bondState})")
    arm.gatt = device.connectGatt(context, false, callback(arm), BluetoothDevice.TRANSPORT_LE)
  }

  private fun callback(arm: Arm) =
      object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          main.post {
            log("${arm.side}: state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
              if (!gatt.requestMtu(247)) gatt.discoverServices()
              gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
              arm.ready = false
              arm.write = null
              if (arm.gatt === gatt) {
                gatt.close()
                arm.gatt = null
              }
              signedIn = false
              pageShown = false
            }
          }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
          main.post {
            log("${arm.side}: mtu $mtu")
            gatt.discoverServices()
          }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          main.post {
            // By characteristic, not service: a firmware update in 2026 moved them between services.
            val chars = gatt.services.flatMap { it.characteristics }
            log("${arm.side}: services ${gatt.services.map { it.uuid.toString().take(8) + "…" + it.uuid.toString().takeLast(4) }}")
            for (c in chars) {
              when (c.uuid) {
                G2Uuid.WRITE -> arm.write = c
                G2Uuid.NOTIFY, G2Uuid.AUDIO -> enqueue(arm) { subscribe(arm, gatt, c) }
              }
            }
            enqueue(arm) {
              arm.ready = arm.write != null
              log("${arm.side}: ready ${arm.ready}")
              arm.busy = false
              runNext(arm)
              if (left.ready && right.ready && !signedIn) signIn()
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
          when (c.uuid) {
            G2Uuid.AUDIO -> main.post { onAudio(arm.side, value) }
            G2Uuid.NOTIFY -> main.post { onNotify(arm, value) }
          }
        }
      }

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
      log("${arm.side}: couldn't subscribe to ${c.uuid}")
      arm.busy = false
      runNext(arm)
    }
  }

  private fun send(service: Byte, payload: ByteArray, reserveFlag: Boolean, toLeft: Boolean = false, toRight: Boolean = true) {
    val packets = g2Packets(syncId++ and 0xFF, service, payload, reserveFlag)
    writer.execute {
      for (p in packets) {
        if (toRight) write(right, p)
        if (toLeft) write(left, p)
        Thread.sleep(8)
      }
    }
  }

  private fun write(arm: Arm, packet: ByteArray) {
    val gatt = arm.gatt ?: return
    val c = arm.write ?: return
    repeat(5) {
      if (gatt.writeCharacteristic(c, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0) return
      Thread.sleep(8)
    }
    log("${arm.side}: write dropped")
  }

  private fun nextMagic() = magic++ and 0xFF

  private fun signIn() {
    signedIn = true
    log("signing in")
    send(G2Service.DEVICE_SETTINGS, DevSettings.auth(nextMagic()), false, toLeft = true, toRight = false)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.auth(nextMagic()), false) }, 200)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.rightTakesCommands(nextMagic()), false) }, 400)
    main.postDelayed({ send(G2Service.DEVICE_SETTINGS, DevSettings.timeSync(nextMagic()), false, toLeft = true) }, 600)
    main.postDelayed({ log("signed in (as far as we can tell)"); heartbeat() }, 800)
  }

  private val heartbeats =
      object : Runnable {
        var n = 0
        override fun run() {
          if (!signedIn) return
          send(G2Service.DEVICE_SETTINGS, DevSettings.heartbeat(nextMagic()), false)
          if (n++ % 2 == 1) send(G2Service.EVEN_HUB, EvenHub.heartbeat(), true)
          main.postDelayed(this, 5_000)
        }
      }

  private fun heartbeat() {
    main.removeCallbacks(heartbeats)
    main.postDelayed(heartbeats, 5_000)
  }

  /** gesture_ctrl's opening message, which MentraOS sends after sign-in. Only if touches don't come. */
  fun gestureInit() = send(G2Service.GESTURE_CTRL, Proto().int(1, 0).int(2, nextMagic()).build(), true)

  fun show(text: String) {
    if (!signedIn) return log("not signed in")
    send(G2Service.EVEN_HUB, EvenHub.page(text, first = !pageShown, magic = nextMagic()), true)
    log("page ${if (pageShown) "rebuilt" else "created"}: $text")
    pageShown = true
  }

  fun update(text: String) {
    send(G2Service.EVEN_HUB, EvenHub.updateText(text), true)
    log("text updated: $text")
  }

  fun micOn() {
    if (!pageShown) return log("the mic needs a page; show one first")
    mic.reset()
    micRaw.reset()
    micCounts.keys.forEach { micCounts[it] = 0 }
    lastFrame = null
    micOn = true
    micStartedAt = System.currentTimeMillis()
    // The firmware needs an off→on edge to arm the mic.
    send(G2Service.EVEN_HUB, EvenHub.mic(false, nextMagic()), true)
    main.postDelayed({ send(G2Service.EVEN_HUB, EvenHub.mic(true, nextMagic()), true); log("mic on") }, 500)
  }

  fun micOff() {
    send(G2Service.EVEN_HUB, EvenHub.mic(false, nextMagic()), true)
    micOn = false
    val ms = System.currentTimeMillis() - micStartedAt
    val file = File(context.getExternalFilesDir(null), "g2-mic.lc3")
    file.writeBytes(mic.toByteArray())
    File(context.getExternalFilesDir(null), "g2-mic.raw").writeBytes(micRaw.toByteArray())
    log("mic off after ${ms} ms: ${mic.size()} bytes, notifications $micCounts → ${file.absolutePath}")
  }

  private fun onAudio(side: String, value: ByteArray) {
    if (!micOn) return
    if (lastFrame?.contentEquals(value) == true) {
      micCounts["dup"] = micCounts.getValue("dup") + 1
      return
    }
    lastFrame = value
    micCounts[side] = micCounts.getValue(side) + 1
    if (micCounts.getValue(side) == 1) log("$side: first audio notification, ${value.size} bytes: ${value.take(12).hex()}")
    mic.write(value, 0, minOf(value.size, 200))
    micRaw.write(value)
  }

  private val micRaw = ByteArrayOutputStream()

  private fun onNotify(arm: Arm, raw: ByteArray) {
    val (service, payload) = arm.reassembler.accept(raw, arm.side) ?: return
    if (service == G2Service.EVEN_HUB) {
      val touch = g2Touch(payload)
      if (touch != null) return log("${arm.side}: TOUCH $touch")
    }
    val hex = payload.hex()
    if (hex.contains("080c7a02100c") || hex.contains("080652020808")) return // heartbeat replies
    log("${arm.side}: service ${"%02x".format(service)} ${payload.size} B ${hex.take(64)}")
  }

  /** Gives the glasses back: the mic off, the page closed, both arms disconnected. */
  fun release() {
    main.removeCallbacks(heartbeats)
    if (micOn) micOff()
    if (pageShown) send(G2Service.EVEN_HUB, EvenHub.shutdown(), true)
    pageShown = false
    signedIn = false
    stopScan()
    main.postDelayed({
      for (arm in listOf(left, right)) {
        arm.gatt?.disconnect()
        arm.gatt?.close()
        arm.gatt = null
        arm.ready = false
        arm.write = null
        arm.ops.clear()
        arm.busy = false
      }
      log("released")
    }, 500)
  }

  private fun log(message: String) {
    Log.i("G2", message)
  }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

private fun List<Byte>.hex() = joinToString("") { "%02x".format(it) }
