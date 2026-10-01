// A stand-in for the lamp, for trying the page on a computer: it speaks the lamp's side of the
// protocol through objects shaped like the browser's Bluetooth ones. Not part of the published page.
//
//   const { fakeLamp } = await import('/fake-lamp.js');
//   const lamp = fakeLamp(JSON.parse(localStorage.getItem('lamp-config')));
//   navigator.bluetooth.requestDevice = async () => lamp.device;
import { Code, Dp, TuyaCodec } from './tuya.js';

export function fakeLamp(config) {
  const codec = new TuyaCodec(config.localKey, config.uuid, config.deviceId);
  const state = new Map([[20, Dp.bool(20, false)], [22, Dp.value(22, 500)], [23, Dp.value(23, 500)]]);
  // What happened to the connection, in order: 'connect', 'disconnect'.
  const log = [];

  const notify = new EventTarget();
  notify.startNotifications = async () => notify;
  const send = (code, data, responseTo = 0) => {
    for (const chunk of codec.build(code, data, responseTo).chunks) {
      notify.value = new DataView(chunk.buffer, chunk.byteOffset, chunk.byteLength);
      notify.dispatchEvent(new Event('characteristicvaluechanged'));
    }
  };

  const write = {
    async writeValueWithoutResponse(chunk) {
      const message = codec.feed(new Uint8Array(chunk));
      if (!message) return;
      switch (message.code) {
        case Code.DEVICE_INFO: {
          const info = new Uint8Array(46);
          info[2] = 3; // protocol version
          info[5] = 1; // bound to an account
          info.set([1, 2, 3, 4, 5, 6], 6); // the random number the session key is made from
          send(Code.DEVICE_INFO, info, message.seq);
          codec.onDeviceInfo(info);
          break;
        }
        case Code.PAIR:
          send(Code.PAIR, Uint8Array.of(0), message.seq);
          break;
        case Code.DEVICE_STATUS:
          send(Code.DEVICE_STATUS, Uint8Array.of(0), message.seq);
          send(Code.RECEIVE_DP, Dp.encode([...message.data].map((id) => state.get(id)).filter(Boolean)));
          break;
        case Code.DPS:
          for (const dp of Dp.decode(message.data, 0)) state.set(dp.id, dp);
          send(Code.DPS, Uint8Array.of(0), message.seq);
          break;
      }
    },
  };

  const service = { getCharacteristic: async (uuid) => (uuid.startsWith('00002b10') ? notify : write) };
  const server = { getPrimaryService: async () => service };
  const device = Object.assign(new EventTarget(), {
    id: 'fake-lamp',
    name: 'TY',
    gatt: {
      connected: false,
      async connect() {
        log.push('connect');
        codec.reset();
        this.connected = true;
        return server;
      },
      disconnect() {
        if (this.connected) log.push('disconnect');
        this.connected = false;
      },
    },
  });

  return { device, log, value: (id) => Dp.int(state.get(id)) };
}
