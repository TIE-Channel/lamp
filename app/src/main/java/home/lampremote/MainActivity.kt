package home.lampremote

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** The remote: the two widget modes on top, then the eight buttons as they sit on the real remote. */
class MainActivity : Activity() {
  private lateinit var status: TextView
  private lateinit var dimTile: Tile
  private lateinit var brightTile: Tile
  private lateinit var pin: Button
  private var pinAskedAt = 0L

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val column = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16), dp(16), dp(16), dp(24))
    }
    column.addView(text("Лампа", 28f, bold = true))
    status = text("", 14f, Palette.MUTED)
    column.addView(status, matchWrap(top = 2))

    dimTile = Tile(this, R.drawable.lamp_w_dim, "Ночник", 56) { Lamp.dim(this) }
    brightTile = Tile(this, R.drawable.lamp_w_bright, "Максимум", 56) { Lamp.bright(this) }
    column.addView(row(listOf(dimTile, brightTile)), matchWrap(top = 16))

    column.addView(text("Пульт", 16f, bold = true), matchWrap(top = 24))
    val buttons = Cmd.values().map { cmd -> Tile(this, cmd.icon, cmd.label, 32) { Lamp.press(this, cmd) } }
    column.addView(row(buttons.subList(0, 4)), matchWrap(top = 8))
    column.addView(row(buttons.subList(4, 8)), matchWrap(top = 8))

    // Some launchers do not list the widget in their picker; this asks the launcher directly.
    pin = Button(this).apply {
      text = "Добавить виджет на рабочий стол"
      setOnClickListener { pinWidget() }
    }
    column.addView(pin, matchWrap(top = 24))

    setContentView(ScrollView(this).apply {
      fitsSystemWindows = true
      addView(column)
    })
  }

  override fun onResume() {
    super.onResume()
    Lamp.listener = ::render
    render()
    checkPinRefused()
    if (hasBlePermissions()) Lamp.refresh(this) else requestPermissions(BLE_PERMISSIONS, 1)
  }

  override fun onPause() {
    Lamp.listener = null
    super.onPause()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    render()
    if (hasBlePermissions()) Lamp.refresh(this)
  }

  private fun pinWidget() {
    val manager = getSystemService(AppWidgetManager::class.java)
    val asked = manager.isRequestPinAppWidgetSupported &&
      manager.requestPinAppWidget(ComponentName(this, LampWidgetProvider::class.java), null, null)
    if (asked) {
      pinAskedAt = SystemClock.elapsedRealtime()
    } else {
      Toast.makeText(this, "Лаунчер не умеет добавлять виджеты по запросу — добавьте из списка виджетов", Toast.LENGTH_LONG).show()
    }
  }

  /**
   * Xiaomi's launcher closes the request at once, without a word, when the app lacks the
   * "Home screen shortcuts" permission. A real confirmation dialog takes the user longer.
   */
  private fun checkPinRefused() {
    val askedAt = pinAskedAt
    pinAskedAt = 0
    if (askedAt == 0L || SystemClock.elapsedRealtime() - askedAt > REFUSED_WITHIN_MS) return
    status.postDelayed({
      if (!isFinishing && LampWidget.count(this) == 0) explainPinRefused()
    }, 700)
  }

  private fun explainPinRefused() {
    AlertDialog.Builder(this)
      .setTitle("Телефон не дал добавить виджет")
      .setMessage(
        "В HyperOS приложению нужно разрешение «Home screen shortcuts» (Ярлыки рабочего стола). " +
          "Откройте разрешения, включите его во вкладке Other permissions и нажмите кнопку добавления ещё раз.",
      )
      .setPositiveButton("Открыть разрешения") { _, _ -> openPermissions() }
      .setNegativeButton("Отмена", null)
      .show()
  }

  private fun openPermissions() {
    try {
      // Xiaomi's own page with the "Other permissions" list.
      startActivity(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName))
    } catch (e: Exception) {
      startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
  }

  private fun render() {
    pin.visibility = if (LampWidget.count(this) == 0) View.VISIBLE else View.GONE
    val config = Config.load(this)
    val dps = config?.dps ?: DpMap.DEFAULT
    val state = Store.state(this)
    val mode = state.mode(dps)
    dimTile.setActive(mode == Mode.DIM)
    brightTile.setActive(mode == Mode.BRIGHT)
    val problem = when {
      !hasBlePermissions() -> "Нужно разрешение «Устройства поблизости»"
      config == null -> "Лампа не настроена: добавьте её в Smart Life и передайте ключ (см. README)"
      else -> Store.status(this)
    }
    status.text = when {
      problem.isNotEmpty() -> problem
      state.on -> "Лампа включена, яркость ${state.percent(dps)} %"
      else -> "Лампа выключена"
    }
    status.setTextColor(if (problem.isEmpty() || problem.endsWith("…")) Palette.MUTED else Palette.DANGER)
  }

  private fun row(tiles: List<Tile>): LinearLayout = LinearLayout(this).apply {
    for ((i, tile) in tiles.withIndex()) {
      addView(tile.view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
    }
  }

  private companion object {
    const val REFUSED_WITHIN_MS = 1_500L
  }

  private fun matchWrap(top: Int) =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}
