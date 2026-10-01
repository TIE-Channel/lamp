// The lamp remote as a web page, for browsers with Web Bluetooth (Bluefy on iPhone, Chrome on
// Android and desktop). Same logic as the Android app: read the lamp's state, then send data points.
import { Code, Dp, TuyaCodec, TuyaError } from './tuya.js';

const VERSION = 9;

const uuid16 = (n) => `0000${n.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb`;
const NOTIFY = uuid16(0x2b10);
const WRITE = uuid16(0x2b11);
// The lamp advertises 0xA201; its data service is one of these, depending on the firmware.
const ADVERTISED = uuid16(0xa201);
const SERVICES = [uuid16(0x1910), uuid16(0xfd50), ADVERTISED];

const DEFAULT_DPS = { switch: 20, bright: 22, temp: 23, countdown: 26, brightMin: 10, brightMax: 1000, tempMax: 1000 };
const CONNECT_MS = 5000;
const STEP_MS = 5000;
const RESPONSE_MS = 5000;
const REPORT_MS = 800;
// Bluefy keeps a page's connection alive while the app sits in the background, and does not
// reliably tell the page that it went there; the lamp then stays taken and every other phone
// loses it. So in Bluefy the page connects only for a tap and lets go the moment it is done.
const BLUEFY = /Bluefy/i.test(navigator.userAgent);
// How long the link is kept after a command, for a second tap.
const IDLE_MS = BLUEFY ? 0 : 3000;
// A "no" quicker than this comes from the browser itself: neither the lamp nor a person closing
// the device list answers that fast.
const REFUSAL_MS = 1000;
// How long to listen for the lamp announcing itself.
const HEAR_MS = 3000;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A Bluetooth call that never answers must not freeze the page: browsers set no limit themselves. */
function within(ms, promise, what) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Stuck(what)), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (e) => {
        clearTimeout(timer);
        reject(e);
      },
    );
  });
}

class Stuck extends Error {}

// --- diagnostics: what the page is doing, shown under "Диагностика" -------------------------

const started = performance.now();
const lines = [];
let step = '';

/** Notes a step of the current command; an error is reported together with the step it hit. */
function trace(text) {
  step = text;
  note(text);
}

/** A line for the diagnostics that is not a step of the command. */
function note(text) {
  lines.push(`${((performance.now() - started) / 1000).toFixed(1)} с  ${text}`);
  if (lines.length > 40) lines.shift();
  const log = document.getElementById('log');
  if (log) log.textContent = lines.join('\n');
}

/** Browsers disagree on what a Bluetooth failure looks like: an Error, a DOMException or plain text. */
function describe(e) {
  if (e instanceof Error) return e.message || e.name;
  if (typeof e === 'string') return e;
  try {
    return JSON.stringify(e) ?? String(e);
  } catch {
    return String(e);
  }
}

/** What kind of value a failure was: its text alone does not tell a number from a message. */
function kind(e) {
  if (e instanceof Error) return e.name;
  return e === null ? 'null' : typeof e;
}

// --- configuration: the lamp's key, kept in this browser only ----------------------------

function loadConfig() {
  // First visit: the key arrives after "#c=" (never sent to the server), then moves to storage.
  const match = location.hash.match(/c=([A-Za-z0-9_-]+)/);
  if (match) {
    const json = atob(match[1].replace(/-/g, '+').replace(/_/g, '/'));
    localStorage.setItem('lamp-config', json);
    history.replaceState(null, '', location.pathname + location.search);
  }
  try {
    const config = JSON.parse(localStorage.getItem('lamp-config'));
    if (!config?.deviceId || !config.uuid || !config.localKey) return null;
    return { ...config, dps: { ...DEFAULT_DPS, ...config.dps } };
  } catch {
    return null;
  }
}

// --- Bluetooth link ----------------------------------------------------------------------

class Link {
  constructor(config) {
    this.codec = new TuyaCodec(config.localKey, config.uuid, config.deviceId);
    this.dps = new Map();
    this.pending = new Map();
    this.reports = 0;
    this.ready = false;
    this.closed = false;
  }

