package home.lampremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Turns taps into data points for the lamp and keeps the Bluetooth link only as long as it is useful. */
object Lamp {
  /** Set by the visible screen to redraw after a state change. */
  var listener: (() -> Unit)? = null

  /** What a tap should send, given the lamp's state. An empty list: nothing to do. */
  private fun interface Action {
    fun dps(state: LampState, d: DpMap): List<TuyaDp>
  }

  private val worker = Executors.newSingleThreadExecutor()
  private val timer = Executors.newSingleThreadScheduledExecutor()
  private val main = Handler(Looper.getMainLooper())

  // The link is touched only on the worker thread.
  private var link: TuyaLink? = null
  private var syncedAt = 0L

  // Taps not finished yet, and the lamp's state before the oldest of them: a failure goes back
  // to it rather than to the guess an earlier unfinished tap had shown.
  private var unfinished = 0
  private var settled: LampState? = null

  private var idle: ScheduledFuture<*>? = null
  private val idleCallbacks = ArrayList<() -> Unit>()

  // --- taps ------------------------------------------------------------------------------

  /** "Night light" of the widget: a second tap on the active button switches the lamp off. */
  fun dim(c: Context, done: ((String?) -> Unit)? = null) = run(c, done) { s, d ->
    if (s.mode(d) == Mode.DIM) off(d) else level(d, d.brightMin)
  }

  /** "Maximum" of the widget: what holding "+" on the remote ends at. */
  fun bright(c: Context, done: ((String?) -> Unit)? = null) = run(c, done) { s, d ->
    if (s.mode(d) == Mode.BRIGHT) off(d) else level(d, d.brightMax)
  }

  /** One button of the remote, as pressed on the app screen. */
  fun press(c: Context, cmd: Cmd, done: ((String?) -> Unit)? = null) = run(c, done) { s, d ->
    val step = (d.brightMax - d.brightMin) / 10
    when (cmd) {
      Cmd.POWER -> listOf(TuyaDp.bool(d.switch, !s.on))
      Cmd.NIGHT -> level(d, d.brightMin)
      // The remote's "+" and "−" do nothing while the lamp is off.
      Cmd.BR_UP -> if (s.on) listOf(TuyaDp.value(d.bright, (s.bright + step).coerceAtMost(d.brightMax))) else emptyList()
      Cmd.BR_DOWN -> if (s.on) listOf(TuyaDp.value(d.bright, (s.bright - step).coerceAtLeast(d.brightMin))) else emptyList()
      Cmd.COLD -> listOf(TuyaDp.value(d.temp ?: unsupported(cmd), d.tempMax))
      Cmd.WARM -> listOf(TuyaDp.value(d.temp ?: unsupported(cmd), 0))
      Cmd.CCT -> {
        // Warm -> neutral -> cold -> warm, as the remote's button cycles.
        val next = when {
          s.temp < d.tempMax / 4 -> d.tempMax / 2
          s.temp < d.tempMax * 3 / 4 -> d.tempMax
          else -> 0
        }
        listOf(TuyaDp.value(d.temp ?: unsupported(cmd), next))
      }
      Cmd.TIMER -> if (s.on) listOf(TuyaDp.value(d.countdown ?: unsupported(cmd), 60)) else emptyList()
    }
  }

  /** Reads the lamp's state without changing anything; the app does it when it comes to the front. */
  fun refresh(c: Context) {
    if (Config.load(c) != null) run(c, null) { _, _ -> emptyList() }
  }

  /** A tap by its name: "dim", "bright" or "press:<button>". Returns false for an unknown name. */
  fun act(c: Context, action: String, done: (String?) -> Unit): Boolean {
    val button = Cmd.values().firstOrNull { "press:${it.name}" == action }
    when {
      action == "dim" -> dim(c, done)
      action == "bright" -> bright(c, done)
      button != null -> press(c, button, done)
      else -> return false
    }
    return true
  }

  private fun off(d: DpMap) = listOf(TuyaDp.bool(d.switch, false))

  private fun level(d: DpMap, bright: Int) = listOf(TuyaDp.bool(d.switch, true), TuyaDp.value(d.bright, bright))

  private fun unsupported(cmd: Cmd): Nothing = throw TuyaError("«${cmd.label}»: лампа этого не умеет")

  // --- link lifetime -----------------------------------------------------------------------

  /** Calls back once the link has been idle long enough to be closed. */
  fun whenIdle(callback: () -> Unit) {
    synchronized(idleCallbacks) { idleCallbacks += callback }
    touch()
  }

  /** Closes the link now, then calls back. */
  fun drop(callback: () -> Unit) {
    idle?.cancel(false)
    worker.execute {
      link?.close()
      link = null
      main.post(callback)
    }
  }

  /**
   * Keeps the link for a few more seconds, so that the next tap acts without reconnecting.
   * Not longer: the lamp talks to one phone at a time, and another one may be waiting.
   */
  private fun touch() {
    idle?.cancel(false)
    idle = timer.schedule({
      worker.execute {
        link?.close()
        link = null
        val callbacks = synchronized(idleCallbacks) { idleCallbacks.toList().also { idleCallbacks.clear() } }
        main.post { callbacks.forEach { it() } }
      }
    }, IDLE_MS, TimeUnit.MILLISECONDS)
  }

