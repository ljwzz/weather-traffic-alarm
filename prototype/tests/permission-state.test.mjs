import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DEVICE_TYPES,
  canUseLocation,
  createPermissionState,
  locationPermissionLabel,
  missingAlarmDisplayPermissions,
  xiaomiPermissionLabel,
} from '../permission-state.mjs';

test('permission runtime defaults are transient and require the display permissions', () => {
  const permissions = createPermissionState();
  assert.equal(permissions.device, DEVICE_TYPES.ANDROID);
  assert.deepEqual(missingAlarmDisplayPermissions(permissions), ['notifications', 'exactAlarm', 'fullScreen']);
  assert.equal(locationPermissionLabel(permissions.location), '尚未请求');
});

test('Xiaomi manual confirmation is distinct from standard permission state', () => {
  const permissions = createPermissionState();
  permissions.device = DEVICE_TYPES.XIAOMI;
  permissions.standard.notifications = 'granted';
  permissions.standard.exactAlarm = 'granted';
  permissions.standard.fullScreen = 'granted';
  assert.deepEqual(missingAlarmDisplayPermissions(permissions), ['xiaomiLockScreen', 'xiaomiBackgroundPopup']);
  assert.equal(xiaomiPermissionLabel(permissions.xiaomi.lockScreen), '待手动确认');
  permissions.xiaomi.lockScreen = 'confirmed';
  assert.equal(xiaomiPermissionLabel(permissions.xiaomi.lockScreen), '用户已确认 · 未自动核验');
});

test('location accepts approximate and precise access only while service remains enabled', () => {
  const permissions = createPermissionState();
  permissions.location.access = 'approximate';
  assert.equal(canUseLocation(permissions.location), true);
  permissions.location.services = 'off';
  assert.equal(locationPermissionLabel(permissions.location), '定位服务已关闭');
  assert.equal(canUseLocation(permissions.location), false);
});