  async open(device) {
    this.device = device;
    this.codec.reset();
    device.addEventListener('gattserverdisconnected', () => this.dropped());
    // Whatever an earlier visit left behind (a link, or a connection still being made) keeps the
    // lamp busy, and a browser does not always show it in gatt.connected: drop it in any case.
    const stale = device.gatt.connected;
    try {
      device.gatt.disconnect();
    } catch {
      // Nothing to drop.
    }
    if (stale) {
      trace('закрываю старое соединение');
      await sleep(300);
    }
    trace('подключение к лампе');
    const connecting = device.gatt.connect();
    // A connection that arrives after the page gave up on it would hold the lamp for good, and
    // the lamp talks to one phone at a time.
    connecting.then(() => {
      if (this.closed) this.close();
    }, () => {});
    const server = await within(CONNECT_MS, connecting, 'лампа не подключается');
    let notify, write;
    // The service that worked last time goes first: every miss costs time.
    const last = localStorage.getItem('lamp-service');
    for (const uuid of [...SERVICES].sort((a, b) => (b === last) - (a === last))) {
      try {
        trace(`поиск службы ${uuid.slice(4, 8)}`);
        const service = await within(STEP_MS, server.getPrimaryService(uuid), 'служба не отвечает');
        notify = await within(STEP_MS, service.getCharacteristic(NOTIFY), 'служба не отвечает');
        write = await within(STEP_MS, service.getCharacteristic(WRITE), 'служба не отвечает');
        localStorage.setItem('lamp-service', uuid);
        break;
      } catch (e) {
        // Not this service: try the next one.
        trace(`службы ${uuid.slice(4, 8)} нет: ${describe(e)}`);
        notify = write = undefined;
      }
    }
    if (!notify || !write) throw new TuyaError('это не лампа Tuya: нет нужной службы');
    trace('включение ответов лампы');
    notify.addEventListener('characteristicvaluechanged', (e) => {
      const v = e.target.value;
      this.received(new Uint8Array(v.buffer, v.byteOffset, v.byteLength).slice());
    });
    await within(STEP_MS, notify.startNotifications(), 'лампа не включает ответы');
    this.write = write;

    trace('вход по ключу');
    let info;
    try {
      info = await this.request(Code.DEVICE_INFO, new Uint8Array(0));
    } catch (e) {
      throw new TuyaError(`лампа не ответила на ключ (${describe(e)})`);
    }
    if (!this.codec.onDeviceInfo(info.data)) throw new TuyaError('лампа не привязана: добавьте её в Smart Life');
    const paired = await this.request(Code.PAIR, this.codec.pairingRequest());
    if (paired.data[0] !== 0 && paired.data[0] !== 2) throw new TuyaError(`лампа отклонила ключ (код ${paired.data[0]})`);
    this.ready = true;
  }

  /** Asks for the given data points and waits for the lamp's report. */
  async refresh(ids) {
    const before = this.reports;
    await this.request(Code.DEVICE_STATUS, Uint8Array.from(ids));
    const deadline = Date.now() + REPORT_MS;
    while (this.reports === before && Date.now() < deadline) await sleep(10);
  }

  async set(dps) {
    const answer = await this.request(Code.DPS, Dp.encode(dps));
    if (answer.data.length && answer.data[0] !== 0) throw new TuyaError(`лампа отклонила команду (код ${answer.data[0]})`);
    for (const dp of dps) this.dps.set(dp.id, dp);
  }

  close() {
    this.ready = false;
    this.closed = true;
    try {
      this.device?.gatt.disconnect();
    } catch {
      // Already gone.
    }
  }

  dropped() {
    this.ready = false;
    for (const { reject } of this.pending.values()) reject(new TuyaError('лампа разорвала соединение'));
    this.pending.clear();
  }

  async send(code, data, responseTo = 0) {
    const { seq, chunks } = this.codec.build(code, data, responseTo);
    // Writes must not interleave: one message at a time.
    this.queue = (this.queue ?? Promise.resolve()).catch(() => {}).then(async () => {
      for (const chunk of chunks) {
        if (this.write.writeValueWithoutResponse) await this.write.writeValueWithoutResponse(chunk);
        else await this.write.writeValue(chunk);
      }
    });
    await this.queue;
    return seq;
  }

