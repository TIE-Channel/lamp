package home.lampremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

const val TAG = "LAMP"

object Hex {
  fun of(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }

  fun parse(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Shown to the user as one "Nearby devices" permission. */
val BLE_PERMISSIONS = arrayOf(
  Manifest.permission.BLUETOOTH_CONNECT,
  Manifest.permission.BLUETOOTH_SCAN,
)

fun Context.hasBlePermissions(): Boolean = BLE_PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
