/* Local interaction prototype. Android owns alarm registration, ringing and permissions. */
import { AMAP_DEMO_TIPS, createDefaultState, defaultAlarmDraft, loadSettings, nextAlarmOccurrence, normalizeAlarmPlan, persistSettings, REPEAT_KINDS, todayIso, validateAlarmPlan } from './state.mjs';
import { createTravelScreens } from './screens-travel.mjs';
import { createAlarmScreens } from './screens-alarm.mjs';
import { createSettingsScreens } from './screens-settings.mjs';
import { createSupportScreens } from './screens-support.mjs';

const STORAGE_KEY = 'zhitu-prototype-config-v3';
const ROUTES = ['home','weather','route','route-edit','plans','plan-edit','why','settings','lock','island','island-expand','ringing','history','failure','rest','overtime-select','overtime-active','onboarding','credentials','calendar','diagnostics','place-search'];
const HEADERS = { home:['知途','本地闹钟'], weather:['天气地图',''], route:['我的通勤','地点与出行方式'], 'route-edit':['编辑地点',''], plans:['闹钟计划','本地创建，由 Android 调度'], 'plan-edit':['编辑闹钟',''], why:['提前计算',''], settings:['通知与保障',''], history:['本机记录',''], failure:['评估状态',''], rest:['天气缓冲',''], 'overtime-select':['单日覆盖',''], 'overtime-active':['闹钟计划',''], onboarding:['开始使用知途',''], credentials:['数据与凭据',''], calendar:['工作日日历',''], diagnostics:['可靠性诊断',''], 'place-search':['选择地点',''] };
const BACK = { weather:'home', route:'home', 'route-edit':'route', 'plan-edit':'plans', why:'home', history:'plans', failure:'plans', rest:'plans', 'overtime-select':'plans', 'overtime-active':'plans', onboarding:'home', credentials:'settings', calendar:'plan-edit', diagnostics:'settings', 'place-search':'route-edit', lock:'settings', island:'settings', 'island-expand':'island', ringing:'plans' };
const NAV = [['home','今日','bea94e9a-63d6-46a2-be51-c2a550277636.svg'],['route','路线','91c986c2-5c7e-4908-a9ea-11f77f84ba30.svg'],['plans','闹钟','1ae38d70-e6a0-416f-85d1-71545f1256bf.svg'],['settings','设置','7b9895bd-a3db-41ea-b369-eb67fda8373d.svg']];
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
const clone = value => structuredClone(value);
const action = (label, route, extra = '') => `<button type="button" class="prototype-button" data-route="${route}" ${extra}>${esc(label)}</button>`;
const overlayAction = (label, name, value = '') => `<button type="button" class="prototype-button" data-action="${name}" data-value="${esc(value)}">${esc(label)}</button>`;
const asset = (file, alt = '', className = '') => `<img class="${className}" src="./assets/figma-svg/${file}" alt="${esc(alt)}">`;

function defaults() {
  return { ...createDefaultState(), origin:'', originAddress:'', destination:'', destinationAddress:'', selectedTransport:'driving', favorites:[], notificationsEnabled:true, lockSummaryEnabled:true, onboardingDone:false };
}
function load() { try { return loadSettings(localStorage, STORAGE_KEY, defaults()); } catch { return defaults(); } }
let config = load();
let runtime = { route:config.onboardingDone ? 'home' : 'onboarding', history:[], notice:'', overlay:null, credentials:{}, credentialStatus:'未配置', amapFixture:'success', calendarMonth:todayIso().slice(0, 7), selectedDate:todayIso(), alarmDraft:null, editingAlarmId:null, calendarPlanId:null, dateOverridesDraft:null, routeDraft:null, routeScope:'global', placeTarget:'origin', placeQuery:'', selectedPlace:null, historyFilter:'all', overrideDraftTime:'' };
let noticeTimer;

