package home.lampremote

/** A connection to the lamp, over Wi-Fi ([WifiLink]) or Bluetooth ([TuyaLink]). Calls block; errors are [TuyaError]. */
interface LampLink {
  /** Last values the lamp reported, by data point number. */
  val dps: MutableMap<Int, TuyaDp>

  val ready: Boolean

  /** Connects and logs in; a no-op when already connected. */
  fun open()

  /** Asks the lamp for the given data points; false if it did not report them. */
  fun refresh(ids: List<Int>): Boolean

  /** Writes data points and waits for the lamp to accept them. */
  fun set(values: List<TuyaDp>)

  fun close()
}
