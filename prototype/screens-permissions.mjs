import {
  DEVICE_TYPES,
  locationPermissionLabel,
  settingsEntryLabel,
  standardPermissionLabel,
  xiaomiPermissionLabel,
} from './permission-state.mjs';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));

export function createPermissionScreens({ state, overlayAction }) {
  const button = (label, action, value = '', className = 'permission-button') => `<button type="button" class="${className}" data-action="${action}" data-value="${esc(value)}">${esc(label)}</button>`;
  const settingsAction = (key, settingsEntry) => settingsEntry === 'unavailable'
    ? '<span class="permission-entry-unavailable">入口不可用或未找到</span>'
    : button('去设置', 'open-permission-settings', key);
  const standardRow = (label, key, status, purpose, settingsEntry) => `<div class="permission-row"><span><b>${label}</b><small>用途：${purpose}</small><small>${standardPermissionLabel(status)} · 模拟操作</small></span>${settingsAction(key, settingsEntry)}</div>`;
  const xiaomiRow = (label, key, status, purpose, settingsEntry) => `<div class="permission-row"><span><b>${label}</b><small>用途：${purpose}</small><small>${xiaomiPermissionLabel(status)} · 模拟操作</small></span><div class="permission-actions">${settingsAction(key, settingsEntry)}${button('我已手动确认', 'confirm-xiaomi-permission', key, 'permission-button is-tonal')}</div></div>`;

  return {
    diagnostics() {
      const permissions = state().runtime.permissionState;
      const isXiaomi = permissions.device === DEVICE_TYPES.XIAOMI;
      const returnAction = state().runtime.permissionFlow ? button('返回继续启用', 'return-permission-flow', '', 'support-button is-primary') : '';
      const deviceLabel = isXiaomi ? '小米（演示设备）' : '通用 Android';
      return `<section class="support-screen permission-screen"><article class="permission-hero"><div><h2>权限准备</h2><em>权限演示 · ${deviceLabel}</em></div><p>此页仅演示系统设置与检查流程，不会读取或修改设备权限。</p></article><article class="permission-card"><h2>通用 Android</h2>${standardRow('通知权限', 'notifications', permissions.standard.notifications, '显示响铃通知与操作入口', permissions.settingsEntry)}${standardRow('精确闹钟', 'exactAlarm', permissions.standard.exactAlarm, '按设定时间触发本地闹钟', permissions.settingsEntry)}${standardRow('全屏提醒', 'fullScreen', permissions.standard.fullScreen, '锁屏与后台响铃页面；解锁时可能以横幅展示', permissions.settingsEntry)}<div class="permission-row"><span><b>位置权限</b><small>用途：仅点击当前位置后单次使用</small><small>${locationPermissionLabel(permissions.location)} · 模拟操作</small></span>${settingsAction('location', permissions.settingsEntry)}</div></article>${isXiaomi ? `<article class="permission-card"><h2>小米系统显示</h2>${xiaomiRow('锁屏显示', 'lockScreen', permissions.xiaomi.lockScreen, '锁屏时展示完整响铃页面', permissions.settingsEntry)}${xiaomiRow('后台弹出界面', 'backgroundPopup', permissions.xiaomi.backgroundPopup, '从后台弹出完整响铃页面', permissions.settingsEntry)}</article>` : ''}<article class="permission-card"><h2>位置使用演示</h2><p>位置仅在点击“使用当前位置”后请求；可选择拒绝、大致位置、精确位置或定位服务已关闭。拒绝或不可用时仍可搜索和地图选点。</p><small class="permission-caption">系统设置为模拟界面；打开不会直接授权，返回后才重新检查。${permissions.settingsEntry === 'unavailable' ? `当前演示：${settingsEntryLabel(permissions.settingsEntry)}。` : ''}</small></article><div class="permission-footer-actions">${button('重新检查（演示）', 'recheck-diagnostics')}${returnAction}</div></section>`;
    },
    renderOverlay(name) {
      const runtime = state().runtime;
      const permissions = runtime.permissionState;
      const sheet = (title, body, actions) => `<div class="support-overlay" role="dialog" aria-modal="true" aria-label="${esc(title)}"><section class="support-sheet permission-sheet"><i class="support-sheet-handle"></i><h2>${esc(title)}</h2>${body}<div class="support-sheet-actions">${actions}</div></section></div>`;
      if (name === 'permission-guide') return sheet(
        '完善响铃显示设置',
        '<p class="support-sheet-description">显示权限未补齐时，锁屏或后台可能无法展示完整响铃页面。仍可继续启用。</p><p class="permission-caption">演示不会修改设备设置；可去检查后返回继续当前操作。</p>',
        `${button('去检查', 'open-permission-diagnostics')}${button('继续启用', 'continue-permission-flow', '', 'support-button is-primary')}${button('取消', 'cancel-permission-flow', '', 'permission-cancel')}`,
      );
      if (name === 'permission-settings') {
        const target = runtime.permissionSettingsTarget;
        if (permissions.settingsEntry === 'unavailable') return sheet(
          '系统设置入口（演示）',
          '<p class="support-sheet-description">当前演示设备找不到对应系统设置入口。此状态不代表实际设备结果；可返回后继续检查或手动确认厂商项。</p>',
          `${button('返回并重新检查', 'return-from-permission-settings', '', 'support-button is-primary')}`,
        );
        const title = target === 'location' ? '模拟位置设置' : '模拟系统设置';
      const body = target === 'location' && !runtime.locationRequest
          ? '<p class="support-sheet-description">位置权限只会在点击“使用当前位置”后请求。当前未发起位置请求，请返回地点页后点击该入口；搜索和地图选点无需位置权限。</p>'
          : target === 'location'
          ? `<p class="support-sheet-description">打开本页不会直接授权。选择演示状态后，返回诊断页重新检查。</p><div class="permission-choice-grid">${[['unrequested','尚未请求'],['denied','拒绝'],['approximate','允许大致位置'],['precise','允许精确位置']].map(([value, label]) => button(label, 'set-location-access', value, `permission-choice ${permissions.location.access === value ? 'is-selected' : ''}`)).join('')}</div><div class="permission-choice-grid is-single">${button(permissions.location.services === 'on' ? '模拟关闭定位服务' : '模拟开启定位服务', 'toggle-location-services', '', 'permission-choice')}</div>`
          : target === 'lockScreen' || target === 'backgroundPopup'
            ? '<p class="support-sheet-description">厂商系统权限只能由用户在设备设置中确认。此模拟设置不会自动确认；返回诊断页后可选择“我已手动确认”。</p>'
            : `<p class="support-sheet-description">打开本页不会直接授权。选择模拟结果后，返回诊断页重新检查。</p><div class="permission-choice-grid">${button('模拟未补齐', 'set-standard-permission', `${target}:missing`, `permission-choice ${permissions.standard[target] === 'missing' ? 'is-selected' : ''}`)}${button('模拟已补齐', 'set-standard-permission', `${target}:granted`, `permission-choice ${permissions.standard[target] === 'granted' ? 'is-selected' : ''}`)}</div>`;
        return sheet(title, body, `${button('返回并重新检查', 'return-from-permission-settings', '', 'support-button is-primary')}`);
      }
      if (name === 'location-request') return sheet(
        '使用当前位置（演示）',
        `<p class="support-sheet-description">仅本次点击触发位置权限演示。可拒绝、允许大致位置、允许精确位置，或模拟定位服务已关闭。</p><div class="permission-choice-grid">${button('拒绝', 'resolve-location-request', 'denied', 'permission-choice')}${button('允许大致位置', 'resolve-location-request', 'approximate', 'permission-choice')}${button('允许精确位置', 'resolve-location-request', 'precise', 'permission-choice')}${button('定位服务已关闭', 'resolve-location-request', 'services-off', 'permission-choice')}</div><p class="permission-caption">拒绝或不可用时，可继续搜索和地图选点。</p>`,
        `${button('取消', 'cancel-location-request')}`,
      );
      if (name === 'location-unavailable') {
        const unavailable = permissions.location.services === 'off' ? '定位服务已关闭。可进入模拟设置恢复后重新检查，也可继续使用搜索和地图选点。' : '位置权限被拒绝。可进入模拟设置恢复后重新检查，也可继续使用搜索和地图选点。';
        return sheet(
          '当前位置不可用（演示）',
          `<p class="support-sheet-description">${unavailable}</p><p class="permission-caption">“去设置”不会直接授权；返回后按当前演示状态重新检查。</p>`,
          `${button('去设置', 'open-permission-settings', 'location')}${button('继续搜索或地图选点', 'cancel-location-request', '', 'support-button is-primary')}`,
        );
      }
      return '';
    },
  };
}
