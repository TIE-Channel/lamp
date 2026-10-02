package home.lampremote

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tuya's local Wi-Fi protocol, version 3.4, as the lamp speaks it on TCP port 6668. Pure functions,
 * so that tests can hold them against messages built by the reference implementation
 * (tools/gen_lan_vectors.py, from tinytuya).
 *
 * A message: 0x000055AA, sequence number, command, length; the payload encrypted with AES-ECB;
 * an HMAC-SHA256 of everything before it; 0x0000AA55. Messages from the lamp carry a return code
 * in front of the payload. The first three messages agree on a session key, made from two random
 * numbers and the lamp's local key; everything after is encrypted with it.
 */
object TuyaLan {
  const val PORT = 6668

  const val SESS_KEY_NEG_START = 3
  const val SESS_KEY_NEG_RESP = 4
  const val SESS_KEY_NEG_FINISH = 5
  const val STATUS = 8
  const val CONTROL_NEW = 13
  const val DP_QUERY_NEW = 16

  private const val PREFIX = 0x000055AA
  private const val SUFFIX = 0x0000AA55
  private const val HEADER = 16
  private const val TRAILER = 36 // HMAC and suffix

  // Commands that change something carry the protocol version in front of their payload.
  private val VERSION_HEADER = "3.4".toByteArray() + ByteArray(12)

  class Message(val seq: Int, val cmd: Int, val retcode: Int, val payload: ByteArray)

  fun pack(seq: Int, cmd: Int, plain: ByteArray, key: ByteArray): ByteArray {
    val body = encrypt(key, plain)
    val head = ByteBuffer.allocate(HEADER).putInt(PREFIX).putInt(seq).putInt(cmd).putInt(body.size + TRAILER).array()
    val signed = head + body
    return signed + hmac(key, signed) + ByteBuffer.allocate(4).putInt(SUFFIX).array()
  }

  /** Reads one message; throws [TuyaError] if it is not signed with [key]. */
  fun read(input: InputStream, key: ByteArray): Message {
    val head = readFully(input, HEADER)
    val header = ByteBuffer.wrap(head)
    if (header.int != PREFIX) throw TuyaError("непонятный ответ лампы по Wi-Fi", retry = true)
    val seq = header.int
    val cmd = header.int
    val length = header.int
    if (length < TRAILER || length > 64 * 1024) throw TuyaError("непонятный ответ лампы по Wi-Fi", retry = true)
    val rest = readFully(input, length)
    val signedEnd = length - TRAILER
    val expected = hmac(key, head + rest.copyOfRange(0, signedEnd))
    if (!MessageDigest.isEqual(expected, rest.copyOfRange(signedEnd, signedEnd + 32))) throw TuyaError("лампа не приняла ключ")
    // A return code comes first, unless the rest would then not be whole AES blocks.
    val hasRetcode = signedEnd >= 4 && (signedEnd - 4) % 16 == 0
    val retcode = if (hasRetcode) ByteBuffer.wrap(rest, 0, 4).int else 0
    val encrypted = rest.copyOfRange(if (hasRetcode) 4 else 0, signedEnd)
    var payload = if (encrypted.isEmpty()) ByteArray(0) else decrypt(key, encrypted)
    if (payload.size >= VERSION_HEADER.size && payload.copyOfRange(0, 3).contentEquals(VERSION_HEADER.copyOfRange(0, 3))) {
      payload = payload.copyOfRange(VERSION_HEADER.size, payload.size)
    }
    return Message(seq, cmd, retcode, payload)
  }

  /** The lamp's random number from its answer to the first message, once it has proved it knows the key. */
  fun remoteNonce(localKey: ByteArray, localNonce: ByteArray, answer: ByteArray): ByteArray? {
    if (answer.size < 48) return null
    if (!MessageDigest.isEqual(answer.copyOfRange(16, 48), hmac(localKey, localNonce))) return null
    return answer.copyOfRange(0, 16)
  }

  fun finishPayload(localKey: ByteArray, remoteNonce: ByteArray): ByteArray = hmac(localKey, remoteNonce)