function persist() { try { persistSettings(localStorage, STORAGE_KEY, config); } catch { runtime.notice = '浏览器存储不可用；更改仅保留在当前会话。'; } }
function currentConfig() { return runtime.routeDraft && ['route','route-edit','place-search'].includes(runtime.route) ? runtime.routeDraft : config; }
function activeCommute() {
  if (runtime.routeScope !== 'plan') return currentConfig();
  const plan = alarmDraft();
  if (!plan.commuteOverride?.enabled) plan.commuteOverride = { enabled:true, origin:config.origin, originAddress:config.originAddress, destination:config.destination, destinationAddress:config.destinationAddress, selectedTransport:config.selectedTransport };
  return plan.commuteOverride;
}
function record(type, message, plan) { config.alarmEvents = [{ id:`event-${Date.now()}`, type, message, date:todayIso(), time:plan?.time || '', planId:plan?.id || null }, ...(config.alarmEvents || [])].slice(0, 100); }
function state() { const c = clone(currentConfig()); if (runtime.dateOverridesDraft) c.dateOverrides = clone(runtime.dateOverridesDraft); const plan = runtime.alarmDraft; return { config:c, runtime:{ ...runtime, alarmDraft: plan ? clone(plan) : null }, next: plan ? nextAlarmOccurrence(plan, { override:c.dateOverrides }) : null }; }
const travel = createTravelScreens({ action, overlayAction, asset, state });
const alarms = createAlarmScreens({ action, overlayAction, asset, state });
const settings = createSettingsScreens({ asset, state, overlayAction });
const support = createSupportScreens({ escapeHTML:esc, action, overlayAction });

