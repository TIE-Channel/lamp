// Checks the browser port against packets built by the reference implementation
// (tools/gen_tuya_vectors.py), the same vectors the Kotlin tests use:  node --test web/tuya.test.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import { Code, Dp, TuyaCodec, TuyaError, aesCbc, crc16, hex, md5, unhex } from './tuya.js';

const vectors = JSON.parse(readFileSync(new URL('../app/src/test/resources/tuya_vectors.json', import.meta.url), 'utf8'));

function codec() {
  const c = new TuyaCodec(vectors.localKey, vectors.uuid, vectors.deviceId);
  const info = new Uint8Array(46);
  info[2] = 3;
  info[5] = 1;
  info.set(unhex(vectors.srand), 6);
  assert.equal(c.onDeviceInfo(info), true);
  return c;
}

test('md5 matches known digests', () => {
  assert.equal(hex(md5(new Uint8Array(0))), 'd41d8cd98f00b204e9800998ecf8427e');
  assert.equal(hex(md5(new TextEncoder().encode('The quick brown fox jumps over the lazy dog'))), '9e107d9d372bb6826bd81d3542a419d6');
  // 56 and 64 bytes: the padding boundaries.
  assert.equal(hex(md5(new TextEncoder().encode('12345678901234567890123456789012345678901234567890123456'))), '49f193adce178490e34d1b3a4ec0064c');
  assert.equal(hex(md5(new TextEncoder().encode('1234567890123456789012345678901234567890123456789012345678901234'))), 'eb6c4179c0a7c82cc2828c1e6338e165');
});

test('aes matches the FIPS-197 example and inverts itself', () => {
  const key = unhex('000102030405060708090a0b0c0d0e0f');
  const zero = new Uint8Array(16);
  const plain = unhex('00112233445566778899aabbccddeeff');
  assert.equal(hex(aesCbc(true, key, zero, plain)), '69c4e0d86a7b0430d8cdb78070b4c55a');
  const two = unhex('00112233445566778899aabbccddeeff' + 'ffeeddccbbaa99887766554433221100');
  assert.deepEqual(aesCbc(false, key, plain, aesCbc(true, key, plain, two)), two);
});

test('crc matches reference', () => {
  assert.equal(crc16(unhex(vectors.crc.data)), vectors.crc.value);
});

test('pairing request matches reference', () => {
  assert.equal(hex(codec().pairingRequest()), vectors.pairing);
});

test('built packets match reference', () => {
  const c = codec();
  for (const v of vectors.cases) {
    const { seq, chunks } = c.build(v.code, unhex(v.data), v.responseTo, unhex(v.iv));
    assert.equal(seq, v.seq);
    assert.deepEqual(chunks.map(hex), v.chunks);
  }
});

test('reference packets are parsed', () => {
  const c = codec();
  for (const v of vectors.cases) {
    let message = null;
    for (const chunk of v.chunks) {
      assert.equal(message, null);
      message = c.feed(unhex(chunk));
    }
    assert.deepEqual({ ...message, data: hex(message.data) }, { seq: v.seq, responseTo: v.responseTo, code: v.code, data: v.data });
  }
});

test('wrong key is reported', () => {
  const other = new TuyaCodec('zzzzzz9999999999', 'u', 'd');
  assert.throws(() => vectors.cases[0].chunks.forEach((chunk) => other.feed(unhex(chunk))), TuyaError);
});

test('data points round trip', () => {
  const encoded = Dp.encode([Dp.bool(20, true), Dp.value(22, 1000)]);
  assert.equal(hex(encoded), '14010101' + '160204000003e8');
  assert.deepEqual(Dp.decode(encoded, 0).map((dp) => [dp.id, Dp.int(dp)]), [[20, 1], [22, 1000]]);
  // A report captured from the lamp.
  const report = Dp.decode(unhex('170204000001f41602040000000a14010100'), 0);
  assert.deepEqual(report.map((dp) => [dp.id, Dp.int(dp)]), [[23, 500], [22, 10], [20, 0]]);
});

test('time answer carries the zone in hundredths of an hour', () => {
  const answer = new TuyaCodec('k', 'u', 'd').time1(1790000000000, 120);
  assert.equal(new TextDecoder().decode(answer.subarray(0, 13)), '1790000000000');
  assert.equal((answer[13] << 8) | answer[14], 200);
});

test('only device info can be sent before login', () => {
  const fresh = new TuyaCodec(vectors.localKey, vectors.uuid, vectors.deviceId);
  assert.equal(fresh.build(Code.DEVICE_INFO, new Uint8Array(0)).seq, 1);
  assert.throws(() => fresh.build(Code.DPS, new Uint8Array(0)), TuyaError);
});
