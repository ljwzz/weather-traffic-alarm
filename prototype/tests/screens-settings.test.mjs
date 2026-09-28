import assert from 'node:assert/strict';
import test from 'node:test';
import { createPermissionState, DEVICE_TYPES } from '../permission-state.mjs';
import { createDefaultState } from '../state.mjs';
import { createSettingsScreens } from '../screens-settings.mjs';

function render(permissions) {
  const snapshot = {
    config: { ...createDefaultState(), favorites:[] },
    runtime: { permissionState:permissions, placeQuery:'', credentials:{}, amapFixture:'success', selectedPlace:null, weatherBufferExpanded:false },
  };
  return createSettingsScreens({ asset: () => '', state: () => snapshot, overlayAction: () => '' }).settings();
}

test('settings keep reliability and each active management path without device-specific rows', () => {
  const permissions = createPermissionState();
  permissions.device = DEVICE_TYPES.XIAOMI;
  const html = render(permissions);
  assert.match(html, /权限与诊断[\s\S]*有 5 项设置待检查。[\s\S]*data-route="diagnostics"/);
  assert.match(html, /data-route="route"[\s\S]*通勤地点与路线/);
  assert.match(html, /data-route="calendar"[\s\S]*工作日日历/);
  assert.match(html, /data-route="credentials"[\s\S]*数据与凭据/);
  assert.match(html, /data-route="weather-buffers"[\s\S]*天气缓冲/);
  assert.match(html, /data-route="onboarding"[\s\S]*隐私与地图授权/);
  assert.doesNotMatch(html, /小米锁屏显示|小米后台弹出界面|位置权限|通知提醒|锁屏摘要/);
});

test('weather buffers are edited on their own screen with three independent profiles', () => {
  const permissions = createPermissionState();
  const snapshot = {
    config: createDefaultState(),
    runtime: { permissionState:permissions, placeQuery:'', credentials:{}, amapFixture:'success', selectedPlace:null, weatherBufferDraft:null },
  };
  const screens = createSettingsScreens({ asset: () => '', state: () => snapshot, overlayAction: () => '' });
  assert.doesNotMatch(screens.settings(), /data-weather-buffer=/);
  const html = screens['weather-buffers']();
  assert.match(html, /工作日[\s\S]*data-weather-buffer="workday"/);
  assert.match(html, /周末[\s\S]*data-weather-buffer="weekend"/);
  assert.match(html, /法定休息日[\s\S]*data-weather-buffer="statutoryHoliday"/);
  assert.equal((html.match(/data-action="save-weather-buffer"/g) || []).length, 3);
});
