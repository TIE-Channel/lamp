package home.lampremote

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Tuya BLE protocol v3, without any Android dependency: framing, encryption and data points.
 * Follows the open implementation in PlusPlus-ua/ha_tuya_ble.
 */
object TuyaCode {
  const val DEVICE_INFO = 0x0000
  const val PAIR = 0x0001
  const val DPS = 0x0002
  const val DEVICE_STATUS = 0x0003
  const val DPS_V4 = 0x0027
  const val RECEIVE_DP = 0x8001
  const val RECEIVE_TIME_DP = 0x8003
  const val RECEIVE_SIGN_DP = 0x8004
  const val RECEIVE_SIGN_TIME_DP = 0x8005
  const val RECEIVE_DP_V4 = 0x8006
  const val RECEIVE_TIME_DP_V4 = 0x8007
  const val TIME1_REQ = 0x8011
  const val TIME2_REQ = 0x8012
}

/** A data point: one numbered property of the lamp (switch, brightness, ...). */
class TuyaDp(val id: Int, val type: Int, val value: ByteArray) {
  val int: Int
    get() = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
  val bool: Boolean
    get() = int != 0

  override fun toString(): String = when (type) {
    BOOL -> "$id=${bool}"
    VALUE, ENUM -> "$id=$int"
    else -> "$id=0x${Hex.of(value)}"
  }

  companion object {
    const val RAW = 0
    const val BOOL = 1
    const val VALUE = 2
    const val STRING = 3
    const val ENUM = 4
    const val BITMAP = 5

    fun bool(id: Int, on: Boolean) = TuyaDp(id, BOOL, byteArrayOf(if (on) 1 else 0))
    fun value(id: Int, v: Int) = TuyaDp(id, VALUE, ByteBuffer.allocate(4).putInt(v).array())
    fun enum(id: Int, v: Int) = TuyaDp(id, ENUM, byteArrayOf(v.toByte()))

    /** [lengthSize] is one byte in protocol 3 and two in protocol 4. */
    fun encode(dps: List<TuyaDp>, lengthSize: Int = 1): ByteArray {
      val out = ByteArrayOutputStream()
      for (dp in dps) {
        out.write(dp.id)
        out.write(dp.type)
        if (lengthSize == 2) out.write(dp.value.size shr 8)
        out.write(dp.value.size)
        out.write(dp.value)
      }
      return out.toByteArray()
    }

    fun decode(data: ByteArray, start: Int, lengthSize: Int = 1): List<TuyaDp> {
      val out = ArrayList<TuyaDp>()
      var pos = start
      while (data.size - pos >= 2 + lengthSize) {
        val id = data[pos].toInt() and 0xFF
        val type = data[pos + 1].toInt() and 0xFF
        var len = data[pos + 2].toInt() and 0xFF
        if (lengthSize == 2) len = (len shl 8) or (data[pos + 3].toInt() and 0xFF)
        val from = pos + 2 + lengthSize
        val end = from + len
        if (type > BITMAP || end > data.size) throw TuyaError("непонятный ответ лампы")
        out += TuyaDp(id, type, data.copyOfRange(from, end))
        pos = end
      }
      return out
    }
  }
}

class TuyaMessage(val seq: Int, val responseTo: Int, val code: Int, val data: ByteArray)

/** [retry] marks radio-level failures that a new connection attempt may cure. */
class TuyaError(message: String, val retry: Boolean = false) : Exception(message)

class TuyaCodec(localKey: String, private val uuid: String, private val deviceId: String) {
  /** Only the first six characters of the local key take part in BLE encryption. */
  private val loginSeed = localKey.take(6).toByteArray()
  private val loginKey = md5(loginSeed)
  private var sessionKey: ByteArray? = null
  private var seq = 1

  /** Major protocol version, as the lamp states it when logging in. */
  var protocolVersion = 3
    private set

  private var input: ByteArrayOutputStream? = null
  private var inputExpectedPacket = 0
  private var inputExpectedLength = 0

  /** Forgets the session; call for every new connection. */
  fun reset() {
    sessionKey = null
    seq = 1
    input = null
    inputExpectedPacket = 0
  }

  /** Session key comes from the random number in the device-info answer. Returns true if the lamp is bound. */
  fun onDeviceInfo(data: ByteArray): Boolean {
    if (data.size < 46) throw TuyaError("короткий ответ лампы")
    protocolVersion = data[2].toInt() and 0xFF
    sessionKey = md5(loginSeed + data.copyOfRange(6, 12))
    return data[5].toInt() != 0
  }

  fun pairingRequest(): ByteArray = (uuid.toByteArray() + loginSeed + deviceId.toByteArray()).copyOf(44)

  /** Answer to the lamp asking for the time: milliseconds as text, then the time zone in 1/100 h. */
  fun time1(now: Long, zone: TimeZone): ByteArray {
    val tz = zone.getOffset(now) / 36_000
    return now.toString().toByteArray() + byteArrayOf((tz shr 8).toByte(), tz.toByte())
  }

