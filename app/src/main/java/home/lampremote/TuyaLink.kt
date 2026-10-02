package home.lampremote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A Bluetooth connection to the lamp. All public calls block and must run off the main thread;
 * they throw [TuyaError] with a message for the user.
 */
@SuppressLint("MissingPermission")
class TuyaLink(private val context: Context, private val config: LampConfig) : LampLink {
  override val dps = ConcurrentHashMap<Int, TuyaDp>()

  @Volatile
  override var ready = false
    private set

  private val codec = TuyaCodec(config.localKey, config.uuid, config.deviceId)
  private val events = LinkedBlockingQueue<Pair<String, Int>>()
  private val pending = ConcurrentHashMap<Int, CompletableFuture<TuyaMessage>>()
  private val replies = Executors.newSingleThreadExecutor()
  private val writeLock = Any()
  private val writeDone = Semaphore(0)
  private val reports = AtomicInteger(0)
  private val commands = AtomicInteger(0)

  @Volatile
  private var gatt: BluetoothGatt? = null
  private var writeChar: BluetoothGattCharacteristic? = null

  // --- public, blocking ----------------------------------------------------------------

  override fun open() {
    if (ready) return
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw TuyaError("в телефоне нет Bluetooth")
    if (!adapter.isEnabled) throw TuyaError("Bluetooth выключен")
    if (!context.hasBlePermissions()) throw TuyaError("нет разрешения — откройте приложение")

    var address = Store.address(context) ?: config.mac ?: scan(adapter) ?: throw TuyaError("лампа не найдена рядом")
    var error: TuyaError? = null
    for (attempt in 1..3) {
      try {
        try {
          connect(adapter, address)
        } catch (e: RuntimeException) {
          // The Bluetooth stack throws when the link dies under a call in progress.
          throw TuyaError("сбой Bluetooth: ${e.message}", retry = true)
        }
        Store.setAddress(context, address)
        return
      } catch (e: TuyaError) {
        Log.i(TAG, "connect $address attempt $attempt: ${e.message}")
        close()
        if (!e.retry) throw e
        error = e
        // Two misses in a row: the remembered address may be stale, look for the lamp again.
        if (attempt == 2) address = scan(adapter) ?: address
      }
    }
    throw error!!
  }

  override fun set(values: List<TuyaDp>) {
    Log.i(TAG, "set $values")
    val result = if (codec.protocolVersion >= 4) {
      // Protocol 4 numbers its commands and answers with the number followed by the result.
      val head = ByteBuffer.allocate(5).put(0).putInt(commands.incrementAndGet()).array()
      request(TuyaCode.DPS_V4, head + TuyaDp.encode(values, 2)).data.getOrNull(5)
    } else {
      request(TuyaCode.DPS, TuyaDp.encode(values)).data.firstOrNull()
    }
    if (result != null && result.toInt() != 0) throw TuyaError("лампа отклонила команду (код $result)")
  }

  /**
   * Asks the lamp for the given data points and waits for its report. The lamp answers only
   * when the numbers are listed: a request for "everything" is accepted and then ignored.
   * Returns false if no report came.
   */
  override fun refresh(ids: List<Int>): Boolean {
    val before = reports.get()
    request(TuyaCode.DEVICE_STATUS, ByteArray(ids.size) { ids[it].toByte() })
    val deadline = System.currentTimeMillis() + REPORT_MS
    while (reports.get() == before && System.currentTimeMillis() < deadline) Thread.sleep(10)
    return reports.get() != before
  }

  override fun close() {
    ready = false
    val g = gatt
    gatt = null
    writeChar = null
    fail(TuyaError("соединение закрыто", retry = true))
    if (g != null) {
      try {
        g.disconnect()
        g.close()
      } catch (e: Exception) {
        Log.w(TAG, "close", e)
      }
    }
  }

  // --- connection ----------------------------------------------------------------------