  request(code, data) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new TuyaError('лампа не ответила')), RESPONSE_MS);
      const done = (fn) => (value) => {
        clearTimeout(timer);
        fn(value);
      };
      // send() takes this number for the message before its first pause; register under it first.
      const seq = this.codec.seq;
      this.pending.set(seq, { resolve: done(resolve), reject: done(reject) });
      this.send(code, data).catch((e) => {
        this.pending.delete(seq);
        done(reject)(e);
      });
    });
  }

  received(chunk) {
    let message;
    try {
      message = this.codec.feed(chunk);
    } catch (e) {
      note(`непонятный ответ: ${describe(e)}`);
      this.dropped();
      return;
    }
    if (!message) return;
    try {
      this.handle(message);
    } catch (e) {
      note(`сообщение ${message.code.toString(16)} пропущено: ${describe(e)}`);
    }
    if (message.responseTo) {
      this.pending.get(message.responseTo)?.resolve(message);
      this.pending.delete(message.responseTo);
    }
  }

  handle(m) {
    const afterTimestamp = (start) => start + 1 + (m.data[start] === 0 ? 13 : 4);
    const signed = () => Uint8Array.of(m.data[0], m.data[1], m.data[2], 0);
    const reply = (data) => this.send(m.code, data, m.seq).catch(() => {});
    switch (m.code) {
      case Code.RECEIVE_DP:
        this.update(Dp.decode(m.data, 0));
        reply(new Uint8Array(0));
        break;
      case Code.RECEIVE_TIME_DP:
        this.update(Dp.decode(m.data, afterTimestamp(0)));
        reply(new Uint8Array(0));
        break;
      case Code.RECEIVE_SIGN_DP:
        this.update(Dp.decode(m.data, 3));
        reply(signed());
        break;
      case Code.RECEIVE_SIGN_TIME_DP:
        this.update(Dp.decode(m.data, afterTimestamp(3)));
        reply(signed());
        break;
      case Code.TIME1_REQ:
        reply(this.codec.time1(Date.now(), -new Date().getTimezoneOffset()));
        break;
      case Code.TIME2_REQ: {
        const now = new Date();
        const zone = Math.trunc((-now.getTimezoneOffset() * 100) / 60);
        reply(Uint8Array.of(now.getFullYear() % 100, now.getMonth() + 1, now.getDate(), now.getHours(), now.getMinutes(),
          now.getSeconds(), (now.getDay() + 6) % 7, (zone >> 8) & 0xff, zone & 0xff));
        break;
      }
    }
  }

  update(dps) {
    for (const dp of dps) this.dps.set(dp.id, dp);
    this.reports++;
  }
}

// --- the lamp ----------------------------------------------------------------------------

const config = loadConfig();
let link = null;
let device = null;
// The link being set up, so that leaving the page can stop it half-way.
let opening = null;
// The lamp this browser remembers did not answer: go to the device list instead of trying it again.
let rememberedFailed = false;
let idleTimer = null;
let state = JSON.parse(localStorage.getItem('lamp-state') ?? 'null') ?? { on: false, bright: 0, temp: 0 };

function mode(s, d) {
  if (!s.on) return 'off';
  const slack = (d.brightMax - d.brightMin) / 50;
  if (s.bright <= d.brightMin + slack) return 'dim';
  if (s.bright >= d.brightMax - slack) return 'bright';
  return 'on';
}

const level = (d, bright) => [Dp.bool(d.switch, true), Dp.value(d.bright, bright)];

/** What each button sends, given the lamp's state. An empty list: nothing to do. */
const ACTIONS = {
  // The two widget modes: a second tap on the active one switches the lamp off.
  dim: (s, d) => (mode(s, d) === 'dim' ? [Dp.bool(d.switch, false)] : level(d, d.brightMin)),
  bright: (s, d) => (mode(s, d) === 'bright' ? [Dp.bool(d.switch, false)] : level(d, d.brightMax)),
  // The remote's buttons.
  power: (s, d) => [Dp.bool(d.switch, !s.on)],
  night: (s, d) => level(d, d.brightMin),
  up: (s, d) => (s.on ? [Dp.value(d.bright, Math.min(d.brightMax, s.bright + (d.brightMax - d.brightMin) / 10))] : []),
  down: (s, d) => (s.on ? [Dp.value(d.bright, Math.max(d.brightMin, s.bright - (d.brightMax - d.brightMin) / 10))] : []),
  cold: (s, d) => [Dp.value(d.temp, d.tempMax)],
  warm: (s, d) => [Dp.value(d.temp, 0)],
  // Warm -> neutral -> cold -> warm, as the remote's button cycles.
  cct: (s, d) => [Dp.value(d.temp, s.temp < d.tempMax / 4 ? d.tempMax / 2 : s.temp < (d.tempMax * 3) / 4 ? d.tempMax : 0)],
  timer: (s, d) => (s.on ? [Dp.value(d.countdown, 60)] : []),
  // Only reads the state.
  refresh: () => [],
};