  /** Encrypts one message and cuts it into GATT writes. Returns its sequence number and the chunks. */
  fun build(code: Int, data: ByteArray, responseTo: Int = 0, iv: ByteArray = randomIv()): Pair<Int, List<ByteArray>> {
    val key: ByteArray
    val flag: Byte
    if (code == TuyaCode.DEVICE_INFO) {
      key = loginKey
      flag = 4
    } else {
      key = sessionKey ?: throw TuyaError("нет сеанса с лампой")
      flag = 5
    }
    val number = seq++
    val body = ByteBuffer.allocate(12 + data.size).putInt(number).putInt(responseTo).putShort(code.toShort()).putShort(data.size.toShort()).put(data).array()
    val crc = crc16(body)
    var raw = body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    if (raw.size % 16 != 0) raw = raw.copyOf(raw.size + 16 - raw.size % 16)
    val encrypted = byteArrayOf(flag) + iv + aes(Cipher.ENCRYPT_MODE, key, iv, raw)

    val chunks = ArrayList<ByteArray>()
    var pos = 0
    var packet = 0
    while (pos < encrypted.size) {
      val head = ByteArrayOutputStream()
      head.write(packInt(packet))
      if (packet == 0) {
        head.write(packInt(encrypted.size))
        head.write(protocolVersion shl 4)
      }
      val part = minOf(GATT_MTU - head.size(), encrypted.size - pos)
      head.write(encrypted, pos, part)
      chunks += head.toByteArray()
      pos += part
      packet++
    }
    return number to chunks
  }

  /** Takes one notification; returns a message once all its chunks have arrived. */
  fun feed(chunk: ByteArray): TuyaMessage? {
    var (packet, pos) = unpackInt(chunk, 0)
    if (packet < inputExpectedPacket) {
      input = null
      inputExpectedPacket = 0
    }
    if (packet != inputExpectedPacket) {
      input = null
      inputExpectedPacket = 0
      return null
    }
    if (packet == 0) {
      input = ByteArrayOutputStream()
      val (length, next) = unpackInt(chunk, pos)
      inputExpectedLength = length
      pos = next + 1 // protocol version
    }
    val buffer = input ?: return null
    buffer.write(chunk, pos, chunk.size - pos)
    inputExpectedPacket++
    if (buffer.size() < inputExpectedLength) return null
    input = null
    inputExpectedPacket = 0
    if (buffer.size() > inputExpectedLength) return null
    return parse(buffer.toByteArray())
  }

  private fun parse(buffer: ByteArray): TuyaMessage {
    val key = when (buffer[0].toInt()) {
      4 -> loginKey
      5 -> sessionKey ?: throw TuyaError("нет сеанса с лампой")
      else -> throw TuyaError("лампа ответила неизвестным шифром ${buffer[0]}")
    }
    if (buffer.size < 17 + 16 || (buffer.size - 17) % 16 != 0) throw TuyaError("обрезанный ответ лампы")
    val raw = aes(Cipher.DECRYPT_MODE, key, buffer.copyOfRange(1, 17), buffer.copyOfRange(17, buffer.size))
    val head = ByteBuffer.wrap(raw)
    val number = head.int
    val responseTo = head.int
    val code = head.short.toInt() and 0xFFFF
    val length = head.short.toInt() and 0xFFFF
    val end = 12 + length
    // A wrong key shows up here: the decrypted header and checksum turn into garbage.
    if (end + 2 > raw.size) throw TuyaError("лампа не приняла ключ")
    val crc = ((raw[end].toInt() and 0xFF) shl 8) or (raw[end + 1].toInt() and 0xFF)
    if (crc != crc16(raw.copyOf(end))) throw TuyaError("лампа не приняла ключ")
    return TuyaMessage(number, responseTo, code, raw.copyOfRange(12, end))
  }

  companion object {
    const val GATT_MTU = 20

    private val random = SecureRandom()

    private fun randomIv() = ByteArray(16).also { random.nextBytes(it) }

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/CBC/NoPadding")
      cipher.init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
      return cipher.doFinal(data)
    }

    fun crc16(data: ByteArray): Int {
      var crc = 0xFFFF
      for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) {
          val carry = crc and 1
          crc = crc ushr 1
          if (carry != 0) crc = crc xor 0xA001
        }
      }
      return crc
    }

    fun packInt(value: Int): ByteArray {
      val out = ByteArrayOutputStream()
      var v = value
      while (true) {
        var b = v and 0x7F
        v = v ushr 7
        if (v != 0) b = b or 0x80
        out.write(b)
        if (v == 0) break
      }
      return out.toByteArray()
    }

    /** Returns the value and the position after it. */
    fun unpackInt(data: ByteArray, start: Int): Pair<Int, Int> {
      var result = 0
      var offset = 0
      while (offset < 5) {
        val pos = start + offset
        if (pos >= data.size) throw TuyaError("обрезанный ответ лампы")
        val b = data[pos].toInt() and 0xFF
        result = result or ((b and 0x7F) shl (offset * 7))
        offset++
        if (b and 0x80 == 0) break
      }
      return result to start + offset
    }

    /**
     * The identity a Tuya lamp broadcasts in its manufacturer data (company 0x07D0).
     * Unbound lamps encrypt their UUID with the product id; bound ones encrypt the device id
     * with the login key. Returns the decrypted text for the given key material, or null.
     */
    fun advertisedId(manufacturerData: ByteArray, keyMaterial: ByteArray): String? {
      if (manufacturerData.size < 6 + 16) return null
      val key = md5(keyMaterial)
      val plain = aes(Cipher.DECRYPT_MODE, key, key, manufacturerData.copyOfRange(6, 22))
      return if (plain.all { it in 0x20..0x7E }) String(plain) else null
    }
  }
}
