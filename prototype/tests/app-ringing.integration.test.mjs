import assert from 'node:assert/strict';
import test from 'node:test';
import { createDefaultState, persistentSettingsSnapshot } from '../state.mjs';

const STORAGE_KEY = 'zhitu-prototype-config-v3';

function defaultStoredConfig() {
  return persistentSettingsSnapshot({
    ...createDefaultState(), origin:'', originAddress:'', destination:'', destinationAddress:'', selectedTransport:'driving', favorites:[], notificationsEnabled:true, lockSummaryEnabled:true, onboardingDone:false,
  });
}

function installBrowserStub(serialized) {
  const saved = new Map([[STORAGE_KEY, serialized]]);
  const listeners = new Map();
  const app = { innerHTML:'' };
  const scenario = { value:'', addEventListener(type, listener) { listeners.set(`scenario:${type}`, listener); } };
  const permissionDevice = { value:'android', addEventListener(type, listener) { listeners.set(`permission-device:${type}`, listener); } };
  const permissionEntry = { value:'available', addEventListener(type, listener) { listeners.set(`permission-entry:${type}`, listener); } };
  const reset = { addEventListener(type, listener) { listeners.set(`reset:${type}`, listener); } };
  const location = { hash:'' };
  const document = {
    fonts: { ready:Promise.resolve() },
    documentElement: { style:{ setProperty() {} } },
    addEventListener(type, listener) { listeners.set(type, listener); },
    dispatchEvent() {},
    getElementById(id) { return id === 'app' ? app : id === 'scenario-select' ? scenario : id === 'permission-device-select' ? permissionDevice : id === 'permission-entry-select' ? permissionEntry : reset; },
    querySelectorAll() { return []; },
    querySelector() { return null; },
  };
  const window = { innerWidth:1000, addEventListener(type, listener) { listeners.set(`window:${type}`, listener); } };
  const localStorage = { getItem:key => saved.get(key) ?? null, setItem:(key, value) => saved.set(key, value) };
  const history = { pushState(_state, _title, hash) { location.hash = hash; }, replaceState(_state, _title, hash) { location.hash = hash; } };
  return { app, document, history, listeners, localStorage, location, permissionDevice, permissionEntry, saved, window };
}

function control(action, value = '') {
  return { disabled:false, dataset:{ action, value }, closest() { return this; } };
}

async function withBrowserStub(run) {
  const savedGlobals = Object.fromEntries(['document', 'window', 'localStorage', 'history', 'location', 'CustomEvent', 'setTimeout', 'clearTimeout'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  const initial = JSON.stringify(defaultStoredConfig());
  const browser = installBrowserStub(initial);
  Object.assign(globalThis, {
    document: browser.document,
    window: browser.window,
    localStorage: browser.localStorage,
    history: browser.history,
    location: browser.location,
    CustomEvent: class { constructor(type, init) { this.type = type; this.detail = init?.detail; } },
    setTimeout: () => 0,
    clearTimeout: () => {},
  });
  try {
    await import(new URL(`../app.js?ringing-integration=${Date.now()}`, import.meta.url));
    await run(browser, initial);
  } finally {
    for (const [key, descriptor] of Object.entries(savedGlobals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
}

test('app entry wires full-screen ringing actions without changing browser storage', async () => {
  await withBrowserStub(async ({ app, listeners, saved, window }, initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('lock');
    assert.match(app.innerHTML, /Android 系统界面/);
    assert.doesNotMatch(app.innerHTML, /system-screen/);
    window.ZhituPrototype.navigate('ringing');
    assert.match(app.innerHTML, /知途 · 提前闹钟/);
    assert.doesNotMatch(app.innerHTML, /prototype-header/);

    click({ target:control('ringing-stop') });
    assert.match(app.innerHTML, /本次响铃已停止/);
    click({ target:control('ringing-replay') });
    assert.match(app.innerHTML, /今天，比平时早 12 分钟/);

    click({ target:control('ringing-snooze') });
    assert.match(app.innerHTML, /07:28/);
    assert.match(app.innerHTML, /已贪睡 10 分钟/);
    click({ target:control('ringing-again') });
    assert.match(app.innerHTML, /贪睡 10 分钟/);
    assert.match(app.innerHTML, /贪睡后再次响铃/);
    assert.doesNotMatch(app.innerHTML, /已贪睡 10 分钟/);

    window.ZhituPrototype.navigate('plans');
    window.ZhituPrototype.navigate('ringing');
    assert.match(app.innerHTML, /07:18/);
    assert.doesNotMatch(app.innerHTML, /已贪睡 10 分钟/);
    assert.equal(saved.get(STORAGE_KEY), initial);
  });
});

test('app reset rebuilds a ringing session while preserving an already-default stored configuration', async () => {
  await withBrowserStub(async ({ app, saved, window }, initial) => {
    window.ZhituPrototype.navigate('ringing-basic');
    assert.match(app.innerHTML, /07:30/);
    window.ZhituPrototype.reset();
    window.ZhituPrototype.navigate('ringing-basic');
    assert.match(app.innerHTML, /按设定时间提醒/);
    assert.equal(saved.get(STORAGE_KEY), initial);
  });
});