/** The browser shows its device chooser only in answer to a tap. */
class NeedsTap extends Error {}

/** A lamp this browser was already allowed to use: no chooser needed, where the browser supports it. */
async function rememberedDevice() {
  if (!navigator.bluetooth.getDevices) return null;
  try {
    const known = await navigator.bluetooth.getDevices();
    note(`известные устройства: ${known.map((d) => `${d.name ?? '?'}${d.gatt?.connected ? ' (на связи)' : ''}`).join(', ') || 'нет'}`
      + `, слушать эфир ${known[0]?.watchAdvertisements ? 'можно' : 'нельзя'}`);
    const id = localStorage.getItem('lamp-device');
    return known.find((d) => d.id === id) ?? known.find((d) => d.name === 'TY') ?? null;
  } catch (e) {
    note(`список известных устройств недоступен: ${describe(e)}`);
    return null;
  }
}

/**
 * Waits for the lamp to announce itself. A browser may refuse to connect to a remembered device
 * it has not heard since it was started, and only the browser's own list or this gets past that.
 */
async function heard(candidate) {
  if (!candidate.watchAdvertisements) return false;
  trace('жду сигнала лампы');
  const stop = new AbortController();
  try {
    const signal = new Promise((resolve) => {
      candidate.addEventListener('advertisementreceived', () => resolve(true), { once: true });
    });
    await within(STEP_MS, candidate.watchAdvertisements({ signal: stop.signal }), 'браузер не слушает эфир');
    const onAir = await Promise.race([signal, sleep(HEAR_MS).then(() => false)]);
    note(onAir ? 'лампа в эфире' : 'лампы в эфире не слышно');
    return onAir;
  } catch (e) {
    note(`слушать эфир не вышло: ${describe(e)} [${kind(e)}]`);
    return false;
  } finally {
    stop.abort();
  }
}

// From the exact request to the loosest. Browsers other than Chrome refuse requests they do not
// like without saying why (Bluefy answers with a bare number); a simpler one may pass.
const CHOOSER_REQUESTS = [
  { filters: [{ services: [ADVERTISED] }, { namePrefix: 'TY' }] },
  { filters: [{ namePrefix: 'TY' }] },
  { acceptAllDevices: true },
];

// A lamp that is connected to anything stops announcing itself, and then no browser lists it.
const ABSENT = 'Если её нет в списке, лампа без питания или занята: закройте совсем (смахните) Bluefy и Smart Life '
  + 'на всех телефонах и попробуйте снова';

async function chooseDevice(allowChooser) {
  if (!allowChooser) throw new NeedsTap();
  // The browser's list and a full-screen page get in each other's way.
  if (document.fullscreenElement) await document.exitFullscreen?.().catch(() => {});
  let refusal;
  for (const [index, request] of CHOOSER_REQUESTS.entries()) {
    trace(index ? `выбор лампы в списке, запрос попроще (${index + 1})` : 'выбор лампы в списке');
    const asked = performance.now();
    try {
      const chosen = await navigator.bluetooth.requestDevice({ ...request, optionalServices: SERVICES });
      localStorage.setItem('lamp-device', chosen.id);
      return chosen;
    } catch (e) {
      // Chrome: the tap has "expired" or there was none. One more tap is needed.
      if (e?.name === 'SecurityError') throw new NeedsTap();
      const took = performance.now() - asked;
      note(`список закрылся через ${Math.round(took)} мс: ${describe(e)} [${kind(e)}]`);
      if (e?.name === 'NotFoundError' || /cancel/i.test(describe(e)) || took > REFUSAL_MS) {
        throw new TuyaError(`лампа не выбрана. ${ABSENT}`);
      }
      refusal = e;
    }
  }
  throw new TuyaError(`браузер не показал список устройств (ответ: ${describe(refusal)}). Проверьте, что Bluetooth включён `
    + 'и разрешён этому браузеру, затем закройте браузер совсем и откройте страницу снова');
}

