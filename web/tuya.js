// Tuya BLE protocol v3 for the browser: the same framing, encryption and data points as
// app/src/main/java/home/lampremote/Tuya.kt. No dependencies, so it also runs in Node for tests.

export const Code = {
  DEVICE_INFO: 0x0000,
  PAIR: 0x0001,
  DPS: 0x0002,
  DEVICE_STATUS: 0x0003,
  RECEIVE_DP: 0x8001,
  RECEIVE_TIME_DP: 0x8003,
  RECEIVE_SIGN_DP: 0x8004,
  RECEIVE_SIGN_TIME_DP: 0x8005,
  TIME1_REQ: 0x8011,
  TIME2_REQ: 0x8012,
};

export const DpType = { RAW: 0, BOOL: 1, VALUE: 2, STRING: 3, ENUM: 4, BITMAP: 5 };

export class TuyaError extends Error {}

const text = new TextEncoder();

export function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let pos = 0;
  for (const p of parts) {
    out.set(p, pos);
    pos += p.length;
  }
  return out;
}

export const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
export const unhex = (s) => Uint8Array.from(s.match(/../g) ?? [], (h) => parseInt(h, 16));

// --- MD5 ------------------------------------------------------------------------------

const MD5_S = [7, 12, 17, 22, 5, 9, 14, 20, 4, 11, 16, 23, 6, 10, 15, 21];
const MD5_K = Array.from({ length: 64 }, (_, i) => Math.floor(Math.abs(Math.sin(i + 1)) * 2 ** 32) >>> 0);

export function md5(data) {
  const length = data.length;
  const padded = new Uint8Array((((length + 8) >> 6) + 1) << 6);
  padded.set(data);
  padded[length] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 8, (length * 8) >>> 0, true);
  view.setUint32(padded.length - 4, Math.floor(length / 2 ** 29), true);

  let a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
  for (let block = 0; block < padded.length; block += 64) {
    let a = a0, b = b0, c = c0, d = d0;
    for (let i = 0; i < 64; i++) {
      let f, g;
      if (i < 16) { f = (b & c) | (~b & d); g = i; }
      else if (i < 32) { f = (d & b) | (~d & c); g = (5 * i + 1) % 16; }
      else if (i < 48) { f = b ^ c ^ d; g = (3 * i + 5) % 16; }
      else { f = c ^ (b | ~d); g = (7 * i) % 16; }
      const sum = (a + f + MD5_K[i] + view.getUint32(block + g * 4, true)) >>> 0;
      const shift = MD5_S[(i >> 4) * 4 + (i % 4)];
      a = d; d = c; c = b;
      b = (b + ((sum << shift) | (sum >>> (32 - shift)))) >>> 0;
    }
    a0 = (a0 + a) >>> 0; b0 = (b0 + b) >>> 0; c0 = (c0 + c) >>> 0; d0 = (d0 + d) >>> 0;
  }
  const out = new Uint8Array(16);
  const o = new DataView(out.buffer);
  o.setUint32(0, a0, true); o.setUint32(4, b0, true); o.setUint32(8, c0, true); o.setUint32(12, d0, true);
  return out;
}

// --- AES-128-CBC without padding ------------------------------------------------------
// WebCrypto always pads, and the protocol pads with zeros by itself, hence the small own AES.

const SBOX = new Uint8Array(256);
const INV_SBOX = new Uint8Array(256);
(() => {
  const rotl = (x, n) => ((x << n) | (x >> (8 - n))) & 0xff;
  let p = 1, q = 1;
  do {
    p = (p ^ (p << 1) ^ (p & 0x80 ? 0x1b : 0)) & 0xff;
    q ^= q << 1; q ^= q << 2; q ^= q << 4; q &= 0xff;
    if (q & 0x80) q ^= 0x09;
    SBOX[p] = q ^ rotl(q, 1) ^ rotl(q, 2) ^ rotl(q, 3) ^ rotl(q, 4) ^ 0x63;
  } while (p !== 1);
  SBOX[0] = 0x63;
  for (let i = 0; i < 256; i++) INV_SBOX[SBOX[i]] = i;
})();

const xtime = (x) => ((x << 1) ^ (x & 0x80 ? 0x1b : 0)) & 0xff;
function mul(x, y) {
  let r = 0;
  for (; y; y >>= 1, x = xtime(x)) if (y & 1) r ^= x;
  return r;
}

function expandKey(key) {
  const w = new Uint8Array(176);
  w.set(key);
  for (let i = 16, rcon = 1; i < 176; i += 4) {
    let t = w.subarray(i - 4, i);
    if (i % 16 === 0) {
      t = Uint8Array.of(SBOX[t[1]] ^ rcon, SBOX[t[2]], SBOX[t[3]], SBOX[t[0]]);
      rcon = xtime(rcon);
    }
    for (let j = 0; j < 4; j++) w[i + j] = w[i - 16 + j] ^ t[j];
  }
  return w;
}

