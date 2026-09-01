import { AMAP_DEMO_TIPS, AMAP_FIXTURE_STATES, amapFixtureState } from './state.mjs';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
export function createSettingsScreens({ asset, state, overlayAction }) {
  const read = () => state();
  const icon = file => asset(file, '', 'settings-icon');
  const link = (label, route, value = '›') => `<button type="button" class="settings-link" data-route="${route}"><span>${label}</span><b>${value}</b></button>`;
  return {
    settings() {
      const { config: c } = read();
      const toggle = (key, label, note, file) => `<label class="settings-toggle">${icon(file)}<span><b>${label}</b><small>${note}</small></span><input type="checkbox" data-setting="${key}" ${c[key] ? 'checked' : ''}><i></i></label>`;
      return `<div class="settings-page"><section class="settings-summary"><div>${icon('b7256810-29df-4240-b463-4dc964155356.svg')}<h2>本地闹钟</h2><em>Android 负责调度</em></div><p>通知、精确闹钟、全屏提醒和响铃状态以 Android 设备诊断为准。</p></section><section class="settings-card"><h2>提醒偏好</h2>${toggle('notificationsEnabled','通知提醒','Android 端实际申请通知权限','207f0477-a230-4d7f-b6cf-941e2c5409ad.svg')}${toggle('lockSummaryEnabled','锁屏摘要','Android 端根据系统能力显示','09d81958-b63a-432d-a2a8-2bdc245f70a8.svg')}</section><section class="settings-card"><div class="settings-card-title"><h2>系统权限</h2><button data-route="diagnostics">查看全部</button></div>${link('通知、精确闹钟与全屏提醒','diagnostics','检查 ›')}</section><section class="settings-card"><h2>本地数据</h2>${link('常用地点与出行方式','place-search','管理 ›')}${link('工作日日历','calendar','查看 ›')}${link('数据与凭据','credentials','管理 ›')}${link('首次使用说明','onboarding','查看 ›')}</section><p class="settings-note">浏览器原型不读取设备权限、不播放铃声，也不创建系统闹钟。</p></div>`;
    },
    'place-search'() {
      const { config: c, runtime: r } = read(); const query = r.placeQuery || ''; const fixture = amapFixtureState(r.credentials, r.amapFixture || AMAP_FIXTURE_STATES.SUCCESS); const results = fixture === AMAP_FIXTURE_STATES.SUCCESS ? [...AMAP_DEMO_TIPS, ...(c.favorites || [])].filter(place => !query || `${place.name}${place.address}`.includes(query)) : (c.favorites || []).filter(place => !query || `${place.name}${place.address}`.includes(query)); const selected = r.selectedPlace;
      const item = place => `<button type="button" class="place-result ${selected?.id === place.id ? 'is-selected' : ''}" data-action="choose-place" data-value="${esc(place.id)}"><span><b>${esc(place.name)} · ${esc(place.address)}</b><small>${esc(place.description || '本机文字地点')}</small></span><i>选择 ›</i></button>`;
      const status = fixture === AMAP_FIXTURE_STATES.SUCCESS ? '输入提示与 POI 搜索 · 离线 fixture' : fixture === AMAP_FIXTURE_STATES.NO_KEY ? '未配置运行时 Web Key；仅显示本地地点' : fixture === AMAP_FIXTURE_STATES.DENIED ? '定位被拒绝；仍可手动搜索与地图选点' : fixture === AMAP_FIXTURE_STATES.LOADING ? '输入提示加载中 · 离线 fixture' : '高德搜索错误 · 离线 fixture';
      return `<div class="places-page"><label class="place-search-field"><span>搜索地点</span><input data-field="placeQuery" value="${esc(query)}" placeholder="输入名称或地址文字" autocomplete="off"></label><p class="place-search-help">${status}</p><button type="button" class="place-locate" data-action="locate-once">使用当前位置（仅本次）</button><section class="places-card"><h2>搜索结果</h2>${results.length ? results.map(item).join('') : '<p>没有匹配地点。</p>'}<button type="button" class="text-link" data-action="add-favorite">添加本地地点 ＋</button></section><section class="places-card place-selected"><h2>已选 · ${esc(selected ? `${selected.name} · ${selected.address}` : '未选择')}</h2><p>展示地点不会写入真实坐标。</p></section><footer class="screen-footer">${overlayAction('使用这个地点', 'use-place')}</footer></div>`;
    },
  };
}
