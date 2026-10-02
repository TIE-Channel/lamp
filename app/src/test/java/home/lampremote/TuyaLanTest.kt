package home.lampremote

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Checks the Wi-Fi protocol against messages built by the reference implementation (tools/gen_lan_vectors.py). */
class TuyaLanTest {
  private val vectors = JSONObject(javaClass.getResource("/tuya_lan_vectors.json")!!.readText())
  private val localKey = vectors.getString("localKey").toByteArray()
  private val localNonce = Hex.parse(vectors.getString("localNonce"))
  private val remoteNonce = Hex.parse(vectors.getString("remoteNonce"))
  private val sessionKey = Hex.parse(vectors.getString("sessionKey"))

  private fun bytes(name: String) = Hex.parse(vectors.getString(name))

  private fun read(name: String, key: ByteArray) = TuyaLan.read(ByteArrayInputStream(bytes(name)), key)

  @Test
  fun sessionNegotiationMatchesReference() {
    assertEquals(Hex.of(bytes("start")), Hex.of(TuyaLan.pack(1, TuyaLan.SESS_KEY_NEG_START, localNonce, localKey)))

    val answer = read("startAnswer", localKey)
    assertEquals(TuyaLan.SESS_KEY_NEG_RESP, answer.cmd)
    assertEquals(0, answer.retcode)
    val remote = TuyaLan.remoteNonce(localKey, localNonce, answer.payload)
    assertArrayEquals(remoteNonce, remote)

    val finish = TuyaLan.pack(2, TuyaLan.SESS_KEY_NEG_FINISH, TuyaLan.finishPayload(localKey, remote!!), localKey)
    assertEquals(Hex.of(bytes("finish")), Hex.of(finish))
    assertArrayEquals(sessionKey, TuyaLan.sessionKey(localKey, localNonce, remote))
  }

  @Test
  fun lampMustProveItKnowsTheKey() {
    val answer = read("startAnswer", localKey)
    // An answer made for another random number of ours.
    assertNull(TuyaLan.remoteNonce(localKey, remoteNonce, answer.payload))
  }

  @Test
  fun queryMatchesReferenceAndAnswerIsRead() {
    assertEquals(Hex.of(bytes("query")), Hex.of(TuyaLan.pack(3, TuyaLan.DP_QUERY_NEW, TuyaLan.queryPayload(), sessionKey)))

    val answer = read("queryAnswer", sessionKey)
    assertEquals(TuyaLan.DP_QUERY_NEW, answer.cmd)
    val dps = TuyaLan.parseDps(answer.payload).associateBy { it.id }
    assertEquals(true, dps.getValue(20).bool)
    assertEquals(1000, dps.getValue(22).int)
    assertEquals(0, dps.getValue(23).int)
    // Text values (the work mode) are not data points the app uses.
    assertTrue(21 !in dps)
  }

  @Test
  fun controlMatchesReference() {
    val payload = TuyaLan.controlPayload(listOf(TuyaDp.bool(20, false), TuyaDp.value(22, 10)), vectors.getLong("time"))
    assertEquals(Hex.of(bytes("control")), Hex.of(TuyaLan.pack(4, TuyaLan.CONTROL_NEW, payload, sessionKey)))

    val answer = read("controlAnswer", sessionKey)
    assertEquals(TuyaLan.CONTROL_NEW, answer.cmd)
    assertEquals(0, answer.retcode)
    assertEquals(0, answer.payload.size)
  }

  @Test(expected = TuyaError::class)
  fun messageSignedWithAnotherKeyIsRejected() {
    read("queryAnswer", localKey)
  }

  @Test
  fun reportsInsideDataAreRead() {
    val dps = TuyaLan.parseDps("""{"protocol":4,"t":1,"data":{"dps":{"20":false}}}""".toByteArray())
    assertEquals(false, dps.single().bool)
  }
}