function addRoundKey(s, w, round) {
  for (let i = 0; i < 16; i++) s[i] ^= w[round * 16 + i];
}

function encryptBlock(s, w) {
  addRoundKey(s, w, 0);
  for (let round = 1; round <= 10; round++) {
    const t = Uint8Array.from(s, (b) => SBOX[b]);
    for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) s[r + 4 * c] = t[r + 4 * ((c + r) % 4)];
    if (round < 10) {
      for (let c = 0; c < 16; c += 4) {
        const [a0, a1, a2, a3] = s.subarray(c, c + 4);
        s[c] = xtime(a0) ^ xtime(a1) ^ a1 ^ a2 ^ a3;
        s[c + 1] = a0 ^ xtime(a1) ^ xtime(a2) ^ a2 ^ a3;
        s[c + 2] = a0 ^ a1 ^ xtime(a2) ^ xtime(a3) ^ a3;
        s[c + 3] = xtime(a0) ^ a0 ^ a1 ^ a2 ^ xtime(a3);
      }
    }
    addRoundKey(s, w, round);
  }
}

function decryptBlock(s, w) {
  addRoundKey(s, w, 10);
  for (let round = 9; round >= 0; round--) {
    const t = s.slice();
    for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++) s[r + 4 * c] = INV_SBOX[t[r + 4 * ((c - r + 4) % 4)]];
    addRoundKey(s, w, round);
    if (round > 0) {
      for (let c = 0; c < 16; c += 4) {
        const [a0, a1, a2, a3] = s.subarray(c, c + 4);
        s[c] = mul(a0, 14) ^ mul(a1, 11) ^ mul(a2, 13) ^ mul(a3, 9);
        s[c + 1] = mul(a0, 9) ^ mul(a1, 14) ^ mul(a2, 11) ^ mul(a3, 13);
        s[c + 2] = mul(a0, 13) ^ mul(a1, 9) ^ mul(a2, 14) ^ mul(a3, 11);
        s[c + 3] = mul(a0, 11) ^ mul(a1, 13) ^ mul(a2, 9) ^ mul(a3, 14);
      }
    }
  }
}

export function aesCbc(encrypt, key, iv, data) {
  const w = expandKey(key);
  const out = new Uint8Array(data.length);
  let prev = iv;
  for (let pos = 0; pos < data.length; pos += 16) {
    const block = data.slice(pos, pos + 16);
    if (encrypt) {
      for (let i = 0; i < 16; i++) block[i] ^= prev[i];
      encryptBlock(block, w);
      prev = block;
    } else {
      decryptBlock(block, w);
      for (let i = 0; i < 16; i++) block[i] ^= prev[i];
      prev = data.subarray(pos, pos + 16);
    }
    out.set(block, pos);
  }
  return out;
}

// --- framing --------------------------------------------------------------------------

export const GATT_MTU = 20;

export function crc16(data) {
  let crc = 0xffff;
  for (const b of data) {
    crc ^= b;
    for (let i = 0; i < 8; i++) crc = crc & 1 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
  }
  return crc;
}

function packInt(value) {
  const out = [];
  do {
    let b = value & 0x7f;
    value >>>= 7;
    if (value) b |= 0x80;
    out.push(b);
  } while (value);
  return out;
}

function unpackInt(data, start) {
  let result = 0;
  for (let offset = 0; offset < 5; offset++) {
    if (start + offset >= data.length) throw new TuyaError('обрезанный ответ лампы');
    const b = data[start + offset];
    result |= (b & 0x7f) << (offset * 7);
    if (!(b & 0x80)) return [result, start + offset + 1];
  }
  throw new TuyaError('обрезанный ответ лампы');
}

