/* Figma-layout travel pages. Provider-dependent areas deliberately remain empty. */
const esc = value => String(value ?? '').replace(/[&>'"]/g, c => ({ '&':'&amp;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[c]));

export function createTravelScreens({ action, overlayAction, asset, state }) {
  const read = () => (typeof state === 'function' ? state() : state) || {};
  const config = () => read().config || read();
  const link = (label, route, extra = '') => action(label, route, extra);
  const event = (label, name, value = '') => overlayAction(label, name, value);
  const image = (file, alt, className = '') => asset(file, alt, className);
  const blank = (title, caption, className = '') => `<section class="provider-placeholder ${className}"><span aria-hidden="true">⌁</span><strong>${esc(title)}</strong><p>${esc(caption)}</p></section>`;

  return {
    home() {
      const c = config(); const plans = c.alarmPlans || [];
      const first = plans.find(plan => plan.enabled);
      return `<div class="travel-home">
        <section class="travel-home-weather provider-placeholder provider-home"><span>⌁</span><strong>天气服务暂未接入</strong><p>接入彩云后显示天气与预报。</p></section>
        <button type="button" class="travel-alarm" data-route="plans"><div class="travel-alarm-top">本地闹钟 <em>${first ? '已创建' : '空列表'}</em></div><div class="travel-alarm-time">${first ? esc(first.time) : '—'} <span>${first ? esc(first.name) : '添加第一个闹钟'}<small>${first ? '由 Android 注册与响铃' : '支持单次、每周和工作日'}</small></span></div><div class="travel-alarm-result"><div>已启用<b>${plans.filter(plan => plan.enabled).length} 个</b></div><div>下一步<b>${first ? '查看闹钟' : '立即添加'}</b></div></button>
        <section class="travel-title"><h2>今天的通勤</h2>${link('查看路线', 'route')}</section>
        ${blank('地图与路线暂未接入', '接入高德后显示路线、距离和耗时。', 'travel-commute')}
        <p class="travel-assurance">基础闹钟不依赖地图或天气，可离线创建与管理。</p>
      </div>`;
    },
    weather() {
      return `<div class="travel-weather">${blank('天气图暂未接入', '接入彩云后显示降水图、预报和回放。', 'travel-weather-map')}<section class="travel-forecast"><header><h2>天气预报</h2><span>暂未接入</span></header><p>当前不展示温度、降水、天气缓冲或提前计算结果。</p><footer>基础闹钟不使用天气数据。</footer></section></div>`;
    },
    route() {
      const c = config();
      return `<div class="travel-route"><section class="travel-route-card"><header><span>${image('8e03947a-17d1-409a-bc9e-57f20be3f0a9.svg', '', 'travel-sun-icon')}</span><b>通勤路线</b>${link('编辑地点', 'route-edit')}</header><div class="travel-endpoints"><b>${esc(c.origin || '未设置起点')}</b><i>→</i><b>${esc(c.destination || '未设置终点')}</b><span>仅保存文字地点</span></div>${blank('地图暂未接入', '接入高德后显示路线与候选路径。', 'travel-route-map')}<footer>不展示距离、耗时和路况结果</footer></section><section class="travel-date-usage"><h2>常用地点</h2><p>地点只用于本地保存，不阻塞基础闹钟。</p><div>${link('管理地点', 'place-search')}<span>本机保存　›</span></div></section><p class="travel-source">地图和路线服务暂未接入。</p></div>`;
    },
    'route-edit'() {
      const c = config(); const modes = [['driving','驾车'],['transit','公交'],['bicycling','骑行'],['electric-bicycle','电动车'],['walking','步行']];
      return `<div class="travel-editor"><section class="travel-place-inputs"><div><small>起点</small><b>${esc(c.origin || '未设置')} · ${esc(c.originAddress || '本机文字地点')}</b>${event('⌕', 'open-place', 'origin')}</div><div><small>终点</small><b>${esc(c.destination || '未设置')} · ${esc(c.destinationAddress || '本机文字地点')}</b>${event('⌕', 'open-place', 'destination')}</div></section><div class="travel-mode-row">${modes.map(([id, label]) => `<button type="button" data-action="mode" data-value="${id}" class="${c.selectedTransport === id ? 'is-selected' : ''}"><span>⌁</span><span>${label}</span></button>`).join('')}</div>${blank('地图选点暂未接入', '可保存常用地点文字；不会请求定位或地图服务。', 'travel-editor-map')}<section class="travel-arrival"><p>出行方式仅本地保存。距离、耗时和到岗计算将在地图服务接入后开放。</p></section><footer class="screen-footer">${event('保存地点', 'save-route')}</footer></div>`;
    },
  };
}