  // --- execution ---------------------------------------------------------------------------

  private fun run(context: Context, done: ((String?) -> Unit)?, action: Action) {
    val app = context.applicationContext
    val config = Config.load(app)
    val before = Store.state(app)
    val asked = SystemClock.elapsedRealtime()
    synchronized(this) {
      if (unfinished++ == 0) settled = before
    }
    // Show the expected result at once; the lamp's real state corrects it a moment later.
    if (config != null) {
      try {
        Store.setState(app, applied(before, action.dps(before, config.dps), config.dps))
      } catch (e: TuyaError) {
        // Reported below, from the real attempt.
      }
    }
    Store.setStatus(app, "связываюсь с лампой…")
    changed(app)
    idle?.cancel(false)
    worker.execute {
      val error = try {
        if (config == null) throw TuyaError("лампа не настроена: добавьте её в Smart Life и запустите tools/tuya_key.py")
        // A tap that waited behind a lamp that would not answer is no longer what the person wants:
        // without this, a lamp coming back would replay every tap made in the meantime.
        if (SystemClock.elapsedRealtime() - asked > STALE_MS) throw TuyaError("лампа не ответила вовремя, нажатие отменено")
        try {
          perform(app, config, action, before)
        } catch (e: TuyaError) {
          if (!e.retry) throw e
          // The link had died unnoticed: one more try on a new one.
          link?.close()
          link = null
          perform(app, config, action, before)
        }
        null
      } catch (e: TuyaError) {
        Log.i(TAG, "command failed: ${e.message}")
        // Drop the expected state: back to what the lamp last said.
        val current = link
        val known = synchronized(this) { settled } ?: before
        if (current != null && current.ready) sync(app, current, config!!.dps, known) else Store.setState(app, known)
        e.message
      }
      synchronized(this) {
        // What is stored now came from the lamp, or is what was known before these taps.
        settled = Store.state(app)
        unfinished--
      }
      Store.setStatus(app, error ?: "")
      changed(app)
      touch()
      if (done != null) main.post { done(error) }
    }
  }

  /** [before] is the state prior to this tap; the stored one already shows the expected result. */
  private fun perform(app: Context, config: LampConfig, action: Action, before: LampState) {
    val d = config.dps
    val link = link ?: TuyaLink(app, config).also { link = it }
    link.open()
    // Ask the lamp where it stands: its remote or another phone may have changed it.
    if (System.currentTimeMillis() - syncedAt > FRESH_MS) {
      // A real answer also replaces the guess about the lamp's switch-off timer.
      if (link.refresh(listOfNotNull(d.switch, d.bright, d.temp))) Store.setOffAt(app, 0)
      syncedAt = System.currentTimeMillis()
    }
    val state = stateOf(link, d, before)
    val dps = action.dps(state, d)
    if (dps.isNotEmpty()) {
      link.set(dps)
      // The lamp does not report what it was just told: take it as done.
      for (dp in dps) {
        link.dps[dp.id] = dp
        when (dp.id) {
          d.switch -> Store.setOffAt(app, 0)
          d.countdown -> {
            Store.setOffAt(app, if (dp.int > 0) System.currentTimeMillis() + dp.int * 1000L else 0)
            // Drop the highlight when the timer fires.
            if (dp.int > 0) main.postDelayed({ changed(app) }, dp.int * 1000L + 500)
          }
        }
      }
      syncedAt = System.currentTimeMillis()
    }
    sync(app, link, d, before)
  }

  private fun stateOf(link: TuyaLink, d: DpMap, fallback: LampState) = LampState(
    on = link.dps[d.switch]?.bool ?: fallback.on,
    bright = link.dps[d.bright]?.int ?: fallback.bright,
    temp = d.temp?.let { link.dps[it]?.int } ?: fallback.temp,
  )

  private fun applied(s: LampState, dps: List<TuyaDp>, d: DpMap): LampState {
    var on = s.on
    var bright = s.bright
    var temp = s.temp
    for (dp in dps) {
      when (dp.id) {
        d.switch -> on = dp.bool
        d.bright -> bright = dp.int
        d.temp -> temp = dp.int
      }
    }
    return LampState(on, bright, temp)
  }

  private fun sync(app: Context, link: TuyaLink, d: DpMap, fallback: LampState) {
    Store.setState(app, stateOf(link, d, fallback))
  }

  private fun changed(c: Context) {
    LampWidget.refresh(c)
    if (Looper.myLooper() == Looper.getMainLooper()) listener?.invoke() else main.post { listener?.invoke() }
  }

  private const val IDLE_MS = 5_000L

  /** A tap that could not even start within this time is dropped. */
  private const val STALE_MS = 15_000L

  /** How long a state read from the lamp is trusted while the link stays open. */
  private const val FRESH_MS = 1_500L
}