  fun sessionKey(localKey: ByteArray, localNonce: ByteArray, remoteNonce: ByteArray): ByteArray {
    val mixed = ByteArray(16) { (localNonce[it].toInt() xor remoteNonce[it].toInt()).toByte() }
    val cipher = Cipher.getInstance("AES/ECB/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(localKey, "AES"))
    return cipher.doFinal(mixed)
  }

  fun queryPayload(): ByteArray = "{}".toByteArray()

  /** Written by hand rather than with JSONObject: the lamp's firmware and the tests expect this exact order. */
  fun controlPayload(values: List<TuyaDp>, unixTime: Long): ByteArray {
    val dps = values.joinToString(",") { dp ->
      val value = when (dp.type) {
        TuyaDp.BOOL -> dp.bool.toString()
        TuyaDp.VALUE, TuyaDp.ENUM -> dp.int.toString()
        else -> throw TuyaError("эту команду по Wi-Fi не отправить")
      }
      "\"${dp.id}\":$value"
    }
    return VERSION_HEADER + "{\"protocol\":5,\"t\":$unixTime,\"data\":{\"dps\":{$dps}}}".toByteArray()
  }

  /** Data points from an answer: {"dps":{...}} or, in reports, {"data":{"dps":{...}}}. Text values are skipped. */
  fun parseDps(payload: ByteArray): List<TuyaDp> {
    if (payload.isEmpty()) return emptyList()
    val json = JSONObject(String(payload))
    val dps = json.optJSONObject("dps") ?: json.optJSONObject("data")?.optJSONObject("dps") ?: return emptyList()
    val out = ArrayList<TuyaDp>()
    for (key in dps.keys()) {
      val id = key.toIntOrNull() ?: continue
      when (val value = dps.get(key)) {
        is Boolean -> out += TuyaDp.bool(id, value)
        is Number -> out += TuyaDp.value(id, value.toInt())
      }
    }
    return out
  }

  private fun encrypt(key: ByteArray, data: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(data)
  }

  private fun decrypt(key: ByteArray, data: ByteArray): ByteArray = try {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    cipher.doFinal(data)
  } catch (e: Exception) {
    throw TuyaError("лампа не приняла ключ")
  }

  private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
  }

  private fun readFully(input: InputStream, size: Int): ByteArray {
    val out = ByteArray(size)
    var read = 0
    while (read < size) {
      val n = input.read(out, read, size - read)
      if (n < 0) throw EOFException()
      read += n
    }
    return out
  }
}

/**
 * The lamp over the home Wi-Fi. This is how Smart Life reaches it day to day: the lamp keeps its
 * Bluetooth only for a couple of minutes after it gets power, then it is on Wi-Fi alone.
 */
class WifiLink(private val context: Context, private val config: LampConfig) : LampLink {
  override val dps = ConcurrentHashMap<Int, TuyaDp>()

  @Volatile
  override var ready = false
    private set

  private val localKey = config.localKey.toByteArray()
  private var key = localKey
  private var seq = 1
  private var socket: Socket? = null
  private var input: InputStream? = null
  private var output: OutputStream? = null

  override fun open() {
    if (ready) return
    // The address it had last time, or the one from the config file, or a look around the network.
    val known = Store.lanAddress(context) ?: config.ip
    if (known != null) {
      try {
        connect(known)
        return
      } catch (e: TuyaError) {
        close()
        if (!e.retry) throw e
        Log.i(TAG, "Wi-Fi $known: ${e.message}")
      }
    }
    // The router may have given the lamp another address: try every address that has the lamp's
    // port open; the lamp is the one that accepts our key.
    for (ip in discover().filter { it != known }) {
      try {
        connect(ip)
        return
      } catch (e: TuyaError) {
        close()
        Log.i(TAG, "Wi-Fi $ip: ${e.message}")
      }
    }
    throw TuyaError("лампа не найдена в сети Wi-Fi", retry = true)
  }

  override fun refresh(ids: List<Int>): Boolean {
    send(TuyaLan.DP_QUERY_NEW, TuyaLan.queryPayload())
    update(TuyaLan.parseDps(receive(TuyaLan.DP_QUERY_NEW).payload))
    return true
  }

  override fun set(values: List<TuyaDp>) {
    Log.i(TAG, "set $values")
    send(TuyaLan.CONTROL_NEW, TuyaLan.controlPayload(values, System.currentTimeMillis() / 1000))
    val answer = receive(TuyaLan.CONTROL_NEW)
    if (answer.retcode != 0) throw TuyaError("лампа отклонила команду (код ${answer.retcode})")
  }

