/* Figma-layout travel pages. Provider-dependent areas deliberately remain empty. */
import { AMAP_FIXTURE_STATES, amapFixtureState, resolveCommute } from './state.mjs';

const esc = value => String(value ?? '').replace(/[&>'"]/g, c => ({ '&':'&amp;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[c]));
const routeFixtures = Object.freeze({
  driving: [['推荐路线','18 分钟','畅通'],['备选 1','21 分钟','缓行'],['备选 2','24 分钟','畅通']],
  transit: [['推荐路线','31 分钟','实时到站'],['备选 1','36 分钟','换乘较少'],['备选 2','39 分钟','步行较少']],
  bicycling: [['推荐路线','26 分钟','道路通畅'],['备选 1','29 分钟','道路通畅'],['备选 2','32 分钟','坡度较缓']],
  'electric-bicycle': [['推荐路线','20 分钟','道路通畅'],['备选 1','23 分钟','道路通畅'],['备选 2','25 分钟','避开限行']],
  walking: [['推荐路线','48 分钟','步行路线'],['备选 1','52 分钟','遮阳较多'],['备选 2','56 分钟','人行道优先']],
});

export function createTravelScreens({ action, overlayAction, asset, state }) {
  const read = () => (typeof state === 'function' ? state() : state) || {};
  const link = (label, route, extra = '') => action(label, route, extra);
  const event = (label, name, value = '') => overlayAction(label, name, value);
  const image = (file, alt, className = '') => asset(file, alt, className);
  const fixture = () => { const s = read(); return amapFixtureState(s.runtime?.credentials, s.runtime?.amapFixture || AMAP_FIXTURE_STATES.SUCCESS); };
  const blank = (title, caption, className = '') => `<section class="provider-placeholder ${className}"><span aria-hidden="true">⌁</span><strong>${esc(title)}</strong><p>${esc(caption)}</p></section>`;
  const map = (className = '') => {
    const status = fixture();
    if (status === AMAP_FIXTURE_STATES.NO_KEY) return blank('需要运行时高德 Key', '在“数据与凭据”配置 Web 或 Android SDK Key 后查看离线演示。', className);
    if (status === AMAP_FIXTURE_STATES.LOADING) return blank('高德地图加载中', '离线 fixture 正在模拟加载状态。', className);
    if (status === AMAP_FIXTURE_STATES.DENIED) return blank('定位权限未授权', '可继续手动搜索或地图选点；不会请求后台定位。', className);
    if (status === AMAP_FIXTURE_STATES.ERROR) return blank('高德地图暂不可用', '离线 fixture 模拟服务或原生渲染失败；地点搜索与路线结果可继续使用。', className);
    return `<section class="amap-fixture-map ${className}" aria-label="高德地图离线 fixture"><i>高德地图 · 离线 fixture</i><b>起点</b><em>终点</em><span>当前路况：主路畅通，局部缓行</span></section>`;
  };
  const routeResult = commute => {
    if (fixture() !== AMAP_FIXTURE_STATES.SUCCESS) return map('travel-route-map');
    const options = (routeFixtures[commute.selectedTransport] || routeFixtures.driving).slice(0, 3);
    return `${map('travel-route-map')}<section class="amap-route-options"><h2>路线方案 <small>最多 3 条 · 当前路况 fixture</small></h2>${options.map(([name, duration, traffic], index) => `<button type="button" class="${index === 0 ? 'is-selected' : ''}"><b>${esc(name)}</b><strong>${esc(duration)}</strong><span>${esc(traffic)}</span></button>`).join('')}</section>`;
  };
  return {
    home() { const c = read().config || {}; const plans = c.alarmPlans || []; const first = plans.find(plan => plan.enabled); return `<div class="travel-home"><section class="travel-home-weather provider-placeholder provider-home"><span>⌁</span><strong>天气服务暂未接入</strong><p>接入彩云后显示天气与预报。</p></section><button type="button" class="travel-alarm" data-route="plans"><div class="travel-alarm-top">本地闹钟 <em>${first ? '已创建' : '空列表'}</em></div><div class="travel-alarm-time">${first ? esc(first.time) : '—'} <span>${first ? esc(first.name) : '添加第一个闹钟'}<small>${first ? '由 Android 注册与响铃' : '支持单次、每周和工作日'}</small></span></div><div class="travel-alarm-result"><div>已启用<b>${plans.filter(plan => plan.enabled).length} 个</b></div><div>下一步<b>${first ? '查看闹钟' : '立即添加'}</b></div></button><section class="travel-title"><h2>今天的通勤</h2>${link('查看路线', 'route')}</section>${map('travel-commute')}<p class="travel-assurance">高德地图仅在用户同意后初始化。</p></div>`; },
    weather() { return `<div class="travel-weather">${blank('天气图暂未接入', '接入彩云后显示降水图、预报和回放。', 'travel-weather-map')}<section class="travel-forecast"><header><h2>天气预报</h2><span>暂未接入</span></header><p>当前不展示温度、降水、天气缓冲或提前计算结果。</p><footer>基础闹钟不使用天气数据。</footer></section></div>`; },
    route() { const s = read(); const c = s.config || {}; const commute = resolveCommute(c); return `<div class="travel-route"><section class="travel-route-card"><header><span>${image('8e03947a-17d1-409a-bc9e-57f20be3f0a9.svg', '', 'travel-sun-icon')}</span><b>全局通勤</b>${link('编辑地点', 'route-edit')}</header><div class="travel-endpoints"><b>${esc(commute.origin || '未设置起点')}</b><i>→</i><b>${esc(commute.destination || '未设置终点')}</b><span>${esc(commute.selectedTransport)} · 计划可单独覆盖</span></div>${routeResult(commute)}</section><section class="travel-date-usage"><h2>常用地点</h2><p>地点搜索、输入提示、地图选点和单次定位使用高德接入契约。</p><div>${link('搜索地点', 'place-search')}<span>运行时 Key ›</span></div></section><p class="travel-source">高德地图与路线为离线 fixture；不会发送网络请求或暴露 Key。</p></div>`; },
    'route-edit'() { const s = read(); const c = s.config || {}; const r = s.runtime || {}; const commute = resolveCommute(c, r.routeScope === 'plan' ? r.alarmDraft : null); const modes = [['driving','驾车'],['transit','公交'],['bicycling','骑行'],['electric-bicycle','电动车'],['walking','步行']]; return `<div class="travel-editor"><p class="amap-scope-note">${r.routeScope === 'plan' ? '正在编辑：本计划通勤覆盖' : '正在编辑：全局通勤'}</p><section class="travel-place-inputs"><div><small>起点</small><b>${esc(commute.origin || '未设置')} · ${esc(commute.originAddress || '未选择')}</b>${event('⌕', 'open-place', 'origin')}</div><div><small>终点</small><b>${esc(commute.destination || '未设置')} · ${esc(commute.destinationAddress || '未选择')}</b>${event('⌕', 'open-place', 'destination')}</div></section><div class="travel-mode-row">${modes.map(([id, label]) => `<button type="button" data-action="mode" data-value="${id}" class="${commute.selectedTransport === id ? 'is-selected' : ''}"><span>⌁</span><span>${label}</span></button>`).join('')}</div>${map('travel-editor-map')}<div class="amap-map-actions">${event('地图选点', 'pick-map')}${event('使用当前位置', 'locate-once')}</div><section class="travel-arrival"><p>地图、定位和路线均为确定性视觉 fixture。定位只响应这一次点击，不持续跟踪。</p></section><footer class="screen-footer">${event(r.routeScope === 'plan' ? '保存本计划覆盖' : '保存全局通勤', 'save-route')}</footer></div>`; },
  };
}
