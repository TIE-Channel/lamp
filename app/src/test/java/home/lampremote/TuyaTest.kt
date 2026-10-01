package home.lampremote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Checks the Kotlin port against packets built by the reference implementation (tools/gen_tuya_vectors.py). */
class TuyaTest {
  private val vectors = JSONObject(javaClass.getResource("/tuya_vectors.json")!!.readText())

  private fun codec(): TuyaCodec {
    val codec = TuyaCodec(vectors.getString("localKey"), vectors.getString("uuid"), vectors.getString("deviceId"))
    // Device info answer: protocol 3 at [2], bound flag at [5], session random at [6..12).
    val info = ByteArray(46)
    info[2] = 3
    info[5] = 1
    Hex.parse(vectors.getString("srand")).copyInto(info, 6)
    assertTrue(codec.onDeviceInfo(info))
    return codec
  }

  @Test
  fun crcMatchesReference() {
    val crc = vectors.getJSONObject("crc")
    assertEquals(crc.getInt("value"), TuyaCodec.crc16(Hex.parse(crc.getString("data"))))
  }

  @Test
  fun pairingRequestMatchesReference() {
    assertEquals(vectors.getString("pairing"), Hex.of(codec().pairingRequest()).lowercase())
  }

  @Test
  fun builtPacketsMatchReference() {
    val codec = codec()
    val cases = vectors.getJSONArray("cases")
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val (seq, chunks) = codec.build(case.getInt("code"), Hex.parse(case.getString("data")), case.getInt("responseTo"), Hex.parse(case.getString("iv")))
      assertEquals(case.getInt("seq"), seq)
      val expected = case.getJSONArray("chunks")
      assertEquals("case $i chunk count", expected.length(), chunks.size)
      for (j in chunks.indices) assertEquals("case $i chunk $j", expected.getString(j), Hex.of(chunks[j]).lowercase())
    }
  }

  @Test
  fun referencePacketsAreParsed() {
    val codec = codec()
    val cases = vectors.getJSONArray("cases")
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val chunks = case.getJSONArray("chunks")
      var message: TuyaMessage? = null
      for (j in 0 until chunks.length()) {
        assertNull("case $i complete too early", message)
        message = codec.feed(Hex.parse(chunks.getString(j)))
      }
      assertNotNull("case $i not completed", message)
      assertEquals(case.getInt("seq"), message!!.seq)
      assertEquals(case.getInt("responseTo"), message.responseTo)
      assertEquals(case.getInt("code"), message.code)
      assertArrayEquals(Hex.parse(case.getString("data")), message.data)
    }
  }

  @Test(expected = TuyaError::class)
  fun wrongKeyIsReported() {
    val other = TuyaCodec("zzzzzz9999999999", "u", "d")
    val chunks = vectors.getJSONArray("cases").getJSONObject(0).getJSONArray("chunks")
    for (j in 0 until chunks.length()) other.feed(Hex.parse(chunks.getString(j)))
  }

  @Test
  fun dataPointsRoundTrip() {
    val dps = listOf(TuyaDp.bool(20, true), TuyaDp.value(22, 1000), TuyaDp.enum(21, 1))
    val encoded = TuyaDp.encode(dps)
    assertEquals("14010101" + "160204000003e8" + "15040101", Hex.of(encoded).lowercase())
    val decoded = TuyaDp.decode(encoded, 0)
    assertEquals(listOf(20, 22, 21), decoded.map { it.id })
    assertTrue(decoded[0].bool)
    assertEquals(1000, decoded[1].int)
    assertEquals(1, decoded[2].int)
  }

  @Test
  fun protocol4DataPointsUseTwoByteLengths() {
    val dps = listOf(TuyaDp.bool(20, true), TuyaDp.value(22, 10))
    val encoded = TuyaDp.encode(dps, 2)
    assertEquals("1401000101" + "160200040000000a", Hex.of(encoded).lowercase())
    val decoded = TuyaDp.decode(encoded, 0, 2)
    assertTrue(decoded[0].bool)
    assertEquals(10, decoded[1].int)
  }

  @Test
  fun timeAnswerCarriesZoneInHundredthsOfHour() {
    val answer = TuyaCodec("k", "u", "d").time1(1_790_000_000_000, TimeZone.getTimeZone("GMT+02:00"))
    assertEquals("1790000000000", String(answer, 0, 13))
    assertEquals(200, ((answer[13].toInt() and 0xFF) shl 8) or (answer[14].toInt() and 0xFF))
  }

  @Test
  fun unboundAdvertisementRevealsUuid() {
    // Captured from the lamp in pairing mode; the key material is its product id.
    val manufacturerData = Hex.parse("010300000C000B1A77650E51996005F39437C2584223")
    val uuid = TuyaCodec.advertisedId(manufacturerData, "d3nbkezo71wfnlqh".toByteArray())
    assertNotNull(uuid)
    assertEquals(16, uuid!!.length)
    assertNull(TuyaCodec.advertisedId(manufacturerData, "wrong-product-id".toByteArray()))
  }
}