  private val callback = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        events.offer("connected" to status)
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        events.offer("disconnected" to status)
        if (g === gatt) dropped()
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      events.offer("services" to status)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
      events.offer("descriptor" to status)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
      writeDone.release()
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
      received(value)
    }

    @Deprecated("Called instead of the three-argument variant before Android 13")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
      received(characteristic.value ?: return)
    }
  }

  private fun connect(adapter: BluetoothAdapter, address: String) {
    events.clear()
    codec.reset()
    val g = adapter.getRemoteDevice(address).connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
      ?: throw TuyaError("Bluetooth не начал соединение", retry = true)
    gatt = g
    if (await("connected", CONNECT_MS) != BluetoothGatt.GATT_SUCCESS) throw TuyaError("не удалось соединиться с лампой", retry = true)

    if (!g.discoverServices()) throw TuyaError("Bluetooth не читает службы лампы", retry = true)
    await("services", STEP_MS)
    val characteristics = g.services.flatMap { it.characteristics }
    val notify = characteristics.firstOrNull { it.uuid == NOTIFY } ?: throw TuyaError("это не лампа Tuya: нет нужной службы")
    val write = characteristics.firstOrNull { it.uuid == WRITE } ?: throw TuyaError("это не лампа Tuya: нет нужной службы")

    g.setCharacteristicNotification(notify, true)
    val cccd = notify.getDescriptor(CCCD) ?: throw TuyaError("лампа не умеет отвечать")
    val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    val started = if (Build.VERSION.SDK_INT >= 33) {
      g.writeDescriptor(cccd, enable) == BluetoothStatusCodes.SUCCESS
    } else {
      @Suppress("DEPRECATION")
      cccd.value = enable
      @Suppress("DEPRECATION")
      g.writeDescriptor(cccd)
    }
    if (!started) throw TuyaError("Bluetooth не включил ответы лампы", retry = true)
    await("descriptor", STEP_MS)
    writeChar = write

    // Login: the lamp proves it knows the key by answering at all, then we prove the same.
    val info = try {
      request(TuyaCode.DEVICE_INFO, ByteArray(0))
    } catch (e: TuyaError) {
      throw if (e.retry) e else TuyaError("лампа не ответила на ключ — возможно, её заново привязали в Smart Life")
    }
    if (!codec.onDeviceInfo(info.data)) throw TuyaError("лампа не привязана: добавьте её в Smart Life")
    Log.i(TAG, "lamp firmware ${info.data[0]}.${info.data[1]}, protocol ${info.data[2]}.${info.data[3]}")
    val paired = request(TuyaCode.PAIR, codec.pairingRequest())
    val result = paired.data.firstOrNull()?.toInt() ?: -1
    if (result != 0 && result != 2) throw TuyaError("лампа отклонила ключ (код $result)")
    ready = true
    Log.i(TAG, "connected to $address")
  }

  private fun await(event: String, timeoutMs: Long): Int {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      val left = deadline - System.currentTimeMillis()
      val (name, status) = (if (left > 0) events.poll(left, TimeUnit.MILLISECONDS) else null)
        ?: throw TuyaError("лампа не отвечает — она включена и рядом?", retry = true)
      if (name == event) return status
      if (name == "disconnected") throw TuyaError("лампа разорвала соединение (код $status)", retry = true)
    }
  }

  private fun dropped() {
    Log.i(TAG, "disconnected")
    ready = false
    val g = gatt
    gatt = null
    writeChar = null
    fail(TuyaError("лампа разорвала соединение", retry = true))
    try {
      g?.close()
    } catch (e: Exception) {
      Log.w(TAG, "close", e)
    }
  }

  private fun fail(error: TuyaError) {
    for (seq in pending.keys) pending.remove(seq)?.completeExceptionally(error)
  }

  // --- messages ------------------------------------------------------------------------

  private fun request(code: Int, data: ByteArray): TuyaMessage {
    val future = CompletableFuture<TuyaMessage>()
    val seq = synchronized(writeLock) {
      val (seq, chunks) = codec.build(code, data)
      pending[seq] = future
      try {
        for (chunk in chunks) write(chunk)
      } catch (e: TuyaError) {
        pending.remove(seq)
        throw e
      }
      seq
    }
    try {
      return future.get(RESPONSE_MS, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
      throw TuyaError("лампа не ответила")
    } catch (e: ExecutionException) {
      throw e.cause as? TuyaError ?: TuyaError("ошибка связи с лампой", retry = true)
    } finally {
      pending.remove(seq)
    }
  }

  /** Answers a message the lamp sent on its own; never blocks the Bluetooth thread. */
  private fun reply(code: Int, data: ByteArray, to: TuyaMessage) {
    replies.execute {
      try {
        synchronized(writeLock) {
          for (chunk in codec.build(code, data, to.seq).second) write(chunk)
        }
      } catch (e: TuyaError) {
        Log.i(TAG, "reply failed: ${e.message}")
      }
    }
  }

  private fun write(chunk: ByteArray) {
    val g = gatt ?: throw TuyaError("нет соединения с лампой", retry = true)
    val c = writeChar ?: throw TuyaError("нет соединения с лампой", retry = true)
    writeDone.drainPermits()
    val started = if (Build.VERSION.SDK_INT >= 33) {
      g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
    } else {
      c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
      @Suppress("DEPRECATION")
      c.value = chunk
      @Suppress("DEPRECATION")
      g.writeCharacteristic(c)
    }
    if (!started) throw TuyaError("Bluetooth не принял данные", retry = true)
    if (!writeDone.tryAcquire(WRITE_MS, TimeUnit.MILLISECONDS)) throw TuyaError("Bluetooth завис при отправке", retry = true)
  }

  private fun received(value: ByteArray) {
    val message = try {
      codec.feed(value)
    } catch (e: TuyaError) {
      Log.i(TAG, "bad packet: ${e.message}")
      fail(e)
      return
    } ?: return
    Log.i(TAG, "rx 0x%04X #%d to #%d %s".format(message.code, message.seq, message.responseTo, Hex.of(message.data)))
    try {
      handle(message)
    } catch (e: Exception) {
      Log.w(TAG, "message 0x%04X ignored".format(message.code), e)
    }
    if (message.responseTo != 0) pending.remove(message.responseTo)?.complete(message)
  }

  private fun handle(m: TuyaMessage) {
    when (m.code) {
      TuyaCode.RECEIVE_DP -> {
        update(TuyaDp.decode(m.data, 0))
        reply(m.code, ByteArray(0), m)
      }
      TuyaCode.RECEIVE_TIME_DP -> {
        update(TuyaDp.decode(m.data, afterTimestamp(m.data, 0)))
        reply(m.code, ByteArray(0), m)
      }
      // Signed reports start with a two-byte counter and a flag byte that are echoed back.
      TuyaCode.RECEIVE_SIGN_DP -> {
        update(TuyaDp.decode(m.data, 3))
        reply(m.code, byteArrayOf(m.data[0], m.data[1], m.data[2], 0), m)
      }
      TuyaCode.RECEIVE_SIGN_TIME_DP -> {
        update(TuyaDp.decode(m.data, afterTimestamp(m.data, 3)))
        reply(m.code, byteArrayOf(m.data[0], m.data[1], m.data[2], 0), m)
      }
      // Protocol 4 reports: version, four-byte counter, flags, mode, then the data points.
      TuyaCode.RECEIVE_DP_V4 -> {
        update(TuyaDp.decode(m.data, 7, 2))
        if (m.data[5].toInt() and 0x80 == 0) reply(m.code, m.data.copyOf(7) + 0, m)
      }
      TuyaCode.RECEIVE_TIME_DP_V4 -> {
        update(TuyaDp.decode(m.data, afterTimestamp(m.data, 7), 2))
        if (m.data[5].toInt() and 0x80 == 0) reply(m.code, m.data.copyOf(7) + 0, m)
      }
      TuyaCode.TIME1_REQ -> reply(m.code, codec.time1(System.currentTimeMillis(), TimeZone.getDefault()), m)
      TuyaCode.TIME2_REQ -> {
        val now = Calendar.getInstance()
        val zone = now.timeZone.getOffset(now.timeInMillis) / 36_000
        val answer = ByteBuffer.allocate(9)
          .put((now.get(Calendar.YEAR) % 100).toByte())
          .put((now.get(Calendar.MONTH) + 1).toByte())
          .put(now.get(Calendar.DAY_OF_MONTH).toByte())
          .put(now.get(Calendar.HOUR_OF_DAY).toByte())
          .put(now.get(Calendar.MINUTE).toByte())
          .put(now.get(Calendar.SECOND).toByte())
          .put(((now.get(Calendar.DAY_OF_WEEK) + 5) % 7).toByte()) // Monday = 0
          .putShort(zone.toShort())
        reply(m.code, answer.array(), m)
      }
    }
  }

  /** Reports may begin with a time: type 0 is 13 digits of text, type 1 is four bytes. */
  private fun afterTimestamp(data: ByteArray, start: Int): Int = when (data[start].toInt()) {
    0 -> start + 1 + 13
    1 -> start + 1 + 4
    else -> throw TuyaError("непонятное время в ответе лампы")
  }

  private fun update(values: List<TuyaDp>) {
    Log.i(TAG, "report $values")
    for (dp in values) dps[dp.id] = dp
    reports.incrementAndGet()
  }

  // --- finding the lamp ----------------------------------------------------------------

  /** Looks for the lamp's advertisement; returns its address or null. */
  private fun scan(adapter: BluetoothAdapter): String? {
    val scanner = adapter.bluetoothLeScanner ?: return null
    val found = AtomicReference<String?>()
    val done = CountDownLatch(1)
    val scan = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(TUYA_COMPANY) ?: return
        if (isOurs(data)) {
          found.set(result.device.address)
          done.countDown()
        }
      }

      override fun onScanFailed(errorCode: Int) {
        Log.w(TAG, "scan failed $errorCode")
        done.countDown()
      }
    }
    val filter = ScanFilter.Builder().setManufacturerData(TUYA_COMPANY, ByteArray(0)).build()
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try {
      scanner.startScan(listOf(filter), settings, scan)
      done.await(SCAN_MS, TimeUnit.MILLISECONDS)
      scanner.stopScan(scan)
    } catch (e: Exception) {
      Log.w(TAG, "scan", e)
    }
    Log.i(TAG, "scan found ${found.get()}")
    return found.get()
  }

  /** A bound lamp broadcasts its device id encrypted with the login key; an unbound one its UUID. */
  private fun isOurs(manufacturerData: ByteArray): Boolean {
    if (TuyaCodec.advertisedId(manufacturerData, config.localKey.take(6).toByteArray()) == config.deviceId.take(16)) return true
    val productId = config.productId ?: return false
    return TuyaCodec.advertisedId(manufacturerData, productId.toByteArray()) == config.uuid
  }

  companion object {
    private val NOTIFY = UUID.fromString("00002b10-0000-1000-8000-00805f9b34fb")
    private val WRITE = UUID.fromString("00002b11-0000-1000-8000-00805f9b34fb")
    private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val TUYA_COMPANY = 0x07D0

    private const val CONNECT_MS = 8_000L
    private const val STEP_MS = 5_000L
    private const val WRITE_MS = 2_000L
    private const val RESPONSE_MS = 5_000L
    private const val REPORT_MS = 800L
    private const val SCAN_MS = 6_000L
  }
}
