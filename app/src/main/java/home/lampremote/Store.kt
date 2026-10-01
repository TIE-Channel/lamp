package home.lampremote

import android.content.Context

/** What the lamp last reported, kept so the widget can be drawn without connecting. */
class LampState(val on: Boolean, val bright: Int, val temp: Int) {
  fun mode(dps: DpMap): Mode {
    if (!on) return Mode.OFF
    // A little slack: lamps round the values they are given.
    val slack = (dps.brightMax - dps.brightMin) / 50
    return when {
      bright <= dps.brightMin + slack -> Mode.DIM
      bright >= dps.brightMax - slack -> Mode.BRIGHT
      else -> Mode.ON
    }
  }

  fun percent(dps: DpMap): Int = ((bright - dps.brightMin) * 100 / (dps.brightMax - dps.brightMin)).coerceIn(1, 100)
}

object Store {
  private fun prefs(c: Context) = c.getSharedPreferences("lamp", Context.MODE_PRIVATE)

  fun state(c: Context): LampState {
    val p = prefs(c)
    // Nobody is connected when the lamp's switch-off timer fires: assume it did.
    val timedOut = p.getLong("offAt", 0) in 1..System.currentTimeMillis()
    return LampState(p.getBoolean("on", false) && !timedOut, p.getInt("bright", 0), p.getInt("temp", 0))
  }

  /** When the lamp's own timer will switch it off; 0 for no timer. */
  fun setOffAt(c: Context, time: Long) = prefs(c).edit().putLong("offAt", time).apply()

  fun setState(c: Context, s: LampState) {
    prefs(c).edit().putBoolean("on", s.on).putInt("bright", s.bright).putInt("temp", s.temp).apply()
  }

  /** Short line under the title: progress or the last error. */
  fun status(c: Context): String = prefs(c).getString("status", "")!!

  fun setStatus(c: Context, status: String) = prefs(c).edit().putString("status", status).apply()

  /** Bluetooth address the lamp was last reached at. */
  fun address(c: Context): String? = prefs(c).getString("address", null)

  fun setAddress(c: Context, address: String) = prefs(c).edit().putString("address", address).apply()
}