const AWAY = 'страница была свёрнута';

async function open(candidate) {
  // A page in the background must not take the lamp: nobody is looking, and nothing would let go.
  if (document.hidden) throw new TuyaError(AWAY);
  const fresh = new Link(config);
  opening = fresh;
  try {
    await fresh.open(candidate);
  } catch (e) {
    const left = fresh.closed;
    fresh.close();
    throw left ? new TuyaError(AWAY) : e;
  } finally {
    opening = null;
  }
  if (fresh.closed) throw new TuyaError(AWAY);
  device = candidate;
  rememberedFailed = false;
  link = fresh;
  return link;
}

async function connected(allowChooser) {
  if (link?.ready) return link;
  const candidate = device ?? (rememberedFailed ? null : await rememberedDevice());
  if (candidate) {
    // Twice after a tap: the first failure closes whatever was left of an old link, which often
    // is the cure. When the page merely looks up the state on opening, a second try is made only
    // for a lamp the browser refused at once and then heard on air.
    for (const attempt of [1, 2]) {
      const asked = performance.now();
      try {
        return await open(candidate);
      } catch (e) {
        // The key is wrong or the lamp refused: another device would not help.
        if (e instanceof TuyaError) throw e;
        note(`знакомая лампа не подключилась (попытка ${attempt}): ${describe(e)} [${kind(e)}]`);
        if (attempt === 2) break;
        const onAir = performance.now() - asked < REFUSAL_MS && (await heard(candidate));
        if (!allowChooser && !onAir) break;
        if (!onAir) await sleep(400);
      }
    }
    device = null;
    // Chrome may want one more tap before its list; that tap must not start from here again.
    rememberedFailed = allowChooser;
  }
  try {
    return await open(await chooseDevice(allowChooser));
  } catch (e) {
    if (e instanceof Stuck) throw new TuyaError('лампа не подключается: она без питания, далеко или занята другим телефоном');
    throw e;
  }
}

/** Lets go of the lamp at once: it talks to one phone at a time. */
function release() {
  clearTimeout(idleTimer);
  opening?.close();
  if (link) note('соединение закрыто');
  link?.close();
  link = null;
}

// What the browser says about the phone's Bluetooth, where it says anything.
let available = null;
let busy = Promise.resolve();
// Taps not finished yet, the running one included.
let waiting = 0;

/** Runs one button. [mayChoose] tells whether to try the browser's device chooser. */
function run(name, mayChoose) {
  waiting++;
  busy = busy.then(async () => {
    clearTimeout(idleTimer);
    const d = config.dps;
    trace(`— ${name} —`);
    setStatus('связываюсь с лампой…');
    try {
      const l = await connected(mayChoose);
      // Ask the lamp where it stands: its remote or another phone may have changed it.
      trace('чтение состояния');
      await l.refresh([d.switch, d.bright, d.temp]);
      const read = () => ({
        on: l.dps.has(d.switch) ? Dp.int(l.dps.get(d.switch)) !== 0 : state.on,
        bright: l.dps.has(d.bright) ? Dp.int(l.dps.get(d.bright)) : state.bright,
        temp: l.dps.has(d.temp) ? Dp.int(l.dps.get(d.temp)) : state.temp,
      });
      state = read();
      const dps = ACTIONS[name](state, d);
      if (dps.length) {
        trace('команда');
        await l.set(dps);
      }
      state = read();
      localStorage.setItem('lamp-state', JSON.stringify(state));
      trace('готово');
      setStatus('');
      hidePrompt();
    } catch (e) {
      if (e instanceof NeedsTap) {
        if (name === 'refresh') setStatus('');
        else showPrompt(name);
      } else {
        const where = step;
        note(`ошибка: ${describe(e)} [${kind(e)}]`);
        if (available === false) setStatus('Bluetooth на телефоне выключен или запрещён этому браузеру', true);
        else setStatus(e instanceof TuyaError ? e.message : `ошибка Bluetooth (${where}): ${describe(e)}`, true);
      }
    }
    render();
    waiting--;
    // Keep the link for a moment, for a second tap; not longer. Where even a moment is too long,
    // only while more taps are waiting.
    if (IDLE_MS) idleTimer = setTimeout(release, IDLE_MS);
    else if (!waiting) release();
  });
}