  override fun close() {
    ready = false
    try {
      socket?.close()
    } catch (e: IOException) {
      // Already gone.
    }
    socket = null
    input = null
    output = null
  }

  private fun connect(ip: String) {
    val s = Socket()
    try {
      s.connect(InetSocketAddress(ip, TuyaLan.PORT), CONNECT_MS)
      s.soTimeout = RESPONSE_MS
      s.tcpNoDelay = true
    } catch (e: IOException) {
      s.close()
      throw TuyaError("лампа не отвечает по Wi-Fi", retry = true)
    }
    socket = s
    input = BufferedInputStream(s.getInputStream())
    output = s.getOutputStream()
    key = localKey
    seq = 1

    // Session key: each side sends a random number, the lamp proves it knows the local key.
    val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
    send(TuyaLan.SESS_KEY_NEG_START, nonce)
    val remote = TuyaLan.remoteNonce(localKey, nonce, receive(TuyaLan.SESS_KEY_NEG_RESP).payload)
      ?: throw TuyaError("лампа не приняла ключ — возможно, её заново привязали в Smart Life")
    send(TuyaLan.SESS_KEY_NEG_FINISH, TuyaLan.finishPayload(localKey, remote))
    key = TuyaLan.sessionKey(localKey, nonce, remote)
    ready = true
    Store.setLanAddress(context, ip)
    Log.i(TAG, "connected to $ip over Wi-Fi")
  }

  private fun send(cmd: Int, plain: ByteArray) {
    val out = output ?: throw TuyaError("нет соединения с лампой", retry = true)
    try {
      out.write(TuyaLan.pack(seq++, cmd, plain, key))
      out.flush()
    } catch (e: IOException) {
      close()
      throw TuyaError("связь с лампой по Wi-Fi прервалась", retry = true)
    }
  }

  /** Waits for the answer to [cmd]; reports the lamp sends meanwhile update the data points. */
  private fun receive(cmd: Int): TuyaLan.Message {
    val stream = input ?: throw TuyaError("нет соединения с лампой", retry = true)
    while (true) {
      val message = try {
        TuyaLan.read(stream, key)
      } catch (e: SocketTimeoutException) {
        close()
        throw TuyaError("лампа не ответила", retry = true)
      } catch (e: IOException) {
        close()
        throw TuyaError("связь с лампой по Wi-Fi прервалась", retry = true)
      }
      if (message.cmd == cmd) return message
      if (message.cmd == TuyaLan.STATUS) update(TuyaLan.parseDps(message.payload))
    }
  }

  private fun update(values: List<TuyaDp>) {
    if (values.isEmpty()) return
    Log.i(TAG, "report $values")
    for (dp in values) dps[dp.id] = dp
  }

  /**
   * Addresses on the home network with Tuya's port open, nearest answers first. The lamp also
   * announces itself by broadcast, but routers and phones often drop those on Wi-Fi.
   */
  private fun discover(): List<String> {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
    val network = connectivity.activeNetwork ?: return emptyList()
    val own = connectivity.getLinkProperties(network)?.linkAddresses
      ?.map { it.address }?.firstOrNull { it is Inet4Address } ?: return emptyList()
    // Home networks are /24: the same first three numbers as the phone's own address.
    val prefix = own.address.copyOf(3)
    val hosts = (1..254).filter { it != (own.address[3].toInt() and 0xFF) }
    val pool = Executors.newFixedThreadPool(SCAN_THREADS)
    val done = ExecutorCompletionService<String?>(pool)
    try {
      for (host in hosts) {
        done.submit {
          val ip = InetAddress.getByAddress(prefix + host.toByte()).hostAddress!!
          try {
            Socket().use { it.connect(InetSocketAddress(ip, TuyaLan.PORT), SCAN_MS) }
            ip
          } catch (e: IOException) {
            null
          }
        }
      }
      val open = ArrayList<String>()
      repeat(hosts.size) { done.poll(SCAN_MS * 4L, TimeUnit.MILLISECONDS)?.get()?.let { open += it } }
      Log.i(TAG, "Tuya port open at ${open.ifEmpty { listOf("no address") }.joinToString()}")
      return open
    } finally {
      pool.shutdownNow()
    }
  }

  companion object {
    private const val CONNECT_MS = 1_500
    private const val RESPONSE_MS = 3_000
    private const val SCAN_MS = 400
    private const val SCAN_THREADS = 64
  }
}
