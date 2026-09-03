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
    await import(new URL(`../app.js?permission-integration=${Date.now()}`, import.meta.url));
    await run(browser, initial);
  } finally {
    for (const [key, descriptor] of Object.entries(savedGlobals)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else delete globalThis[key];
    }
  }
}

test('permission guide preserves an alarm draft and only continues the save once', async () => {
  await withBrowserStub(async ({ app, listeners, saved, window }, initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    assert.match(app.innerHTML, /完善响铃显示设置/);
    assert.equal(saved.get(STORAGE_KEY), initial);

    click({ target:control('open-permission-diagnostics') });
    assert.match(app.innerHTML, /可靠性诊断/);
    assert.match(app.innerHTML, /权限演示/);
    assert.match(app.innerHTML, /位置权限/);

    click({ target:control('return-permission-flow') });
    assert.match(app.innerHTML, /完善响铃显示设置/);
    click({ target:control('continue-permission-flow') });
    assert.match(app.innerHTML, /闹钟计划/);
    assert.notEqual(saved.get(STORAGE_KEY), initial);
  });
});

test('permission guide cancellation writes nothing and does not consume a later explicit confirmation', async () => {
  await withBrowserStub(async ({ app, listeners, saved, window }, initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    click({ target:control('cancel-permission-flow') });
    assert.equal(saved.get(STORAGE_KEY), initial);
    click({ target:control('save-alarm') });
    assert.match(app.innerHTML, /完善响铃显示设置/);
    assert.equal(saved.get(STORAGE_KEY), initial);
    click({ target:control('continue-permission-flow') });
    assert.notEqual(saved.get(STORAGE_KEY), initial);
  });
});

test('diagnostics back navigation restores the pending permission confirmation', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    click({ target:control('open-permission-diagnostics') });
    window.ZhituPrototype.navigate('plan-edit');
    assert.match(app.innerHTML, /完善响铃显示设置/);
  });
});

test('continuing after partially completing diagnostics records the current missing-permission state', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    click({ target:control('open-permission-diagnostics') });
    click({ target:control('open-permission-settings', 'notifications') });
    click({ target:control('set-standard-permission', 'notifications:granted') });
    click({ target:control('return-from-permission-settings') });
    click({ target:control('return-permission-flow') });
    click({ target:control('continue-permission-flow') });

    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    assert.doesNotMatch(app.innerHTML, /完善响铃显示设置/);
    assert.match(app.innerHTML, /闹钟计划/);
  });
});

test('alarm switch click never bypasses the change-event permission confirmation', async () => {
  await withBrowserStub(async ({ app, listeners, saved, window }, _initial) => {
    const click = listeners.get('click');
    const change = listeners.get('change');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    click({ target:control('continue-permission-flow') });
    const id = /data-action="toggle-alarm" data-value="([^"]+)"/.exec(app.innerHTML)?.[1];
    assert.ok(id);

    change({ target:{ dataset:{ action:'toggle-alarm', value:id }, checked:false } });
    const disabledSnapshot = saved.get(STORAGE_KEY);
    listeners.get('permission-device:change')({ target:{ value:'xiaomi' } });
    change({ target:{ dataset:{ action:'toggle-alarm', value:id }, checked:true } });
    assert.match(app.innerHTML, /完善响铃显示设置/);
    click({ target:control('toggle-alarm', id) });
    assert.equal(saved.get(STORAGE_KEY), disabledSnapshot);
  });
});

test('location permission demo only starts from current-location action and returns a precise fixture', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    const input = listeners.get('input');
    click({ target:control('amap-consent', 'approved') });
    input({ target:{ dataset:{ credential:'amapSdkKey' }, value:'fixture-key' } });
    window.ZhituPrototype.navigate('place-search');
    click({ target:control('locate-once') });
    assert.match(app.innerHTML, /使用当前位置（演示）/);
    click({ target:control('resolve-location-request', 'precise') });
    assert.match(app.innerHTML, /当前位置（演示）/);
  });
});

test('denied location exposes settings recovery and keeps search as an alternative', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    const input = listeners.get('input');
    click({ target:control('amap-consent', 'approved') });
    input({ target:{ dataset:{ credential:'amapSdkKey' }, value:'fixture-key' } });
    window.ZhituPrototype.navigate('place-search');
    click({ target:control('locate-once') });
    click({ target:control('resolve-location-request', 'denied') });
    assert.match(app.innerHTML, /当前位置不可用（演示）/);
    assert.match(app.innerHTML, /继续搜索或地图选点/);
    click({ target:control('open-permission-settings', 'location') });
    assert.match(app.innerHTML, /模拟位置设置/);
    click({ target:control('return-from-permission-settings') });
    assert.match(app.innerHTML, /使用当前位置（演示）/);
  });
});

