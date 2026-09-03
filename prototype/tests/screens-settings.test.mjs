import assert from 'node:assert/strict';
import test from 'node:test';
import { createPermissionState, DEVICE_TYPES } from '../permission-state.mjs';
import { createSettingsScreens } from '../screens-settings.mjs';

function render(permissions) {
  const snapshot = {
    config: { notificationsEnabled:true, lockSummaryEnabled:true, favorites:[] },
    runtime: { permissionState:permissions, placeQuery:'', credentials:{}, amapFixture:'success', selectedPlace:null },
  };
  return createSettingsScreens({ asset: () => '', state: () => snapshot, overlayAction: () => '' }).settings();
}

test('generic Android settings omit Xiaomi-only display entries and show the live location state', () => {
  const permissions = createPermissionState();
  const html = render(permissions);
  assert.match(html, /位置权限[\s\S]*尚未请求/);
  assert.doesNotMatch(html, /小米锁屏显示/);
  assert.doesNotMatch(html, /小米后台弹出界面/);
});

test('Xiaomi settings expose live manual-confirmation and location states', () => {
  const permissions = createPermissionState();
  permissions.device = DEVICE_TYPES.XIAOMI;
  permissions.location.access = 'approximate';
  permissions.xiaomi.lockScreen = 'confirmed';
  const html = render(permissions);
  assert.match(html, /位置权限[\s\S]*大致位置/);
  assert.match(html, /小米锁屏显示[\s\S]*用户已确认 · 未自动核验/);
  assert.match(html, /小米后台弹出界面[\s\S]*待手动确认/);
});
