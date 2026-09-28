/* Travel pages share the current home preview states and results. */
import { AMAP_FIXTURE_STATES, EVALUATION_FIXTURE_STATES, amapFixtureState, createEvaluationFixture, resolveCommute } from './state.mjs';

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
      [EVALUATION_FIXTURE_STATES.REGISTRATION_FAILED, '注册失败'],
      [EVALUATION_FIXTURE_STATES.INSUFFICIENT_ADVANCE, '提前额度不足'],
      [EVALUATION_FIXTURE_STATES.DEADLINE, '截止'],
      [EVALUATION_FIXTURE_STATES.SKIPPED, '跳过'],
      [EVALUATION_FIXTURE_STATES.EXPIRED, '过期'],
    ];
    return `<section class="evaluation-panel is-${esc(run.state)}"><header><div><small>自动评估 · 离线 fixture</small><h2>${esc(run.title)}</h2></div><span>${esc(run.inputs.targetDate)}</span></header><p>${esc(run.detail)}</p>${detailed ? `<div class="evaluation-inputs"><span>${esc(run.inputs.route.transport)} ${run.inputs.route.minutes} 分钟</span><span>${esc(run.inputs.weather.condition)} +${run.inputs.weather.bufferMinutes} 分钟</span><span>${esc(run.inputs.dayRule.label)}</span></div><div class="evaluation-plans">${planOptions.map(plan => `<button type="button" data-action="select-evaluation-plan" data-value="${esc(plan.id)}" class="${r.selectedEvaluationPlanId === plan.id ? 'is-selected' : ''}">${esc(plan.name || '闹钟')} · ${esc(plan.time)}</button>`).join('')}</div><div class="evaluation-fixtures">${stateOptions.map(([id, label]) => `<button type="button" data-action="select-evaluation-fixture" data-value="${id}" class="${r.evaluationFixture === id ? 'is-selected' : ''}">${label}</button>`).join('')}</div><button type="button" class="evaluation-run" data-action="evaluate-now" ${r.evaluationSubmitting ? 'disabled' : ''}>${r.evaluationSubmitting ? '正在生成…' : '立即评估'}</button>` : ''}<footer>${event('查看本次决策', 'open-current-decision')}${link('决策记录', 'history')}</footer></section>`;
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
  const preview = kind => read().runtime?.homePreview?.[kind] || { state:'config-loading', result:null, refreshing:false };
  const previewAction = (kind, state) => {
    if (kind === 'weather') {
      if (['config-error', 'credential-missing'].includes(state)) return link('配置凭据', 'credentials');
      if (state === 'location-missing') return link('完善地点', 'route-edit');
    } else {
      if (state === 'authorization-missing') return link('完成授权', 'onboarding');
      if (['config-error', 'web-key-missing'].includes(state)) return link('配置凭据', 'credentials');
      if (state === 'location-missing') return link('完善地点', 'route-edit');
    }
    return event('重试', 'refresh-home-preview', kind);
  };
  const previewStatus = (kind, card) => {
    const labels = kind === 'weather'
      ? {
        'config-loading':'正在读取天气配置', 'config-error':'无法读取凭据配置', 'credential-missing':'尚未配置彩云凭据', 'location-missing':'尚未配置通勤地点', loading:'正在获取天气', cached:'晴好天气', empty:'没有可用天气', error:'无法获取天气', success:'晴好天气',
      }
      : {
        'config-loading':'正在读取路线配置', 'config-error':'无法读取凭据配置', 'authorization-missing':'等待高德地图专项授权', 'web-key-missing':'尚未配置高德 Web Key', 'location-missing':'尚未配置通勤地点', loading:'正在查询驾车路线', cached:'展示缓存路线', empty:'未找到可用驾车路线', error:'无法获取路线', success:'路线已更新',
      };
    const result = card.result;
    const detail = result
      ? kind === 'weather'
        ? result.severity
        : `${result.transport} · ${result.distance} · ${result.duration}`
      : labels[card.state] || '等待更新';
    const mapSdkStatus = (() => {
      if (kind !== 'route') return '';
      const r = read().runtime || {};
      if (!r.credentials?.amapSdkKey) return ' · 地图：需要配置 Android Key';
      if (r.amapFixture === 'loading') return ' · 地图：正在加载';
      if (r.amapFixture === 'denied') return ' · 地图：定位未授权';
      if (r.amapFixture === 'error') return ' · 地图：暂不可用';
      return ' · 地图：可用';
    })();
    const meta = result
      ? kind === 'weather'
        ? `${result.endpoints} · 数据时间：${result.observedAt} · ${card.state === 'cached' ? '数据来自本地缓存' : result.source}${card.state === 'error' ? ' · 更新失败，保留上次结果' : ''}`
        : `${result.endpoints} · 数据时间：${result.observedAt} · ${card.state === 'cached' ? '数据来自本地缓存' : result.source}${card.state === 'error' ? ' · 更新失败，保留上次结果' : ''}${mapSdkStatus}`
      : card.state === 'loading' && card.result ? '保留上次结果，正在更新' : '离线 fixture；不请求真实 API。';
    return { label:labels[card.state] || '等待更新', detail, meta };
  };
  const previewCard = kind => {
    const card = preview(kind); const content = previewStatus(kind, card); const title = kind === 'weather' ? '彩云天气' : '通勤路线'; const route = kind === 'weather' ? 'weather' : 'route';
    const canOpen = card.result || ['success', 'cached', 'empty', 'error', 'loading'].includes(card.state);
    return `<section class="home-preview-card is-${esc(card.state)}" data-preview-kind="${kind}"><button type="button" class="home-preview-open" data-route="${route}" ${canOpen ? '' : 'aria-disabled="true"'}><header><span>${title}</span><small>${esc(content.label)}</small></header><strong>${esc(content.detail)}</strong><p>${esc(content.meta)}</p></button>${!['success', 'cached'].includes(card.state) ? `<footer>${previewAction(kind, card.state)}</footer>` : ''}</section>`;
  };
  return {
    home() { const c = read().config || {}; const r = read().runtime || {}; const plans = c.alarmPlans || []; const first = plans.find(plan => plan.enabled); const evaluationPlans = r.evaluationPlans || []; return `<div class="travel-home" data-refreshing="${Boolean(preview('weather').refreshing || preview('route').refreshing)}"><section class="home-preview-heading"><h2>今日数据</h2><button type="button" data-action="refresh-home-preview" aria-label="刷新通勤预览">${preview('weather').refreshing || preview('route').refreshing ? '刷新中…' : '刷新通勤预览'}</button></section>${previewCard('weather')}<section class="home-evaluation-actions"><header><h2>可评估计划</h2>${link('选择计划', 'weather')}</header>${evaluationPlans.map(plan => `<div><span>${esc(plan.name || '闹钟')} · ${esc(plan.time)}</span><button type="button" data-action="evaluate-plan" data-value="${esc(plan.id)}">立即评估</button></div>`).join('')}</section>${evaluationPanel()}<button type="button" class="travel-alarm" data-route="plans"><div class="travel-alarm-top">本地闹钟 <em>${first ? '已创建' : '空列表'}</em></div><div class="travel-alarm-time">${first ? esc(first.time) : '—'} <span>${first ? esc(first.name || '闹钟') : '添加第一个闹钟'}<small>${first ? '由 Android 注册与响铃' : '支持单次、每周和工作日'}</small></span></div><div class="travel-alarm-result"><div>已启用<b>${plans.filter(plan => plan.enabled).length} 个</b></div><div>下一步<b>${first ? '查看闹钟' : '立即添加'}</b></div></button>${previewCard('route')}<p class="travel-assurance">下拉或点按“刷新通勤预览”只更新天气与路线预览，不创建评估或闹钟。</p></div>`; },
    weather() {
      const home = preview('weather'); const panel = evaluationPanel({ detailed:true });
      if (!home.result || !['success', 'cached'].includes(home.state)) return `<div class="travel-weather">${previewCard('weather')}${panel}</div>`;
      const cached = home.state === 'cached'; const result = home.result;
      return `<div class="travel-weather"><p class="home-preview-detail">首页预览：${esc(result.severity)} · ${esc(result.endpoints)} · 数据时间：${esc(result.observedAt)} · ${esc(cached ? '数据来自本地缓存' : result.source)}</p><section class="caiyun-weather-fixture travel-weather-map" aria-label="彩云天气本地 fixture"><div><span>${cached ? '缓存数据' : '模拟成功'}</span><strong class="home-weather-grade">${esc(result.severity)}</strong><p>${esc(result.endpoints)} · 数据时间：${esc(result.observedAt)}</p></div><small>${esc(cached ? '数据来自本地缓存' : result.source)}</small></section><section class="travel-forecast"><header><h2>天气预报</h2><span>彩云天气</span></header><p>正在展示与首页相同的确定性模拟天气数据。</p></section>${panel}</div>`;
    },
    route() {
      const home = preview('route'); const s = read(); const c = s.config || {}; const commute = resolveCommute(c);
      if (!home.result || !['success', 'cached'].includes(home.state)) return `<div class="travel-route">${previewCard('route')}<section class="travel-date-usage"><h2>全局通勤</h2><p>设置起点、终点和出行方式。</p><div>${link('编辑地点', 'route-edit')}<span>运行时 Key ›</span></div></section></div>`;
      const result = home.result; const cached = home.state === 'cached';
      return `<div class="travel-route"><p class="home-preview-detail">首页预览：${esc(result.transport)} · ${esc(result.distance)} · ${esc(result.duration)} · ${esc(result.endpoints)} · 数据时间：${esc(result.observedAt)} · ${esc(cached ? '数据来自本地缓存' : result.source)}</p><section class="travel-route-card"><header><span>${image('8e03947a-17d1-409a-bc9e-57f20be3f0a9.svg', '', 'travel-sun-icon')}</span><b>全局通勤</b>${link('编辑地点', 'route-edit')}</header><div class="travel-endpoints"><b>${esc(commute.origin || '未设置起点')}</b><i>→</i><b>${esc(commute.destination || '未设置终点')}</b><span>${esc(result.transport)} · ${esc(result.distance)} · ${esc(result.duration)}</span></div>${routeResult(commute)}</section></div>`;
    },
    'route-edit'() { const s = read(); const c = s.config || {}; const r = s.runtime || {}; const commute = resolveCommute(c, r.routeScope === 'plan' ? r.alarmDraft : null); const modes = [['driving','驾车'],['transit','公交'],['bicycling','骑行'],['electric-bicycle','电动车'],['walking','步行']]; return `<div class="travel-editor"><p class="amap-scope-note">${r.routeScope === 'plan' ? '正在编辑：本计划通勤覆盖' : '正在编辑：全局通勤'}</p><section class="travel-place-inputs"><div><small>起点</small><b>${esc(commute.origin || '未设置')} · ${esc(commute.originAddress || '未选择')}</b>${event('⌕', 'open-place', 'origin')}</div><div><small>终点</small><b>${esc(commute.destination || '未设置')} · ${esc(commute.destinationAddress || '未选择')}</b>${event('⌕', 'open-place', 'destination')}</div></section><div class="travel-mode-row">${modes.map(([id, label]) => `<button type="button" data-action="mode" data-value="${id}" class="${commute.selectedTransport === id ? 'is-selected' : ''}"><span>⌁</span><span>${label}</span></button>`).join('')}</div>${map('travel-editor-map')}<div class="amap-map-actions">${event('地图选点', 'pick-map')}<button type="button" class="route-locate" data-action="locate-once"><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="3"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/></svg>使用当前位置</button></div><section class="travel-arrival"><p>地图、定位和路线均为确定性视觉 fixture。定位只响应这一次点击，不持续跟踪。</p></section><footer class="screen-footer">${event(r.routeScope === 'plan' ? '保存本计划覆盖' : '保存全局通勤', 'save-route')}</footer></div>`; },
  };
}