test('location settings recovery consumes the request and never replays it on a later settings return', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    const input = listeners.get('input');
    click({ target:control('amap-consent', 'approved') });
    input({ target:{ dataset:{ credential:'amapSdkKey' }, value:'fixture-key' } });
    window.ZhituPrototype.navigate('place-search');
    click({ target:control('locate-once') });
    click({ target:control('resolve-location-request', 'denied') });
    click({ target:control('open-permission-settings', 'location') });
    click({ target:control('set-location-access', 'precise') });
    click({ target:control('return-from-permission-settings') });
    assert.match(app.innerHTML, /当前位置（演示）/);

    window.ZhituPrototype.navigate('diagnostics');
    click({ target:control('open-permission-settings', 'location') });
    click({ target:control('return-from-permission-settings') });
    assert.match(app.innerHTML, /可靠性诊断/);
    assert.doesNotMatch(app.innerHTML, /当前位置不可用（演示）/);
  });
});

test('a repeated current-location click rebuilds a service-disabled request before settings recovery', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    const input = listeners.get('input');
    click({ target:control('amap-consent', 'approved') });
    input({ target:{ dataset:{ credential:'amapSdkKey' }, value:'fixture-key' } });
    window.ZhituPrototype.navigate('place-search');
    click({ target:control('locate-once') });
    click({ target:control('resolve-location-request', 'services-off') });
    click({ target:control('cancel-location-request') });

    click({ target:control('locate-once') });
    assert.match(app.innerHTML, /当前位置不可用（演示）/);
    click({ target:control('open-permission-settings', 'location') });
    assert.match(app.innerHTML, /模拟关闭定位服务|模拟开启定位服务/);
    click({ target:control('toggle-location-services') });
    click({ target:control('set-location-access', 'precise') });
    click({ target:control('return-from-permission-settings') });
    assert.match(app.innerHTML, /当前位置（演示）/);
  });
});

test('leaving a route-edit location request clears its continuation context', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    const input = listeners.get('input');
    click({ target:control('amap-consent', 'approved') });
    input({ target:{ dataset:{ credential:'amapSdkKey' }, value:'fixture-key' } });
    window.ZhituPrototype.navigate('route-edit');
    click({ target:control('locate-once') });
    assert.match(app.innerHTML, /使用当前位置（演示）/);
    window.ZhituPrototype.navigate('plans');
    window.ZhituPrototype.navigate('diagnostics');
    click({ target:control('open-permission-settings', 'location') });
    assert.match(app.innerHTML, /位置权限只会在点击“使用当前位置”后请求/);
  });
});

test('Xiaomi display settings remain manual after returning from the simulated settings screen', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('diagnostics');
    listeners.get('permission-device:change')({ target:{ value:'xiaomi' } });
    assert.match(app.innerHTML, /锁屏显示/);
    assert.match(app.innerHTML, /待手动确认/);
    click({ target:control('open-permission-settings', 'lockScreen') });
    assert.match(app.innerHTML, /不会自动确认/);
    click({ target:control('return-from-permission-settings') });
    assert.match(app.innerHTML, /待手动确认/);
    click({ target:control('confirm-xiaomi-permission', 'lockScreen') });
    assert.match(app.innerHTML, /用户已确认 · 未自动核验/);
  });
});

test('diagnostics keeps unrequested location informational until current-location is clicked', async () => {
  await withBrowserStub(async ({ app, listeners, window }, _initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('diagnostics');
    click({ target:control('open-permission-settings', 'location') });
    assert.match(app.innerHTML, /位置权限只会在点击“使用当前位置”后请求/);
    assert.doesNotMatch(app.innerHTML, /允许精确位置/);
  });
});

test('scenario toolbar can simulate an unavailable settings entry without confirming Xiaomi settings', async () => {
  await withBrowserStub(async ({ app, listeners, permissionEntry, window }, _initial) => {
    const click = listeners.get('click');
    window.ZhituPrototype.navigate('diagnostics');
    listeners.get('permission-entry:change')({ target:{ value:'unavailable' } });
    assert.equal(permissionEntry.value, 'unavailable');
    assert.match(app.innerHTML, /入口不可用或未找到/);
    click({ target:control('open-permission-settings', 'notifications') });
    assert.match(app.innerHTML, /找不到对应系统设置入口/);
  });
});

test('popstate leaving the permission origin clears an otherwise stale continuation', async () => {
  await withBrowserStub(async ({ app, listeners, location, window }, _initial) => {
    const click = listeners.get('click');
    const popstate = listeners.get('window:popstate');
    window.ZhituPrototype.navigate('plans');
    click({ target:control('new-alarm') });
    click({ target:control('save-alarm') });
    click({ target:control('open-permission-diagnostics') });

    location.hash = '#/plan-edit';
    popstate();
    assert.match(app.innerHTML, /完善响铃显示设置/);
    location.hash = '#/plans';
    popstate();
    assert.doesNotMatch(app.innerHTML, /完善响铃显示设置/);

    window.ZhituPrototype.navigate('diagnostics');
    assert.doesNotMatch(app.innerHTML, /返回继续启用/);
  });
});