function notice(message) { runtime.notice = message; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { runtime.notice = ''; render(); }, 3200); }
function closeOverlay() { runtime.overlay = null; runtime.overrideDraftTime = ''; }
function enterRouteDraft() { if (!runtime.routeDraft) runtime.routeDraft = clone(config); }
function navigate(route, { replace = false, fromHistory = false } = {}) {
  if (!ROUTES.includes(route)) return;
  const old = runtime.route;
  if (old === 'plan-edit' && !['calendar','route-edit','place-search'].includes(route)) { runtime.alarmDraft = null; runtime.editingAlarmId = null; runtime.dateOverridesDraft = null; }
  if (old === 'route-edit' && route !== 'place-search' && runtime.routeScope !== 'plan') runtime.routeDraft = null;
  if ((route === 'route-edit' || route === 'place-search') && runtime.routeScope !== 'plan') enterRouteDraft();
  if (!replace && old !== route) runtime.history.push(old);
  runtime.route = route; closeOverlay();
  if (route === 'calendar') runtime.calendarMonth = runtime.selectedDate.slice(0, 7);
  if (!fromHistory) history[replace ? 'replaceState' : 'pushState'](null, '', `#/${route}`);
  render();
}
function back() { if (runtime.overlay) { closeOverlay(); render(); return; } if (runtime.route === 'plan-edit') { navigate('plans', { replace:true }); return; } const previous = runtime.history.pop(); navigate(previous || BACK[runtime.route] || 'home', { replace:true }); }
function header() { const [title, subtitle] = HEADERS[runtime.route] || ['', '']; const primary = ['home','route','plans','settings'].includes(runtime.route); return `<header class="prototype-header ${subtitle ? '' : 'is-compact'}"><div class="header-title">${primary ? '' : '<button class="header-back" data-action="back" aria-label="返回">‹</button>'}<h1>${esc(title)}</h1></div>${subtitle ? `<p>${esc(subtitle)}</p>` : ''}</header>`; }
function status() { return `<div class="prototype-status" aria-hidden="true"><span>07:00</span>${asset('062b4121-afb6-4a70-840f-c091259fce25.svg','','camera-dot')}${asset('12a64736-c78d-42d8-9cb1-0bdc1016ef05.svg','','system-signal')}</div>`; }
function nav() { const active = runtime.route; return `<nav class="prototype-nav" aria-label="主要导航">${NAV.map(([route,label,file]) => `<button type="button" data-route="${route}" class="${active === route ? 'is-active' : ''}" ${active === route ? 'aria-current="page"' : ''}><span>${asset(file)}</span><b>${label}</b></button>`).join('')}</nav>`; }
function concept(route) { return `<section class="support-screen"><article class="support-info-card"><h2>Android 系统界面</h2><p>${route === 'ringing' ? '实际响铃、停止和贪睡由 Android 前台服务实现。' : '锁屏和系统展示效果以 Android 真机验收为准。'}</p></article></section>`; }
function extraOverlay() {
  if (runtime.overlay !== 'favorite') return '';
  const place = runtime.favoriteDraft || { name:'', address:'' };
  return `<div class="support-overlay" role="dialog" aria-modal="true" aria-label="添加地点"><section class="support-sheet"><i class="support-sheet-handle"></i><h2>添加常用地点</h2><label class="support-modal-field"><span>名称</span><input data-favorite-field="name" value="${esc(place.name)}"></label><label class="support-modal-field"><span>地址文字</span><input data-favorite-field="address" value="${esc(place.address)}"></label><p class="support-caption">仅保存在本机，不请求地图或定位。</p><div class="support-sheet-actions">${overlayAction('取消','close-overlay')}${overlayAction('保存','save-favorite')}</div></section></div>`;
}
function render() {
  const page = runtime.route; const snapshot = state();
  const body = travel[page]?.() || alarms[page]?.() || settings[page]?.() || (['lock','island','island-expand','ringing'].includes(page) ? concept(page) : support.renderRoute(page, snapshot));
  const overlay = support.renderOverlay(runtime.overlay, snapshot) || extraOverlay();
  document.getElementById('app').innerHTML = `<main class="prototype-screen" data-page="${page}">${status()}${header()}<div class="prototype-scroll">${body}</div>${['home','route','plans','settings'].includes(page) ? nav() : '<div class="prototype-gesture" aria-hidden="true"><i></i></div>'}</main>${overlay}${runtime.notice ? `<p class="prototype-notice" role="status">${esc(runtime.notice)}</p>` : ''}`;
  for (const footer of document.querySelectorAll('.prototype-scroll .screen-footer')) document.querySelector('.prototype-screen').insertBefore(footer, document.querySelector('.prototype-nav,.prototype-gesture'));
  document.getElementById('scenario-select').value = page;
  document.dispatchEvent(new CustomEvent('zhitu:routechange', { detail:{ route:page } }));
}

