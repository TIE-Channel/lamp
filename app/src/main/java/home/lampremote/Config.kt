package home.lampremote

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Numbers of the lamp's data points and their ranges. */
class DpMap(
  val switch: Int,
  val bright: Int,
  val temp: Int?,
  val countdown: Int?,
  val brightMin: Int,
  val brightMax: Int,
  val tempMax: Int,
) {
  companion object {
    /** The usual set of a Tuya Bluetooth light. */
    val DEFAULT = DpMap(switch = 20, bright = 22, temp = 23, countdown = 26, brightMin = 10, brightMax = 1000, tempMax = 1000)
  }
}

/**
 * The lamp's identity in the Tuya cloud. The lamp only obeys a phone that knows its local key,
 * which exists once the lamp has been added in the Smart Life app; tools/tuya_key.py fetches it
 * and writes this file into the app's private storage.
 */
class LampConfig(
  val deviceId: String,
  val uuid: String,
  val localKey: String,
  val mac: String?,
  val productId: String?,
  /** The lamp's address on the home Wi-Fi, if known; the app also finds it by itself. */
  val ip: String?,
  val dps: DpMap,
)

object Config {
  const val FILE = "config.json"

  fun load(context: Context): LampConfig? {
    val file = File(context.filesDir, FILE)
    if (!file.exists()) return null
    return try {
      val json = JSONObject(file.readText())
      val d = json.optJSONObject("dps")
      val def = DpMap.DEFAULT
      fun optional(key: String, fallback: Int?): Int? = if (d == null || !d.has(key)) fallback else if (d.isNull(key)) null else d.getInt(key)
      LampConfig(
        deviceId = json.getString("deviceId"),
        uuid = json.getString("uuid"),
        localKey = json.getString("localKey"),
        mac = json.optString("mac").ifEmpty { null },
        productId = json.optString("productId").ifEmpty { null },
        ip = json.optString("ip").ifEmpty { null },
        dps = DpMap(
          switch = d?.optInt("switch", def.switch) ?: def.switch,
          bright = d?.optInt("bright", def.bright) ?: def.bright,
          temp = optional("temp", def.temp),
          countdown = optional("countdown", def.countdown),
          brightMin = d?.optInt("brightMin", def.brightMin) ?: def.brightMin,
          brightMax = d?.optInt("brightMax", def.brightMax) ?: def.brightMax,
          tempMax = d?.optInt("tempMax", def.tempMax) ?: def.tempMax,
        ),
      )
    } catch (e: Exception) {
      Log.w(TAG, "bad $FILE", e)
      null
    }
  }
}
