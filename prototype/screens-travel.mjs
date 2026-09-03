/* Figma-layout travel pages. Provider-dependent areas deliberately remain empty. */
import { AMAP_FIXTURE_STATES, CAIYUN_FIXTURE_STATES, EVALUATION_FIXTURE_STATES, amapFixtureState, caiyunFixtureState, createEvaluationFixture, resolveCommute } from './state.mjs';

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
  const weatherFixture = () => caiyunFixtureState(read().runtime?.caiyunFixture || CAIYUN_FIXTURE_STATES.SUCCESS);
  const evaluation = () => {
    const runtime = read().runtime || {};
    return runtime.evaluationRun || createEvaluationFixture({
      fixture: runtime.evaluationFixture || EVALUATION_FIXTURE_STATES.PENDING,
      plan: runtime.evaluationPlan,
      transport: resolveCommute(read().config || {}).selectedTransport,
      selectedRouteIndex: runtime.selectedRouteIndex,
    });
  };
  const evaluationPanel = ({ detailed = false } = {}) => {
    const run = evaluation(); const r = read().runtime || {};
    const planOptions = r.evaluationPlans || [];
    const stateOptions = [
      [EVALUATION_FIXTURE_STATES.PENDING, '待评估'],
      [EVALUATION_FIXTURE_STATES.RUNNING, '评估中'],
      [EVALUATION_FIXTURE_STATES.ADVANCED, '成功提前'],
      [EVALUATION_FIXTURE_STATES.NO_ADVANCE, '无需提前'],
      [EVALUATION_FIXTURE_STATES.RETRY, '失败重试'],
      [EVALUATION_FIXTURE_STATES.DEADLINE, '截止'],
      [EVALUATION_FIXTURE_STATES.EXPIRED, '过期'],
    ];
    return `<section class="evaluation-panel is-${esc(run.state)}"><header><div><small>自动评估 · 离线 fixture</small><h2>${esc(run.title)}</h2></div><span>${esc(run.inputs.targetDate)}</span></header><p>${esc(run.detail)}</p>${detailed ? `<div class="evaluation-inputs"><span>${esc(run.inputs.route.transport)} ${run.inputs.route.minutes} 分钟</span><span>${esc(run.inputs.weather.condition)} +${run.inputs.weather.bufferMinutes} 分钟</span><span>${esc(run.inputs.dayRule.label)}</span></div><div class="evaluation-plans">${planOptions.map(plan => `<button type="button" data-action="select-evaluation-plan" data-value="${esc(plan.id)}" class="${r.selectedEvaluationPlanId === plan.id ? 'is-selected' : ''}">${esc(plan.name)} · ${esc(plan.time)}</button>`).join('')}</div><div class="evaluation-fixtures">${stateOptions.map(([id, label]) => `<button type="button" data-action="select-evaluation-fixture" data-value="${id}" class="${r.evaluationFixture === id ? 'is-selected' : ''}">${label}</button>`).join('')}</div><button type="button" class="evaluation-run" data-action="evaluate-now">立即评估</button>` : ''}<footer>${link('查看计算分解', 'why')}${link('决策记录', 'history')}</footer></section>`;
  };
  const blank = (title, caption, className = '') => `<section class="provider-placeholder ${className}"><span aria-hidden="true">⌁</span><strong>${esc(title)}</strong><p>${esc(caption)}</p></section>`;
  const selectedRouteIndex = count => {
    const index = Number(read().runtime?.selectedRouteIndex);
    return Number.isInteger(index) && index >= 0 && index < count ? index : 0;
  };
  const routeLines = (options, selectedIndex) => options.map(([name], index) =>
    `<button type="button" class="amap-route-line${index === selectedIndex ? ' is-selected' : ''}" data-action="select-route" data-value="${index}" aria-label="选择${esc(name)}" aria-pressed="${index === selectedIndex}"></button>`,
  ).join('');
  const map = (className = '', options = [], selectedIndex = 0) => {
    const status = fixture();
    if (status === AMAP_FIXTURE_STATES.NO_KEY) return blank('需要运行时高德 Key', '在“数据与凭据”配置 Web 或 Android SDK Key 后查看离线演示。', className);
    if (status === AMAP_FIXTURE_STATES.LOADING) return blank('高德地图加载中', '离线 fixture 正在模拟加载状态。', className);
    if (status === AMAP_FIXTURE_STATES.DENIED) return blank('定位权限未授权', '可继续手动搜索或地图选点；不会请求后台定位。', className);
    if (status === AMAP_FIXTURE_STATES.ERROR) return blank('高德地图暂不可用', '离线 fixture 模拟服务或原生渲染失败；地点搜索与路线结果可继续使用。', className);
    const lines = options.length ? `<div class="amap-route-lines" aria-label="可选择的路线折线">${routeLines(options, selectedIndex)}</div>` : '';
    return `<section class="amap-fixture-map ${className}" aria-label="高德地图离线 fixture">${lines}<i>高德地图 · 离线 fixture</i><b>起点</b><em>终点</em><span>当前路况：主路畅通，局部缓行</span></section>`;
  };
  const routeResult = commute => {
    if (fixture() !== AMAP_FIXTURE_STATES.SUCCESS) return map('travel-route-map');
    const options = (routeFixtures[commute.selectedTransport] || routeFixtures.driving).slice(0, 3);
    const selectedIndex = selectedRouteIndex(options.length);
    return `${map('travel-route-map', options, selectedIndex)}<section class="amap-route-options"><h2>路线方案 <small>最多 3 条 · 当前路况 fixture</small></h2>${options.map(([name, duration, traffic], index) => `<button type="button" class="${index === selectedIndex ? 'is-selected' : ''}" data-action="select-route" data-value="${index}" aria-pressed="${index === selectedIndex}"><b>${esc(name)}</b><strong>${esc(duration)}</strong><span>${esc(traffic)}</span></button>`).join('')}</section>`;
  };
  return {
    home() { const c = read().config || {}; const r = read().runtime || {}; const plans = c.alarmPlans || []; const first = plans.find(plan => plan.enabled); const evaluationPlans = r.evaluationPlans || []; const status = weatherFixture(); const label = ({ loading:'加载中', success:'模拟成功', cached:'模拟缓存', error:'模拟错误' })[status]; return `<div class="travel-home"><button type="button" class="travel-home-weather provider-placeholder provider-home" data-route="weather"><span>⌁</span><strong>彩云天气 · ${label}</strong><p>本地 fixture；不请求真实 API。</p></button><button type="button" class="travel-alarm" data-route="plans"><div class="travel-alarm-top">本地闹钟 <em>${first ? '已创建' : '空列表'}</em></div><div class="travel-alarm-time">${first ? esc(first.time) : '—'} <span>${first ? esc(first.name) : '添加第一个闹钟'}<small>${first ? '由 Android 注册与响铃' : '支持单次、每周和工作日'}</small></span></div><div class="travel-alarm-result"><div>已启用<b>${plans.filter(plan => plan.enabled).length} 个</b></div><div>下一步<b>${first ? '查看闹钟' : '立即添加'}</b></div></button><section class="home-evaluation-actions"><header><h2>可评估计划</h2>${link('选择计划', 'weather')}</header>${evaluationPlans.map(plan => `<div><span>${esc(plan.name)} · ${esc(plan.time)}</span><button type="button" data-action="evaluate-plan" data-value="${esc(plan.id)}">立即评估</button></div>`).join('')}</section>${evaluationPanel()}<section class="travel-title"><h2>今天的通勤</h2>${link('查看路线', 'route')}</section>${map('travel-commute')}<p class="travel-assurance">高德地图仅在用户同意后初始化。</p></div>`; },
    weather() { const status = weatherFixture(); const panel = evaluationPanel({ detailed:true }); if (status === CAIYUN_FIXTURE_STATES.LOADING) return `<div class="travel-weather">${blank('彩云天气加载中', '本地 fixture 正在模拟加载；未发送网络请求。', 'travel-weather-map')}<section class="travel-forecast"><header><h2>天气预报</h2><span>彩云天气 · 模拟</span></header><p>正在等待模拟结果。</p></section>${panel}</div>`; if (status === CAIYUN_FIXTURE_STATES.ERROR) return `<div class="travel-weather">${blank('彩云天气暂不可用', '本地 fixture 模拟服务错误；未发送网络请求。', 'travel-weather-map')}<section class="travel-forecast"><header><h2>天气预报</h2><span>彩云天气 · 模拟错误</span></header><p>手动预览错误不会自动改变已记录的评估结果。</p></section>${panel}</div>`; const cached = status === CAIYUN_FIXTURE_STATES.CACHED; return `<div class="travel-weather"><section class="caiyun-weather-fixture travel-weather-map" aria-label="彩云天气本地 fixture"><div><span>${cached ? '缓存数据' : '模拟成功'}</span><strong>22°</strong><b>小雨</b><p>降水概率 60% · 体感 21°</p></div><small>彩云天气 · ${cached ? '本地缓存 fixture' : '本地 fixture'}</small></section><section class="travel-forecast"><header><h2>天气预报</h2><span>彩云天气</span></header><p>${cached ? '正在展示缓存的模拟天气数据。' : '正在展示确定性的模拟天气数据。'}</p><div class="travel-hours"><button class="is-selected"><small>现在</small><b>22°</b><em>小雨</em></button><button><small>08:00</small><b>23°</b><em>小雨</em></button><button><small>09:00</small><b>24°</b><em>阴</em></button><button><small>10:00</small><b>25°</b><em>阴</em></button><button><small>11:00</small><b>26°</b><em>多云</em></button></div><footer>手动天气预览与自动评估 fixture 相互独立。</footer></section>${panel}</div>`; },
    route() { const s = read(); const c = s.config || {}; const commute = resolveCommute(c); return `<div class="travel-route"><section class="travel-route-card"><header><span>${image('8e03947a-17d1-409a-bc9e-57f20be3f0a9.svg', '', 'travel-sun-icon')}</span><b>全局通勤</b>${link('编辑地点', 'route-edit')}</header><div class="travel-endpoints"><b>${esc(commute.origin || '未设置起点')}</b><i>→</i><b>${esc(commute.destination || '未设置终点')}</b><span>${esc(commute.selectedTransport)} · 计划可单独覆盖</span></div>${routeResult(commute)}</section><section class="travel-date-usage"><h2>常用地点</h2><p>地点搜索、输入提示、地图选点和单次定位使用高德接入契约。</p><div>${link('搜索地点', 'place-search')}<span>运行时 Key ›</span></div></section><p class="travel-source">高德地图与路线为离线 fixture；不会发送网络请求或暴露 Key。</p></div>`; },
    'route-edit'() { const s = read(); const c = s.config || {}; const r = s.runtime || {}; const commute = resolveCommute(c, r.routeScope === 'plan' ? r.alarmDraft : null); const modes = [['driving','驾车'],['transit','公交'],['bicycling','骑行'],['electric-bicycle','电动车'],['walking','步行']]; return `<div class="travel-editor"><p class="amap-scope-note">${r.routeScope === 'plan' ? '正在编辑：本计划通勤覆盖' : '正在编辑：全局通勤'}</p><section class="travel-place-inputs"><div><small>起点</small><b>${esc(commute.origin || '未设置')} · ${esc(commute.originAddress || '未选择')}</b>${event('⌕', 'open-place', 'origin')}</div><div><small>终点</small><b>${esc(commute.destination || '未设置')} · ${esc(commute.destinationAddress || '未选择')}</b>${event('⌕', 'open-place', 'destination')}</div></section><div class="travel-mode-row">${modes.map(([id, label]) => `<button type="button" data-action="mode" data-value="${id}" class="${commute.selectedTransport === id ? 'is-selected' : ''}"><span>⌁</span><span>${label}</span></button>`).join('')}</div>${map('travel-editor-map')}<div class="amap-map-actions">${event('地图选点', 'pick-map')}${event('使用当前位置', 'locate-once')}</div><section class="travel-arrival"><p>地图、定位和路线均为确定性视觉 fixture。定位只响应这一次点击，不持续跟踪。</p></section><footer class="screen-footer">${event(r.routeScope === 'plan' ? '保存本计划覆盖' : '保存全局通勤', 'save-route')}</footer></div>`; },
  };
}