// --- page --------------------------------------------------------------------------------

const $ = (id) => document.getElementById(id);
let statusText = '';
let statusIsError = false;

function setStatus(message, isError = false) {
  statusText = message;
  statusIsError = isError;
  render();
}

function render() {
  const d = config?.dps ?? DEFAULT_DPS;
  const m = mode(state, d);
  $('dim').classList.toggle('active', m === 'dim');
  $('bright').classList.toggle('active', m === 'bright');
  const percent = Math.min(100, Math.max(1, Math.round(((state.bright - d.brightMin) * 100) / (d.brightMax - d.brightMin))));
  const status = $('status');
  status.textContent = statusText || (state.on ? `Лампа включена, яркость ${percent} %` : 'Лампа выключена');
  status.classList.toggle('error', statusIsError);
}

function showPrompt(name) {
  $('prompt-action').textContent = document.querySelector(`[data-do="${name}"] span`)?.textContent ?? '';
  $('prompt').hidden = false;
  $('prompt').onclick = () => run(name, true);
  setStatus('');
}

function hidePrompt() {
  $('prompt').hidden = true;
}

function start() {
  const has = (thing) => (thing ? 'есть' : 'нет');
  note(`версия ${VERSION}, Bluetooth ${has(navigator.bluetooth)}, список известных устройств ${has(navigator.bluetooth?.getDevices)}, `
    + `полный экран ${has(document.documentElement.requestFullscreen)}`);
  note(navigator.userAgent);
  if (!navigator.bluetooth) {
    setStatus('Этот браузер не умеет Bluetooth. На iPhone откройте страницу в Bluefy, на Android — в Chrome.', true);
    return;
  }
  if (!config) {
    setStatus('Лампа не настроена: откройте ссылку настройки, которую выдаёт tools/web_link.py.', true);
    return;
  }
  for (const button of document.querySelectorAll('[data-do]')) {
    button.addEventListener('click', () => run(button.dataset.do, true));
  }
  // No pinch zoom: Safari's own gesture events, and two-finger moves elsewhere.
  for (const type of ['gesturestart', 'gesturechange', 'gestureend']) {
    document.addEventListener(type, (e) => e.preventDefault());
  }
  document.addEventListener('touchmove', (e) => {
    if (e.touches.length > 1) e.preventDefault();
  }, { passive: false });
  // Full screen where a phone's browser allows it (it never does without a tap, and Safari on an
  // iPhone not at all). Only on a tap that will not bring up the browser's device list: a page
  // going full screen can keep that list from appearing.
  if (matchMedia('(pointer: coarse)').matches) {
    document.addEventListener('click', () => {
      const page = document.documentElement;
      if (document.fullscreenElement || !page.requestFullscreen || !device) return;
      note('полный экран');
      page.requestFullscreen({ navigationUI: 'hide' })?.catch((e) => note(`полный экран не дали: ${describe(e)}`));
    });
  }
  // A page in the background gets no timers: without this the phone would keep the lamp's only
  // connection, and the next visit would find it taken.
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) release();
    // Back on screen: the lamp may have been changed meanwhile.
    else if (!BLUEFY) run('refresh', false);
  });
  window.addEventListener('pagehide', release);
  // Bluefy's own way of telling a page that the app went to the background or came back.
  if (BLUEFY) {
    try {
      const native = (window.BLENative ??= {});
      const theirs = native.notifyAppState;
      native.notifyAppState = (active) => {
        note(`Bluefy: приложение ${active ? 'на экране' : 'в фоне'}`);
        if (!active) release();
        return theirs?.call(native, active);
      };
    } catch (e) {
      note(`Bluefy не даёт следить за сворачиванием: ${describe(e)}`);
    }
  }
  navigator.bluetooth.getAvailability?.().then((on) => {
    available = on;
    note(`Bluetooth телефона: ${on ? 'включён' : 'выключен или запрещён'}`);
  }, () => {});
  render();
  // A shortcut may ask for an action straight away: ...?do=dim or ...?do=bright. Then the chooser
  // is tried at once; a browser that insists on a tap first gets the big button instead.
  const wanted = new URLSearchParams(location.search).get('do');
  if (wanted in ACTIONS) run(wanted, true);
  else if (!BLUEFY) run('refresh', false);
}

start();
