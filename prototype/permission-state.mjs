export const DEVICE_TYPES = Object.freeze({ ANDROID: 'android', XIAOMI: 'xiaomi' });

export const STANDARD_PERMISSION_KEYS = Object.freeze([
  'notifications',
  'exactAlarm',
  'fullScreen',
]);

export function createPermissionState() {
  return {
    device: DEVICE_TYPES.ANDROID,
    settingsEntry: 'available',
    standard: {
      notifications: 'missing',
      exactAlarm: 'missing',
      fullScreen: 'missing',
    },
    location: {
      access: 'unrequested',
      services: 'on',
    },
    xiaomi: {
      lockScreen: 'unconfirmed',
      backgroundPopup: 'unconfirmed',
    },
  };
}

export function missingAlarmDisplayPermissions(permissionState) {
  const missing = STANDARD_PERMISSION_KEYS.filter(key => permissionState.standard[key] !== 'granted');
  if (permissionState.device === DEVICE_TYPES.XIAOMI) {
    if (permissionState.xiaomi.lockScreen !== 'confirmed') missing.push('xiaomiLockScreen');
    if (permissionState.xiaomi.backgroundPopup !== 'confirmed') missing.push('xiaomiBackgroundPopup');
  }
  return missing;
}

export function standardPermissionLabel(status) {
  return status === 'granted' ? '模拟检查：已补齐' : '模拟检查：未补齐';
}

export function settingsEntryLabel(settingsEntry) {
  return settingsEntry === 'unavailable' ? '入口不可用或未找到' : '去设置';
}

export function locationPermissionLabel(location) {
  if (location.services === 'off') return '定位服务已关闭';
  return {
    unrequested: '尚未请求',
    denied: '已拒绝',
    approximate: '大致位置',
    precise: '精确位置',
  }[location.access] || '尚未请求';
}

export function xiaomiPermissionLabel(status) {
  return status === 'confirmed' ? '用户已确认 · 未自动核验' : '待手动确认';
}

export function canUseLocation(location) {
  return location.services === 'on' && ['approximate', 'precise'].includes(location.access);
}
