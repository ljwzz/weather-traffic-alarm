import assert from 'node:assert/strict';
import test from 'node:test';
import { createDefaultState, persistentSettingsSnapshot } from '../state.mjs';

const STORAGE_KEY = 'zhitu-prototype-config-v3';

function defaultStoredConfig() {
  return persistentSettingsSnapshot({
    ...createDefaultState(), origin:'', originAddress:'', destination:'', destinationAddress:'', selectedTransport:'driving', favorites:[], onboardingDone:false,
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

async function withBrowserStub(run, initial = JSON.stringify(defaultStoredConfig())) {
  const savedGlobals = Object.fromEntries(['document', 'window', 'localStorage', 'history', 'location', 'CustomEvent', 'setTimeout', 'clearTimeout'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
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

test('early ringing opens only its linked decision after the unlock entry and keeps the ringing session for return', async () => {
  await withBrowserStub(async ({ app, listeners, window }) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('ringing');
    assert.match(app.innerHTML, /解锁后查看提前原因/);

    click({ target:control('ringing-view-reason') });
    assert.match(app.innerHTML, /本次决策/);
    assert.match(app.innerHTML, /提前 12 分钟/);
    assert.match(app.innerHTML, /实际注册 07:18/);
    assert.match(app.innerHTML, /当前实例 early:2026-09-02T07:18:00#0/);

    click({ target:control('back') });
    assert.match(app.innerHTML, /知途 · 提前闹钟/);
    assert.match(app.innerHTML, /07:18/);
  });
});

test('direct detail routes are empty without an explicit id and restore only the id in the hash', async () => {
  await withBrowserStub(async ({ app, location, window }) => {
    window.ZhituPrototype.navigate('why');
    assert.match(app.innerHTML, /本次决策不可用/);

    window.ZhituPrototype.navigate('why?decisionId=decision-fixture-work-advanced');
    assert.match(app.innerHTML, /提前 12 分钟/);
    assert.equal(location.hash, '#/why?decisionId=decision-fixture-work-advanced');
  });
});

test('evaluations keep one explicit record per plan, ignore a duplicate click, and retain a deleted plan snapshot', async () => {
  const initial = JSON.stringify({
    ...defaultStoredConfig(),
    onboardingDone:true,
    alarmPlans:[
      { id:'plan-a', name:'计划 A', time:'07:00', enabled:true, repeat:{ kind:'workdays' } },
      { id:'plan-b', name:'计划 B', time:'08:00', enabled:true, repeat:{ kind:'workdays' } },
    ],
  });
  await withBrowserStub(async ({ app, listeners, window }) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('home');
    click({ target:control('evaluate-plan', 'plan-a') });
    click({ target:control('evaluate-plan', 'plan-a') });
    await Promise.resolve();
    click({ target:control('evaluate-plan', 'plan-b') });
    await Promise.resolve();
    click({ target:control('delete-alarm', 'plan-a') });
    window.ZhituPrototype.navigate('history');

    assert.equal((app.innerHTML.match(/计划 A/g) || []).length, 1);
    assert.equal((app.innerHTML.match(/计划 B/g) || []).length, 1);
  }, initial);
});

test('re-evaluating a valid plan leaves the viewed historical failure selected', async () => {
  const initial = JSON.stringify({
    ...defaultStoredConfig(),
    onboardingDone:true,
    alarmPlans:[{ id:'fixture-work', name:'当前计划', time:'07:30', enabled:true, repeat:{ kind:'workdays' } }],
  });
  await withBrowserStub(async ({ app, listeners, window }) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('history');
    click({ target:control('open-decision', 'decision-fixture-work-retry') });
    assert.match(app.innerHTML, /评估失败，等待重试/);

    click({ target:control('re-evaluate-decision') });
    await Promise.resolve();
    assert.match(app.innerHTML, /评估失败，等待重试/);
    assert.match(app.innerHTML, /正在查看的历史记录未改写/);
  }, initial);
});
