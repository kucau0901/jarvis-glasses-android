package app.jarvis.glasses.g2

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Debug builds only: every message the G2 sends while the Even app has it, heard without signing
 * in or writing anything but the subscriptions. To learn which gestures reach the phone when no
 * Jarvis page is up. Logged under "G2listen".
 */
@SuppressLint("MissingPermission")
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class G2Listen(private val context: Context) {
  private val main = Handler(Looper.getMainLooper())
  private val gatts = mutableListOf<BluetoothGatt>()

  fun start(minutes: Int) {
    val bluetooth = context.getSystemService(BluetoothManager::class.java)
    val paired = bluetooth.adapter.bondedDevices.filter { it.name?.contains("G2") == true }
    for (device in paired) {
      val side = if ("_L_" in device.name) "L" else "R"
      val reassembler = G2Reassembler()
      val ops = ArrayDeque<BluetoothGattDescriptor>()
      gatts += device.connectGatt(context, false, object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
          Log.i(TAG, "$side: state $newState ($status)")
          if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
          for (c in gatt.services.flatMap { it.characteristics }) {
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
            gatt.setCharacteristicNotification(c, true)
            c.getDescriptor(G2Uuid.CCCD)?.let { ops.addLast(it) }
          }
          Log.i(TAG, "$side: listening to ${ops.size} characteristics")
          next(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = next(gatt)

        private fun next(gatt: BluetoothGatt) {
          val d = ops.removeFirstOrNull() ?: return
          gatt.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
          if (c.uuid == G2Uuid.AUDIO) return
          val tag = c.uuid.toString().substring(4, 8) + "…" + c.uuid.toString().takeLast(4)
          val whole = if (c.uuid == G2Uuid.NOTIFY) reassembler.accept(value, side) else null
          if (whole == null) {
            if (c.uuid != G2Uuid.NOTIFY) Log.i(TAG, "$side $tag: ${hex(value)}")
            return
          }
          val (service, payload) = whole
          val fields = parseProto(payload).entries.joinToString(" ") { (k, v) -> "$k=${if (v is ByteArray) hex(v) else v}" }
          Log.i(TAG, "$side service ${"%02x".format(service)}: $fields")
        }
      }, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }
    Log.i(TAG, "listening for $minutes minutes to ${paired.size} arms")
    main.postDelayed({ stop() }, minutes * 60_000L)
  }

  private fun stop() {
    for (g in gatts) {
      g.disconnect()
      g.close()
    }
    gatts.clear()
    Log.i(TAG, "stopped")
  }

  private fun hex(b: ByteArray) = b.take(80).joinToString("") { "%02x".format(it) } + if (b.size > 80) "…(${b.size})" else ""

  private companion object {
    const val TAG = "G2listen"
  }
}
