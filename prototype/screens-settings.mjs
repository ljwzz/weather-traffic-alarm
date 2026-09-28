import { AMAP_DEMO_TIPS, AMAP_FIXTURE_STATES, DAY_KINDS, DEFAULT_WEATHER_BUFFERS, amapFixtureState } from './state.mjs';
import { missingAlarmDisplayPermissions } from './permission-state.mjs';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
export function createSettingsScreens({ state, overlayAction }) {
  const read = () => state();
  const link = (label, route, value = '›') => `<button type="button" class="settings-link" data-route="${route}"><span>${label}</span><b>${value}</b></button>`;
  const bufferLabels = {
    [DAY_KINDS.WORKDAY]: '工作日',
    [DAY_KINDS.WEEKEND]: '周末',
    [DAY_KINDS.STATUTORY_HOLIDAY]: '法定休息日',
  };
  const bufferEditor = (kind, values) => `<section class="settings-buffer-profile"><header><strong>${bufferLabels[kind]}</strong><button type="button" data-action="save-weather-buffer" data-value="${kind}">保存</button></header><div>${['轻度','中度','重度'].map((label, index) => `<label><span>${label}</span><input type="number" min="0" max="60" step="1" inputmode="numeric" data-weather-buffer="${kind}" data-weather-buffer-index="${index}" value="${values[index]}"><small>分钟</small></label>`).join('')}</div></section>`;
  return {
    settings() {
      const { config: c, runtime: r } = read();
      const permissionState = r.permissionState;
      const missing = missingAlarmDisplayPermissions(permissionState);
      const summary = missing.length ? `有 ${missing.length} 项设置待检查。` : '设置已完成检查。';
      return `<div class="settings-page"><section class="settings-summary"><h2>权限与诊断</h2><p>${summary}</p>${link('提醒权限与诊断','diagnostics','查看 ›')}</section><section class="settings-card"><h2>通勤与数据</h2>${link('通勤地点与路线','route','管理 ›')}${link('工作日日历','calendar','查看 ›')}${link('数据与凭据','credentials','管理 ›')}${link('天气缓冲','weather-buffers','管理 ›')}</section><section class="settings-card"><h2>隐私</h2>${link('隐私与地图授权','onboarding','查看 ›')}</section><section class="settings-card settings-about"><img src="./assets/app-icon.svg" alt="知途 App 图标"><div><h2>知途</h2><p>版本 0.1.0 · 构建 1</p><small>本地原型 · 系统能力为离线演示</small></div></section></div>`;
    },
    'weather-buffers'() {
      const { config: c, runtime: r } = read();
      const buffers = r.weatherBufferDraft || c.weatherBuffers || DEFAULT_WEATHER_BUFFERS;
      return `<div class="settings-page"><section class="settings-summary"><h2>按日期类型分别保存</h2><p>工作日、周末和法定休息日各自使用独立缓冲。</p></section><div class="settings-buffer-profiles">${Object.keys(bufferLabels).map(kind => bufferEditor(kind, buffers[kind] || DEFAULT_WEATHER_BUFFERS[kind])).join('')}</div></div>`;
    },
    'place-search'() {
      const { config: c, runtime: r } = read(); const query = r.placeQuery || ''; const fixture = amapFixtureState(r.credentials, r.amapFixture || AMAP_FIXTURE_STATES.SUCCESS); const results = fixture === AMAP_FIXTURE_STATES.SUCCESS ? [...AMAP_DEMO_TIPS, ...(c.favorites || [])].filter(place => !query || `${place.name}${place.address}`.includes(query)) : (c.favorites || []).filter(place => !query || `${place.name}${place.address}`.includes(query)); const selected = r.selectedPlace;
      const item = place => `<button type="button" class="place-result ${selected?.id === place.id ? 'is-selected' : ''}" data-action="choose-place" data-value="${esc(place.id)}"><span><b>${esc(place.name)} · ${esc(place.address)}</b><small>${esc(place.description || '本机文字地点')}</small></span><i>选择 ›</i></button>`;
      const status = fixture === AMAP_FIXTURE_STATES.SUCCESS ? '输入提示与 POI 搜索 · 离线 fixture' : fixture === AMAP_FIXTURE_STATES.NO_KEY ? '未配置运行时 Web Key；仅显示本地地点' : fixture === AMAP_FIXTURE_STATES.DENIED ? '定位被拒绝；仍可手动搜索与地图选点' : fixture === AMAP_FIXTURE_STATES.LOADING ? '输入提示加载中 · 离线 fixture' : '高德搜索错误 · 离线 fixture';
      return `<div class="places-page"><label class="place-search-field"><span>搜索地点</span><input data-field="placeQuery" value="${esc(query)}" placeholder="输入名称或地址文字" autocomplete="off"></label><p class="place-search-help">${status}</p><button type="button" class="place-locate" data-action="locate-once">使用当前位置（仅本次）</button><section class="places-card"><h2>搜索结果</h2>${results.length ? results.map(item).join('') : '<p>没有匹配地点。</p>'}<button type="button" class="text-link" data-action="add-favorite">添加本地地点 ＋</button></section><section class="places-card place-selected"><h2>已选 · ${esc(selected ? `${selected.name} · ${selected.address}` : '未选择')}</h2><p>展示地点不会写入真实坐标。</p></section><footer class="screen-footer">${overlayAction('使用这个地点', 'use-place')}</footer></div>`;
    },
  };
}