function openOverlay(value) { runtime.overlay = value; if (value === 'override-time') runtime.overrideDraftTime = ''; render(); }
function alarmDraft() { if (!runtime.alarmDraft) throw new Error('请先选择一个闹钟。'); return runtime.alarmDraft; }
function saveAlarm() {
  const plan = validateAlarmPlan({ ...alarmDraft(), updatedAt:new Date().toISOString(), scheduleStatus:alarmDraft().enabled ? 'pendingPermission' : 'completed' });
  const existing = config.alarmPlans.findIndex(item => item.id === plan.id);
  if (existing >= 0) config.alarmPlans.splice(existing, 1, plan); else config.alarmPlans.push(plan);
  if (runtime.dateOverridesDraft) config.dateOverrides = runtime.dateOverridesDraft;
  record(plan.enabled ? 'registered' : 'stopped', plan.enabled ? `已保存“${plan.name}”，待 Android 注册` : `已停用“${plan.name}”`, plan);
  persist(); runtime.alarmDraft = null; runtime.editingAlarmId = null; runtime.dateOverridesDraft = null; notice('闹钟已保存；Android 应用将负责实际注册与响铃。'); navigate('plans', { replace:true });
}
function toggleAlarm(id, enabled) {
  const index = config.alarmPlans.findIndex(plan => plan.id === id); if (index < 0) return;
  const candidate = { ...config.alarmPlans[index], enabled, scheduleStatus:enabled ? 'pendingPermission' : 'completed', updatedAt:new Date().toISOString() };
  if (enabled) validateAlarmPlan(candidate);
  config.alarmPlans.splice(index, 1, candidate); record(enabled ? 'registered' : 'stopped', enabled ? `请求启用“${candidate.name}”，待 Android 注册` : `已停用“${candidate.name}”`, candidate); persist(); render();
}
function changeMonth(delta) { const [year, month] = runtime.calendarMonth.split('-').map(Number); runtime.calendarMonth = todayIso(new Date(year, month - 1 + delta, 1)); render(); }
function handleClick(event) {
  const target = event.target.closest('[data-action],[data-route]'); if (!target || target.disabled) return;
  if (target.dataset.route) { navigate(target.dataset.route); return; }
  const op = target.dataset.action; const value = target.dataset.value || '';
  try {
    if (op === 'back') return back();
    if (op === 'close-overlay') { closeOverlay(); render(); return; }
    if (op === 'overlay') return openOverlay(value);
    if (op === 'new-alarm') { runtime.alarmDraft = defaultAlarmDraft(); runtime.editingAlarmId = null; return navigate('plan-edit'); }
    if (op === 'edit-alarm') { const plan = config.alarmPlans.find(item => item.id === value); if (!plan) throw Error('闹钟不存在。'); runtime.alarmDraft = clone(plan); runtime.editingAlarmId = value; return navigate('plan-edit'); }
    if (op === 'save-alarm') return saveAlarm();
    if (op === 'delete-alarm') { config.alarmPlans = config.alarmPlans.filter(plan => plan.id !== value); record('stopped', '已删除闹钟'); persist(); runtime.alarmDraft = null; notice('闹钟已删除。'); return navigate('plans', { replace:true }); }
    if (op === 'toggle-alarm') return toggleAlarm(value, target.querySelector('input')?.checked ?? !config.alarmPlans.find(plan => plan.id === value)?.enabled);
    if (op === 'select-repeat') { const plan = alarmDraft(); plan.repeat = value === REPEAT_KINDS.ONCE ? { kind:value, date:todayIso() } : value === REPEAT_KINDS.WEEKLY ? { kind:value, weekdays:[1,2,3,4,5] } : { kind:value }; render(); return; }
    if (op === 'toggle-weekday') { const plan = alarmDraft(); const day = Number(value); const days = new Set(plan.repeat.weekdays || []); days.has(day) ? days.delete(day) : days.add(day); plan.repeat.weekdays = [...days].sort(); render(); return; }
    if (op === 'save-overlay-time' || op === 'save-overlay-snooze' || op === 'save-overlay-sound') { closeOverlay(); render(); return; }
    if (op === 'open-calendar') { runtime.calendarPlanId = alarmDraft().id; runtime.dateOverridesDraft = clone(config.dateOverrides || {}); return navigate('calendar'); }
    if (op === 'calendar-previous') return changeMonth(-1);
    if (op === 'calendar-next') return changeMonth(1);
    if (op === 'select-calendar-date') { runtime.selectedDate = value; render(); return; }
    if (op === 'set-date-override') { const plan = config.alarmPlans.find(item => item.id === runtime.calendarPlanId) || alarmDraft(); const key = `${plan.id}:${runtime.selectedDate}`; const overrides = runtime.dateOverridesDraft || (runtime.dateOverridesDraft = clone(config.dateOverrides || {})); if (value === 'inherit') delete overrides[key]; else overrides[key] = value === 'off' ? { enabled:false } : { enabled:true }; render(); return; }
    if (op === 'save-override-time') { const plan = config.alarmPlans.find(item => item.id === runtime.calendarPlanId) || alarmDraft(); const key = `${plan.id}:${runtime.selectedDate}`; const overrides = runtime.dateOverridesDraft || (runtime.dateOverridesDraft = clone(config.dateOverrides || {})); overrides[key] = { ...(overrides[key] || { enabled:true }), time:runtime.overrideDraftTime || plan.time }; closeOverlay(); render(); return; }
    if (op === 'save-calendar') return navigate('plan-edit', { replace:true });
    if (op === 'history-filter') { runtime.historyFilter = value; closeOverlay(); render(); return; }
    if (op === 'save-route') { if (runtime.routeScope === 'plan') { runtime.routeScope = 'global'; notice('本计划通勤覆盖已保存。'); return navigate('plan-edit', { replace:true }); } config = clone(runtime.routeDraft || config); runtime.routeDraft = null; persist(); notice('全局通勤已保存。'); return navigate('route', { replace:true }); }
    if (op === 'mode') { activeCommute().selectedTransport = value; render(); return; }
    if (op === 'open-place') { runtime.placeTarget = value; runtime.selectedPlace = null; return navigate('place-search'); }
    if (op === 'choose-place') { runtime.selectedPlace = [...AMAP_DEMO_TIPS, ...(currentConfig().favorites || [])].find(place => place.id === value) || null; render(); return; }
    if (op === 'use-place') { const place = runtime.selectedPlace; if (!place) throw Error('请先选择一个地点。'); const c = activeCommute(); c[runtime.placeTarget] = place.name; c[`${runtime.placeTarget}Address`] = place.address; return navigate('route-edit', { replace:true }); }
    if (op === 'add-favorite') { runtime.favoriteDraft = { id:`place-${Date.now()}`, name:'', address:'', description:'本机文字地点' }; runtime.overlay = 'favorite'; render(); return; }
    if (op === 'save-favorite') { const place = runtime.favoriteDraft; if (!place?.name?.trim() || !place?.address?.trim()) throw Error('请填写名称和地址文字。'); currentConfig().favorites.push(clone(place)); runtime.favoriteDraft = null; closeOverlay(); render(); return; }
    if (op === 'amap-consent') { config.amapConsent = value === 'approved' ? 'approved' : 'basic'; config.onboardingDone = true; persist(); notice(value === 'approved' ? '已同意高德授权；可配置运行时 Key。' : '仅使用基础功能；高德地图保持未初始化。'); return navigate(value === 'approved' ? 'credentials' : 'home', { replace:true }); }
    if (op === 'edit-plan-commute') { const plan = alarmDraft(); plan.commuteOverride = { enabled:true, origin:config.origin, originAddress:config.originAddress, destination:config.destination, destinationAddress:config.destinationAddress, selectedTransport:config.selectedTransport }; runtime.routeScope = 'plan'; return navigate('route-edit'); }
    if (op === 'use-global-commute') { alarmDraft().commuteOverride = { enabled:false }; render(); return; }
    if (op === 'pick-map') { if (config.amapConsent !== 'approved') throw Error('请先在首次启动页同意高德授权。'); if (!runtime.credentials.amapSdkKey) throw Error('请先配置运行时 Android SDK Key。'); const c = activeCommute(); c[runtime.placeTarget] = '地图选点（演示）'; c[`${runtime.placeTarget}Address`] = '离线 fixture · 不含坐标'; notice('已应用地图选点 fixture。'); render(); return; }
    if (op === 'locate-once') { if (config.amapConsent !== 'approved') throw Error('请先在首次启动页同意高德授权。'); if (!runtime.credentials.amapSdkKey) throw Error('请先配置运行时 Android SDK Key。'); if (runtime.amapFixture === 'denied') throw Error('定位权限被拒绝；可改用搜索或地图选点。'); const place = { id:'demo-current-location', name:'当前位置（演示）', address:'仅本次定位 fixture · 不含坐标' }; if (runtime.route === 'place-search') { runtime.selectedPlace = place; notice('已获取一次性定位 fixture。'); render(); return; } const c = activeCommute(); c[runtime.placeTarget] = place.name; c[`${runtime.placeTarget}Address`] = place.address; notice('已应用一次性定位 fixture。'); render(); return; }
    if (op === 'save-credentials') { runtime.credentialStatus = '运行时凭据已保存 · 仅当前页面会话'; render(); return; }
    if (op === 'test-credentials') { runtime.credentialStatus = '高德离线 fixture 已验证；未发送网络请求'; render(); return; }
    if (op === 'clear-credentials') return openOverlay('clear-credentials');
    if (op === 'confirm-clear-credentials') { runtime.credentials = {}; runtime.credentialStatus = '运行时凭据已清空'; closeOverlay(); render(); return; }
    if (op === 'preview-sound') { notice('浏览器原型不播放声音；Android 应用可试听。'); return; }
    if (op === 'recheck-diagnostics') { notice('浏览器原型不读取系统状态，请在 Android 应用中检查。'); return; }
  } catch (error) { notice(error.message); render(); }
}
function handleInput(event) {
  const target = event.target;
  if (target.dataset.credential) { runtime.credentials[target.dataset.credential] = target.value; return; }
  if (target.dataset.alarmField) { const plan = alarmDraft(); const field = target.dataset.alarmField; if (field === 'date') plan.repeat.date = target.value; else plan[field] = target.value; return; }
  if (target.dataset.overlayField) { const plan = alarmDraft(); const field = target.dataset.overlayField; if (field === 'overrideTime') runtime.overrideDraftTime = target.value; else if (field === 'vibration') plan.vibration = target.checked; else plan[field] = field === 'snoozeMinutes' ? Number(target.value) : target.value; return; }
  if (target.dataset.favoriteField) { runtime.favoriteDraft[target.dataset.favoriteField] = target.value; return; }
  if (target.dataset.field === 'placeQuery') { runtime.placeQuery = target.value; render(); return; }
}
function handleChange(event) {
  const target = event.target;
  if (target.dataset.action === 'toggle-alarm') return toggleAlarm(target.dataset.value, target.checked);
  if (target.dataset.setting) { config[target.dataset.setting] = target.checked; persist(); render(); }
  if (target.dataset.amapFixture) { runtime.amapFixture = target.value; render(); }
  if (target.dataset.overlayField === 'vibration') handleInput(event);
}
function reset() { config = defaults(); persist(); runtime = { ...runtime, route:'onboarding', history:[], overlay:null, alarmDraft:null, editingAlarmId:null, calendarPlanId:null, dateOverridesDraft:null, routeDraft:null, routeScope:'global', credentials:{}, credentialStatus:'未配置', amapFixture:'success', selectedDate:todayIso(), calendarMonth:todayIso().slice(0, 7) }; notice('本地演示数据已重置。'); navigate('onboarding', { replace:true }); }
document.addEventListener('click', handleClick);
document.addEventListener('input', handleInput);
document.addEventListener('change', handleChange);
document.addEventListener('keydown', event => { if (event.key === 'Escape' && runtime.overlay) { closeOverlay(); render(); } });
document.getElementById('scenario-select').addEventListener('change', event => navigate(event.target.value));
document.getElementById('scenario-reset').addEventListener('click', reset);
window.addEventListener('popstate', () => navigate(location.hash.replace(/^#\/?/, ''), { replace:true, fromHistory:true }));
function fitPhone() { document.documentElement.style.setProperty('--phone-scale', String(Math.min(1, (window.innerWidth - 24) / 412))); }
window.addEventListener('resize', fitPhone); fitPhone();
window.ZhituPrototype = { ROUTES, navigate, reset };
navigate(config.onboardingDone && ROUTES.includes(location.hash.replace(/^#\/?/, '')) ? location.hash.replace(/^#\/?/, '') : (config.onboardingDone ? 'home' : 'onboarding'), { replace:true });
document.fonts.ready.then(() => { document.getElementById('render-status').textContent = '412 × 892 · 本地设计字体已加载'; });