/** A data point: one numbered property of the lamp (switch, brightness, ...). */
export const Dp = {
  bool: (id, on) => ({ id, type: DpType.BOOL, value: Uint8Array.of(on ? 1 : 0) }),
  value: (id, v) => ({ id, type: DpType.VALUE, value: Uint8Array.of(v >>> 24, (v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff) }),
  int: (dp) => dp.value.reduce((acc, b) => (acc << 8) | b, 0),
  encode: (dps) => concat(...dps.map((dp) => Uint8Array.of(dp.id, dp.type, dp.value.length, ...dp.value))),
  decode(data, start) {
    const out = [];
    let pos = start;
    while (data.length - pos >= 3) {
      const end = pos + 3 + data[pos + 2];
      if (data[pos + 1] > DpType.BITMAP || end > data.length) throw new TuyaError('непонятный ответ лампы');
      out.push({ id: data[pos], type: data[pos + 1], value: data.slice(pos + 3, end) });
      pos = end;
    }
    return out;
  },
};

export class TuyaCodec {
  constructor(localKey, uuid, deviceId) {
    // Only the first six characters of the local key take part in BLE encryption.
    this.loginSeed = text.encode(localKey.slice(0, 6));
    this.loginKey = md5(this.loginSeed);
    this.uuid = uuid;
    this.deviceId = deviceId;
    this.reset();
  }

  /** Forgets the session; call for every new connection. */
  reset() {
    this.sessionKey = null;
    this.protocolVersion = 3;
    this.seq = 1;
    this.input = null;
    this.inputExpectedPacket = 0;
  }

  /** Session key comes from the random number in the device-info answer. Returns true if the lamp is bound. */
  onDeviceInfo(data) {
    if (data.length < 46) throw new TuyaError('короткий ответ лампы');
    this.protocolVersion = data[2];
    this.sessionKey = md5(concat(this.loginSeed, data.subarray(6, 12)));
    return data[5] !== 0;
  }

  pairingRequest() {
    const out = new Uint8Array(44);
    out.set(concat(text.encode(this.uuid), this.loginSeed, text.encode(this.deviceId)));
    return out;
  }

  /** Answer to the lamp asking for the time: milliseconds as text, then the time zone in 1/100 h. */
  time1(now, offsetMinutes) {
    const tz = Math.trunc((offsetMinutes * 100) / 60);
    return concat(text.encode(String(now)), Uint8Array.of((tz >> 8) & 0xff, tz & 0xff));
  }

  /** Encrypts one message and cuts it into GATT writes. Returns its sequence number and the chunks. */
  build(code, data, responseTo = 0, iv = crypto.getRandomValues(new Uint8Array(16))) {
    const login = code === Code.DEVICE_INFO;
    const key = login ? this.loginKey : this.sessionKey;
    if (!key) throw new TuyaError('нет сеанса с лампой');
    const seq = this.seq++;
    const body = new Uint8Array(12 + data.length);
    const view = new DataView(body.buffer);
    view.setUint32(0, seq);
    view.setUint32(4, responseTo);
    view.setUint16(8, code);
    view.setUint16(10, data.length);
    body.set(data, 12);
    const crc = crc16(body);
    const raw = new Uint8Array(Math.ceil((body.length + 2) / 16) * 16);
    raw.set(body);
    raw[body.length] = crc >> 8;
    raw[body.length + 1] = crc & 0xff;
    const encrypted = concat(Uint8Array.of(login ? 4 : 5), iv, aesCbc(true, key, iv, raw));

    const chunks = [];
    for (let pos = 0, packet = 0; pos < encrypted.length; packet++) {
      const head = packInt(packet);
      if (packet === 0) head.push(...packInt(encrypted.length), this.protocolVersion << 4);
      const part = encrypted.subarray(pos, pos + GATT_MTU - head.length);
      chunks.push(concat(Uint8Array.from(head), part));
      pos += part.length;
    }
    return { seq, chunks };
  }

  /** Takes one notification; returns a message once all its chunks have arrived. */
  feed(chunk) {
    let [packet, pos] = unpackInt(chunk, 0);
    if (packet !== this.inputExpectedPacket) {
      this.input = null;
      this.inputExpectedPacket = 0;
      if (packet !== 0) return null;
    }
    if (packet === 0) {
      this.input = [];
      [this.inputExpectedLength, pos] = unpackInt(chunk, pos);
      pos += 1; // protocol version
    }
    if (!this.input) return null;
    this.input.push(...chunk.subarray(pos));
    this.inputExpectedPacket++;
    if (this.input.length < this.inputExpectedLength) return null;
    const buffer = Uint8Array.from(this.input);
    this.input = null;
    this.inputExpectedPacket = 0;
    return buffer.length === this.inputExpectedLength ? this.parse(buffer) : null;
  }

  parse(buffer) {
    const key = buffer[0] === 4 ? this.loginKey : buffer[0] === 5 ? this.sessionKey : null;
    if (!key) throw new TuyaError(`лампа ответила неизвестным шифром ${buffer[0]}`);
    if (buffer.length < 33 || (buffer.length - 17) % 16) throw new TuyaError('обрезанный ответ лампы');
    const raw = aesCbc(false, key, buffer.subarray(1, 17), buffer.subarray(17));
    const view = new DataView(raw.buffer);
    const end = 12 + view.getUint16(10);
    // A wrong key shows up here: the decrypted header and checksum turn into garbage.
    if (end + 2 > raw.length || view.getUint16(end) !== crc16(raw.subarray(0, end))) throw new TuyaError('лампа не приняла ключ');
    return { seq: view.getUint32(0), responseTo: view.getUint32(4), code: view.getUint16(8), data: raw.slice(12, end) };
  }
}
