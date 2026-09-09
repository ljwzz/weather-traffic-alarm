/* Local interaction prototype. Android owns alarm registration, ringing and permissions. */
import { AMAP_DEMO_TIPS, CAIYUN_FIXTURE_STATES, EVALUATION_FIXTURE_STATES, HOME_PREVIEW_REFRESH_WINDOWS, HOME_PREVIEW_STATES, applyHomePreviewResponse, createDefaultState, createEvaluationFixture, createHomePreviewState, decisionRecordFromEvaluation, defaultAlarmDraft, evaluationFixtureHistory, homePreviewInputSignature, homePreviewIsFresh, homePreviewPrerequisite, loadSettings, nextAlarmOccurrence, persistSettings, REPEAT_KINDS, routePreviewResult, saveWeatherBufferProfile, todayIso, validateAlarmPlan, weatherPreviewResult } from './state.mjs';
import { createTravelScreens } from './screens-travel.mjs';
import { createAlarmScreens } from './screens-alarm.mjs';
import { createSettingsScreens } from './screens-settings.mjs';
import { createSupportScreens } from './screens-support.mjs';
import { createSystemScreens } from './screens-system.mjs';
import { createRingingSession, RINGING_KINDS, ringSnoozedSession, snoozeRingingSession, stopRingingSession } from './ringing-state.mjs';
import { canUseLocation, createPermissionState, missingAlarmDisplayPermissions } from './permission-state.mjs';
import { createPermissionScreens } from './screens-permissions.mjs';

const STORAGE_KEY = 'zhitu-prototype-config-v3';
const ROUTES = ['home','weather','route','route-edit','plans','plan-edit','why','settings','lock','island','island-expand','ringing','ringing-basic','history','failure','rest','overtime-select','overtime-active','onboarding','credentials','calendar','diagnostics','place-search'];
const HEADERS = { home:['知途','本地闹钟'], weather:['天气地图',''], route:['我的通勤','地点与出行方式'], 'route-edit':['编辑地点',''], plans:['闹钟计划','本地创建，由 Android 调度'], 'plan-edit':['编辑闹钟',''], why:['本次决策',''], settings:['设置','闹钟与通勤'], history:['决策与本机记录',''], failure:['评估详情',''], rest:['天气缓冲',''], 'overtime-select':['单日覆盖',''], 'overtime-active':['闹钟计划',''], onboarding:['开始使用知途',''], credentials:['数据与凭据',''], calendar:['工作日日历',''], diagnostics:['可靠性诊断',''], 'place-search':['选择地点',''] };
const BACK = { weather:'home', route:'home', 'route-edit':'route', 'plan-edit':'plans', why:'home', history:'plans', failure:'plans', rest:'plans', 'overtime-select':'plans', 'overtime-active':'plans', onboarding:'home', credentials:'settings', calendar:'plan-edit', diagnostics:'settings', 'place-search':'route-edit', lock:'settings', island:'settings', 'island-expand':'island', ringing:'plans', 'ringing-basic':'plans' };
const NAV = [['home','今日','bea94e9a-63d6-46a2-be51-c2a550277636.svg'],['route','路线','91c986c2-5c7e-4908-a9ea-11f77f84ba30.svg'],['plans','闹钟','1ae38d70-e6a0-416f-85d1-71545f1256bf.svg'],['settings','设置','7b9895bd-a3db-41ea-b369-eb67fda8373d.svg']];
const RINGING_ROUTES = new Set(['ringing', 'ringing-basic']);
function createRingingFixtureSession(kind) {
  const session = createRingingSession(kind);
  if (kind === RINGING_KINDS.EARLY) session.occurrence.decisionId = 'decision-fixture-work-advanced';
  return session;
}
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
const clone = value => structuredClone(value);
const action = (label, route, extra = '') => `<button type="button" class="prototype-button" data-route="${route}" ${extra}>${esc(label)}</button>`;
const overlayAction = (label, name, value = '') => `<button type="button" class="prototype-button" data-action="${name}" data-value="${esc(value)}">${esc(label)}</button>`;
const asset = (file, alt = '', className = '') => `<img class="${className}" src="./assets/figma-svg/${file}" alt="${esc(alt)}">`;

function defaults() {
  return { ...createDefaultState(), origin:'', originAddress:'', destination:'', destinationAddress:'', selectedTransport:'driving', favorites:[], onboardingDone:false };
}
function demoDecisionRecords() {
  const plan = { id:'fixture-work', name:'上班闹钟（fixture）', time:'07:30', preparationMinutes:25, estimatedDepartureTime:'08:03', revision:'fixture-r1' };
  return evaluationFixtureHistory(plan).map(item => item.state === EVALUATION_FIXTURE_STATES.ADVANCED
    ? { ...item, title:'提前 12 分钟', inputs:{ ...item.inputs, preparationMinutes:25, estimatedDepartureTime:'08:03' }, schedule:{ ...item.schedule, earlyWake:'07:18', actualWake:'07:18' }, occurrence:{ id:'early:2026-09-02T07:18:00#0', planId:'fixture-work', planRevision:'fixture-r1', kind:'ADVANCE', state:'RINGING', parentId:null } }
    : item);
}
function load() { try { return loadSettings(localStorage, STORAGE_KEY, defaults()); } catch { return defaults(); } }
let config = load();
function createRuntime() {
  const decisionRecords = demoDecisionRecords();
  return { route:config.onboardingDone ? 'home' : 'onboarding', history:[], notice:'', overlay:null, credentials:{}, credentialStatus:'未验证', amapFixture:'success', caiyunFixture:'success', routeFixture:'success', homeConfigurationState:'ready', weatherCredentialConfigured:false, caiyunConnectionState:'pending', weatherForecastWindowValid:true, weatherObservedAt:'09-05 07:00', fixtureNow:null, amapCredentialRevision:0, weatherCredentialRevision:0, homePreview:createHomePreviewState(), evaluationFixture:EVALUATION_FIXTURE_STATES.PENDING, evaluationRun:null, evaluationSubmitting:false, decisionRecords, selectedDecisionId:null, selectedOccurrenceId:null, selectedEvaluationPlanId:null, calendarMonth:todayIso().slice(0, 7), selectedDate:todayIso(), selectedRouteIndex:0, alarmDraft:null, editingAlarmId:null, commuteSettingsExpanded:false, weatherBufferExpanded:false, weatherBufferDraft:null, calendarPlanId:null, dateOverridesDraft:null, routeDraft:null, routeScope:'global', placeTarget:'origin', placeQuery:'', selectedPlace:null, historyFilter:'all', overrideDraftTime:'', ringingSession:null, ringingDetailOpen:false, diagnosticFixture:'records', permissionState:createPermissionState(), permissionFlow:null, permissionPrompted:[], permissionSettingsTarget:null, locationRequest:null };
}
let runtime = createRuntime();
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
function evaluationPlans() {
  const plans = (config.alarmPlans || []).filter(plan => plan.enabled);
  return plans.length ? plans : [{ id:'fixture-work', name:'上班闹钟（fixture）', time:'07:30', enabled:true }];
}
function selectedEvaluationPlan() {
  const plans = evaluationPlans();
  return plans.find(plan => plan.id === runtime.selectedEvaluationPlanId) || plans[0];
}
function evaluationRun(fixture = runtime.evaluationFixture) {
  return createEvaluationFixture({
    fixture,
    plan:selectedEvaluationPlan(),
    transport:config.selectedTransport,
    selectedRouteIndex:runtime.selectedRouteIndex,
    weatherBuffers:config.weatherBuffers,
  });
}
function appendDecision(run, plan = selectedEvaluationPlan()) {
  const decisionId = `decision-${plan.id}-${run.fixture}-${Date.now()}`;
  const record = decisionRecordFromEvaluation(run, plan, { decisionId });
  runtime.decisionRecords = [record, ...(runtime.decisionRecords || [])];
  return record;
}
function openDecision(decisionId) {
  const item = decisionRecord(decisionId);
  const route = [EVALUATION_FIXTURE_STATES.RETRY, EVALUATION_FIXTURE_STATES.REGISTRATION_FAILED, EVALUATION_FIXTURE_STATES.INSUFFICIENT_ADVANCE, EVALUATION_FIXTURE_STATES.DEADLINE, EVALUATION_FIXTURE_STATES.SKIPPED, EVALUATION_FIXTURE_STATES.EXPIRED].includes(item?.state) ? 'failure' : 'why';
  return navigate(item ? `${route}?decisionId=${encodeURIComponent(decisionId)}` : route);
}
function invalidateHomePreviews() {
  for (const kind of ['weather', 'route']) {
    const preview = runtime.homePreview[kind];
    runtime.homePreview[kind] = { ...preview, generation:preview.generation + 1, inputSignature:'', result:null, state:HOME_PREVIEW_STATES.CONFIG_LOADING, refreshing:false };
  }
}
function syncHomePreviewPrerequisites(kind) {
  const current = runtime.homePreview[kind];
  const prerequisite = homePreviewPrerequisite(kind, config, runtime);
  if (!prerequisite) return;
  runtime.homePreview[kind] = {
    ...current,
    generation:current.generation + 1,
    inputSignature:homePreviewInputSignature(config, runtime),
    result:null,
    state:prerequisite,
    refreshing:false,
  };
}
function homeResponseState(kind, fixture) {
  if (kind === 'weather') {
    if (runtime.weatherForecastWindowValid === false) return HOME_PREVIEW_STATES.EMPTY;
    if (fixture === CAIYUN_FIXTURE_STATES.CACHED) return HOME_PREVIEW_STATES.CACHED;
    if (fixture === CAIYUN_FIXTURE_STATES.ERROR) return HOME_PREVIEW_STATES.ERROR;
    if (fixture === CAIYUN_FIXTURE_STATES.LOADING) return HOME_PREVIEW_STATES.LOADING;
    return HOME_PREVIEW_STATES.SUCCESS;
  }
  if (fixture === 'empty') return HOME_PREVIEW_STATES.EMPTY;
  if (fixture === 'error') return HOME_PREVIEW_STATES.ERROR;
  if (fixture === 'loading') return HOME_PREVIEW_STATES.LOADING;
  return HOME_PREVIEW_STATES.SUCCESS;
}
function refreshHomePreview({ force = false, kind = null } = {}) {
  const kinds = kind ? [kind] : ['weather', 'route'];
  const signature = homePreviewInputSignature(config, runtime);
  for (const name of kinds) {
    const current = runtime.homePreview[name];
    if (current.refreshing) continue;
    const prerequisite = homePreviewPrerequisite(name, config, runtime);
    if (prerequisite) {
      runtime.homePreview[name] = { ...current, generation:current.generation + 1, inputSignature:signature, result:null, state:prerequisite, refreshing:false };
      continue;
    }
    const windowMs = name === 'weather' ? HOME_PREVIEW_REFRESH_WINDOWS.weatherMs : HOME_PREVIEW_REFRESH_WINDOWS.routeMs;
    const now = runtime.fixtureNow || Date.now();
    if (!force && homePreviewIsFresh(current, signature, windowMs, now)) continue;
    const generation = current.generation + 1;
    const previous = homePreviewIsFresh(current, signature, windowMs, now) ? current.result : null;
    runtime.homePreview[name] = { ...current, generation, inputSignature:signature, result:previous, state:HOME_PREVIEW_STATES.LOADING, refreshing:true };
    const fixture = name === 'weather' ? runtime.caiyunFixture : runtime.routeFixture;
    setTimeout(() => {
      const responseState = homeResponseState(name, fixture);
      if (responseState === HOME_PREVIEW_STATES.LOADING) return;
      const result = responseState === HOME_PREVIEW_STATES.SUCCESS || responseState === HOME_PREVIEW_STATES.CACHED
        ? (name === 'weather' ? weatherPreviewResult(config, runtime) : routePreviewResult(config, runtime))
        : null;
      runtime.homePreview[name] = applyHomePreviewResponse(runtime.homePreview[name], { generation, inputSignature:signature, state:responseState, result, updatedAt:now });
      render();
    }, 160);
  }
}
function decisionRecord(decisionId = runtime.selectedDecisionId) { return (runtime.decisionRecords || []).find(item => item.decisionId === decisionId) || null; }
function state() { const c = clone(currentConfig()); if (runtime.dateOverridesDraft) c.dateOverrides = clone(runtime.dateOverridesDraft); const plan = runtime.alarmDraft; const evaluationPlan = selectedEvaluationPlan(); return { config:c, runtime:{ ...runtime, alarmDraft: plan ? clone(plan) : null, evaluationPlan:clone(evaluationPlan), evaluationPlans:clone(evaluationPlans()), evaluationRun:runtime.evaluationRun ? clone(runtime.evaluationRun) : null, evaluationHistory:clone(runtime.decisionRecords || []), selectedDecision:clone(decisionRecord()) }, next: plan ? nextAlarmOccurrence(plan, { override:c.dateOverrides }) : null }; }
const travel = createTravelScreens({ action, overlayAction, asset, state });
const alarms = createAlarmScreens({ action, overlayAction, asset, state });
const settings = createSettingsScreens({ asset, state, overlayAction });
const support = createSupportScreens({ escapeHTML:esc, action, overlayAction });
const system = createSystemScreens({ asset, state });
const permissions = createPermissionScreens({ state, overlayAction });

function notice(message) { runtime.notice = message; clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { runtime.notice = ''; render(); }, 3200); }
function closeOverlay() { runtime.overlay = null; runtime.overrideDraftTime = ''; }
function enterRouteDraft() { if (!runtime.routeDraft) runtime.routeDraft = clone(config); }
function routeName(target) { return String(target || '').split('?')[0]; }
function navigate(target, { replace = false, fromHistory = false } = {}) {
  const [route, query = ''] = String(target || '').split('?');
  if (!ROUTES.includes(route)) return;
  const params = new URLSearchParams(query);
  if (route === 'why' || route === 'failure') {
    const decisionId = params.get('decisionId');
    runtime.selectedDecisionId = decisionId && runtime.decisionRecords.some(item => item.decisionId === decisionId) ? decisionId : null;
    runtime.selectedOccurrenceId = params.get('occurrenceId') || null;
  }
  const old = runtime.route;
  const preservesPermissionFlow = old === 'plan-edit' && route === 'diagnostics' && runtime.permissionFlow;
  const resumesPermissionFlow = old === 'diagnostics' && route === runtime.permissionFlow?.originRoute;
  const leavesPermissionFlow = runtime.permissionFlow && !['diagnostics', runtime.permissionFlow.originRoute].includes(route);
  if (leavesPermissionFlow) runtime.permissionFlow = null;
  if (runtime.locationRequest && route !== runtime.locationRequest.route) runtime.locationRequest = null;
  if (old === 'plan-edit' && !['calendar','route-edit','place-search'].includes(route) && !preservesPermissionFlow) { runtime.alarmDraft = null; runtime.editingAlarmId = null; runtime.dateOverridesDraft = null; }
  if (old === 'route-edit' && route !== 'place-search' && runtime.routeScope !== 'plan') runtime.routeDraft = null;
  if (old === 'route-edit' && route === 'plan-edit' && runtime.routeScope === 'plan') { runtime.routeScope = 'global'; runtime.routeDraft = null; }
  if ((route === 'route-edit' || route === 'place-search') && runtime.routeScope !== 'plan') enterRouteDraft();
  const preservesRingingSession = old === 'ringing' && route === 'why' && runtime.ringingDetailOpen;
  const returnsToRingingSession = old === 'why' && (route === 'ringing' || route === 'ringing-basic') && runtime.ringingDetailOpen;
  if (route === 'ringing' || route === 'ringing-basic') {
    const kind = route === 'ringing-basic' ? RINGING_KINDS.BASIC : RINGING_KINDS.EARLY;
    if (old !== route || !runtime.ringingSession) {
      if (!runtime.ringingSession) runtime.ringingSession = createRingingFixtureSession(kind);
    }
    if (returnsToRingingSession) runtime.ringingDetailOpen = false;
  } else if ((old === 'ringing' || old === 'ringing-basic') && !preservesRingingSession) runtime.ringingSession = null;
  else if (runtime.ringingDetailOpen && old === 'why' && route !== 'why') { runtime.ringingSession = null; runtime.ringingDetailOpen = false; }
  if (!replace && old !== route) runtime.history.push(old);
  runtime.route = route; closeOverlay();
  if (resumesPermissionFlow) runtime.overlay = 'permission-guide';
  if (route === 'calendar') runtime.calendarMonth = runtime.selectedDate.slice(0, 7);
  if (route === 'home') refreshHomePreview();
  if (route === 'weather') syncHomePreviewPrerequisites('weather');
  if (route === 'route') syncHomePreviewPrerequisites('route');
  if (!fromHistory) history[replace ? 'replaceState' : 'pushState'](null, '', `#/${route}${query ? `?${query}` : ''}`);
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
  const systemBody = RINGING_ROUTES.has(page) ? system[page]?.() : '';
  const body = systemBody || travel[page]?.() || alarms[page]?.() || settings[page]?.() || (page === 'diagnostics' ? permissions.diagnostics() : (['lock','island','island-expand'].includes(page) ? concept(page) : support.renderRoute(page, snapshot)));
  const overlay = permissions.renderOverlay(runtime.overlay) || support.renderOverlay(runtime.overlay, snapshot) || extraOverlay();
  document.getElementById('app').innerHTML = systemBody
    ? `${body}${overlay}${runtime.notice ? `<p class="prototype-notice" role="status">${esc(runtime.notice)}</p>` : ''}`
    : `<main class="prototype-screen" data-page="${page}">${status()}${header()}<div class="prototype-scroll">${body}</div>${['home','route','plans','settings'].includes(page) ? nav() : '<div class="prototype-gesture" aria-hidden="true"><i></i></div>'}</main>${overlay}${runtime.notice ? `<p class="prototype-notice" role="status">${esc(runtime.notice)}</p>` : ''}`;
  if (!systemBody) for (const footer of document.querySelectorAll('.prototype-scroll .screen-footer')) document.querySelector('.prototype-screen').insertBefore(footer, document.querySelector('.prototype-nav,.prototype-gesture'));
  document.getElementById('scenario-select').value = page;
  const deviceControl = document.getElementById('permission-device-select');
  const entryControl = document.getElementById('permission-entry-select');
  if (deviceControl) deviceControl.value = runtime.permissionState.device;
  if (entryControl) entryControl.value = runtime.permissionState.settingsEntry;
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
function permissionSignature() {
  const state = runtime.permissionState;
  return JSON.stringify({ device:state.device, standard:state.standard, xiaomi:state.xiaomi });
}
function startPermissionFlow(operation, planId = null) {
  const missing = missingAlarmDisplayPermissions(runtime.permissionState);
  const signature = permissionSignature();
  if (!missing.length || runtime.permissionPrompted.includes(signature)) return false;
  runtime.permissionFlow = { operation, planId, originRoute:runtime.route, signature };
  runtime.overlay = 'permission-guide';
  render();
  return true;
}
function requestSaveAlarm() {
  validateAlarmPlan({ ...alarmDraft(), updatedAt:new Date().toISOString(), scheduleStatus:alarmDraft().enabled ? 'pendingPermission' : 'completed' });
  if (!alarmDraft().enabled || !startPermissionFlow('save')) saveAlarm();
}
function toggleAlarm(id, enabled) {
  const index = config.alarmPlans.findIndex(plan => plan.id === id); if (index < 0) return;
  const candidate = { ...config.alarmPlans[index], enabled, scheduleStatus:enabled ? 'pendingPermission' : 'completed', updatedAt:new Date().toISOString() };
  if (enabled) validateAlarmPlan(candidate);
  config.alarmPlans.splice(index, 1, candidate); record(enabled ? 'registered' : 'stopped', enabled ? `请求启用“${candidate.name}”，待 Android 注册` : `已停用“${candidate.name}”`, candidate); persist(); render();
}
function requestToggleAlarm(id, enabled) {
  const candidate = config.alarmPlans.find(plan => plan.id === id);
  if (!candidate) return;
  if (enabled) validateAlarmPlan({ ...candidate, enabled:true });
  if (!enabled || !startPermissionFlow('toggle', id)) toggleAlarm(id, enabled);
}
function applyCurrentLocation() {
  if (runtime.amapFixture === 'denied') throw Error('定位权限被拒绝；可改用搜索或地图选点。');
  const place = { id:'demo-current-location', name:'当前位置（演示）', address:'仅本次定位 fixture · 不含坐标' };
  runtime.locationRequest = null;
  if (runtime.route === 'place-search') { runtime.selectedPlace = place; notice('已获取一次性定位 fixture。'); render(); return; }
  const c = activeCommute(); c[runtime.placeTarget] = place.name; c[`${runtime.placeTarget}Address`] = place.address; notice('已应用一次性定位 fixture。'); render();
}
function requestCurrentLocation() {
  if (config.amapConsent !== 'approved') throw Error('请先在首次启动页同意高德授权。');
  if (!runtime.credentials.amapSdkKey) throw Error('请先配置运行时 Android SDK Key。');
  runtime.locationRequest = { route:runtime.route, placeTarget:runtime.placeTarget };
  const location = runtime.permissionState.location;
  if (location.services === 'off') return openOverlay('location-unavailable');
  if (!canUseLocation(location)) return openOverlay('location-request');
  applyCurrentLocation();
}
function changeMonth(delta) { const [year, month] = runtime.calendarMonth.split('-').map(Number); runtime.calendarMonth = todayIso(new Date(year, month - 1 + delta, 1)); render(); }
function handleClick(event) {
  const target = event.target.closest('[data-action],[data-route]'); if (!target || target.disabled) return;
  if (target.dataset.route) { navigate(target.dataset.route); return; }
  const op = target.dataset.action; const value = target.dataset.value || '';
  try {
    if (op === 'back') return back();
    if (op === 'ringing-stop') { runtime.ringingSession = stopRingingSession(runtime.ringingSession); render(); return; }
    if (op === 'ringing-snooze') { runtime.ringingSession = snoozeRingingSession(runtime.ringingSession); render(); return; }
    if (op === 'ringing-again') { runtime.ringingSession = ringSnoozedSession(runtime.ringingSession); render(); return; }
    if (op === 'ringing-replay') { const kind = runtime.route === 'ringing-basic' ? RINGING_KINDS.BASIC : RINGING_KINDS.EARLY; runtime.ringingSession = createRingingFixtureSession(kind); render(); return; }
    if (op === 'ringing-return') return navigate('plans');
    if (op === 'ringing-view-reason') {
      const occurrence = runtime.ringingSession?.occurrence;
      const rootOccurrenceId = occurrence?.rootOccurrenceId || occurrence?.id;
      const item = (runtime.decisionRecords || []).find(record => record.occurrence?.id === rootOccurrenceId && record.decisionId === occurrence?.decisionId);
      runtime.ringingDetailOpen = true;
      return navigate(item ? `why?decisionId=${encodeURIComponent(item.decisionId)}&occurrenceId=${encodeURIComponent(occurrence?.id || '')}` : 'why');
    }
    if (op === 'close-overlay') { closeOverlay(); render(); return; }
    if (op === 'overlay') return openOverlay(value);
    if (op === 'new-alarm') { runtime.alarmDraft = defaultAlarmDraft(); runtime.editingAlarmId = null; runtime.commuteSettingsExpanded = false; return navigate('plan-edit'); }
    if (op === 'edit-alarm') { const plan = config.alarmPlans.find(item => item.id === value); if (!plan) throw Error('闹钟不存在。'); runtime.alarmDraft = clone(plan); runtime.editingAlarmId = value; runtime.commuteSettingsExpanded = false; return navigate('plan-edit'); }
    if (op === 'save-alarm') return requestSaveAlarm();
    if (op === 'delete-alarm') { config.alarmPlans = config.alarmPlans.filter(plan => plan.id !== value); record('stopped', '已删除闹钟'); persist(); runtime.alarmDraft = null; notice('闹钟已删除。'); return navigate('plans', { replace:true }); }
    if (op === 'toggle-alarm') return;
    if (op === 'select-repeat') { const plan = alarmDraft(); plan.repeat = value === REPEAT_KINDS.ONCE ? { kind:value, date:todayIso() } : value === REPEAT_KINDS.WEEKLY ? { kind:value, weekdays:[1,2,3,4,5] } : { kind:value }; render(); return; }
    if (op === 'toggle-commute-settings') { runtime.commuteSettingsExpanded = !runtime.commuteSettingsExpanded; render(); return; }
    if (op === 'toggle-weather-buffers') { runtime.weatherBufferExpanded = !runtime.weatherBufferExpanded; if (runtime.weatherBufferExpanded && !runtime.weatherBufferDraft) runtime.weatherBufferDraft = clone(config.weatherBuffers); render(); return; }
    if (op === 'save-weather-buffer') { const profile = runtime.weatherBufferDraft?.[value]; if (!profile) throw Error('天气缓冲草稿不存在。'); config = saveWeatherBufferProfile(config, value, profile); runtime.weatherBufferDraft = clone(config.weatherBuffers); persist(); notice('天气缓冲已保存。'); render(); return; }
    if (op === 'toggle-weekday') { const plan = alarmDraft(); const day = Number(value); const days = new Set(plan.repeat.weekdays || []); days.has(day) ? days.delete(day) : days.add(day); plan.repeat.weekdays = [...days].sort(); render(); return; }
    if (['save-overlay-time','save-overlay-snooze','save-overlay-arrival','save-overlay-preparation','save-overlay-max-advance','save-overlay-sound'].includes(op)) { closeOverlay(); render(); return; }
    if (op === 'open-calendar') { runtime.calendarPlanId = alarmDraft().id; runtime.dateOverridesDraft = clone(config.dateOverrides || {}); return navigate('calendar'); }
    if (op === 'calendar-previous') return changeMonth(-1);
    if (op === 'calendar-next') return changeMonth(1);
    if (op === 'select-calendar-date') { runtime.selectedDate = value; render(); return; }
    if (op === 'set-date-override') { const plan = alarmDraft(); if (plan.id !== runtime.calendarPlanId) throw Error('日期覆盖未绑定当前闹钟草稿。'); const key = `${plan.id}:${runtime.selectedDate}`; const overrides = runtime.dateOverridesDraft || (runtime.dateOverridesDraft = clone(config.dateOverrides || {})); if (value === 'inherit') delete overrides[key]; else overrides[key] = value === 'off' ? { enabled:false } : { enabled:true }; render(); return; }
    if (op === 'save-override-time') { const plan = alarmDraft(); if (plan.id !== runtime.calendarPlanId) throw Error('日期覆盖未绑定当前闹钟草稿。'); const key = `${plan.id}:${runtime.selectedDate}`; const overrides = runtime.dateOverridesDraft || (runtime.dateOverridesDraft = clone(config.dateOverrides || {})); overrides[key] = { ...(overrides[key] || { enabled:true }), time:runtime.overrideDraftTime || plan.time }; closeOverlay(); render(); return; }
    if (op === 'save-calendar') return navigate('plan-edit', { replace:true });
    if (op === 'history-filter') { runtime.historyFilter = value; closeOverlay(); render(); return; }
    if (op === 'select-evaluation-plan') {
      if (!evaluationPlans().some(plan => plan.id === value)) throw Error('评估计划不存在。');
      runtime.selectedEvaluationPlanId = value;
      runtime.evaluationRun = null;
      render();
      return;
    }
    if (op === 'select-evaluation-fixture') {
      if (!Object.values(EVALUATION_FIXTURE_STATES).includes(value)) throw Error('评估 fixture 无效。');
      runtime.evaluationFixture = value;
      runtime.evaluationRun = null;
      render();
      return;
    }
    if (op === 'evaluate-now') {
      if (runtime.evaluationSubmitting) return;
      runtime.evaluationSubmitting = true;
      runtime.evaluationRun = evaluationRun();
      const decision = appendDecision(runtime.evaluationRun);
      runtime.selectedDecisionId = decision.decisionId;
      queueMicrotask(() => { runtime.evaluationSubmitting = false; render(); });
      notice(`已生成“${runtime.evaluationRun.title}”离线评估结果。`);
      render();
      return;
    }
    if (op === 'evaluate-plan') {
      if (!evaluationPlans().some(plan => plan.id === value)) throw Error('评估计划不存在。');
      runtime.selectedEvaluationPlanId = value;
      if (runtime.evaluationSubmitting) return;
      runtime.evaluationSubmitting = true;
      runtime.evaluationRun = evaluationRun();
      const decision = appendDecision(runtime.evaluationRun);
      runtime.selectedDecisionId = decision.decisionId;
      queueMicrotask(() => { runtime.evaluationSubmitting = false; render(); });
      notice(`已生成“${runtime.evaluationRun.title}”离线评估结果。`);
      render();
      return;
    }
    if (op === 'open-decision') return openDecision(value);
    if (op === 'open-current-decision') return openDecision(runtime.selectedDecisionId);
    if (op === 're-evaluate-decision') {
      const historical = decisionRecord();
      const plan = config.alarmPlans.find(item => item.id === historical?.planId);
      if (!historical) throw Error('本次决策记录不可用。');
      if (!plan) throw Error('该计划已删除，无法重新评估。');
      if (!plan.enabled) throw Error('该计划已停用，无法重新评估。');
      if (runtime.evaluationSubmitting) return;
      runtime.evaluationSubmitting = true;
      const run = createEvaluationFixture({ fixture:runtime.evaluationFixture, plan, transport:config.selectedTransport, selectedRouteIndex:runtime.selectedRouteIndex, weatherBuffers:config.weatherBuffers });
      runtime.evaluationRun = run;
      appendDecision(run, plan);
      queueMicrotask(() => { runtime.evaluationSubmitting = false; render(); });
      notice('已针对当前有效计划生成新的离线评估；正在查看的历史记录未改写。');
      render();
      return;
    }
    if (op === 'save-route') { if (runtime.routeScope === 'plan') { runtime.routeScope = 'global'; notice('本计划通勤覆盖已保存。'); return navigate('plan-edit', { replace:true }); } config = clone(runtime.routeDraft || config); config.commuteRevision = (config.commuteRevision || 0) + 1; runtime.routeDraft = null; invalidateHomePreviews(); persist(); notice('全局通勤已保存。'); return navigate('route', { replace:true }); }
    if (op === 'mode') { activeCommute().selectedTransport = value; runtime.selectedRouteIndex = 0; render(); return; }
    if (op === 'select-route') { const index = Number(value); if (!Number.isInteger(index) || index < 0 || index > 2) throw Error('路线选择无效。'); runtime.selectedRouteIndex = index; invalidateHomePreviews(); refreshHomePreview({ force:true, kind:'route' }); render(); return; }
    if (op === 'open-place') { runtime.placeTarget = value; runtime.selectedPlace = null; return navigate('place-search'); }
    if (op === 'choose-place') { runtime.selectedPlace = [...AMAP_DEMO_TIPS, ...(currentConfig().favorites || [])].find(place => place.id === value) || null; render(); return; }
    if (op === 'use-place') { const place = runtime.selectedPlace; if (!place) throw Error('请先选择一个地点。'); const c = activeCommute(); c[runtime.placeTarget] = place.name; c[`${runtime.placeTarget}Address`] = place.address; return navigate('route-edit', { replace:true }); }
    if (op === 'add-favorite') { runtime.favoriteDraft = { id:`place-${Date.now()}`, name:'', address:'', description:'本机文字地点' }; runtime.overlay = 'favorite'; render(); return; }
    if (op === 'save-favorite') { const place = runtime.favoriteDraft; if (!place?.name?.trim() || !place?.address?.trim()) throw Error('请填写名称和地址文字。'); currentConfig().favorites.push(clone(place)); runtime.favoriteDraft = null; closeOverlay(); render(); return; }
    if (op === 'amap-consent') { config.amapConsent = value === 'approved' ? 'approved' : 'basic'; config.onboardingDone = true; persist(); notice(value === 'approved' ? '已同意高德授权；可配置运行时 Key。' : '仅使用基础功能；高德地图保持未初始化。'); return navigate(value === 'approved' ? 'credentials' : 'home', { replace:true }); }
    if (op === 'edit-plan-commute') { const plan = alarmDraft(); if (!plan.commuteOverride?.enabled) plan.commuteOverride = { enabled:true, origin:config.origin, originAddress:config.originAddress, destination:config.destination, destinationAddress:config.destinationAddress, selectedTransport:config.selectedTransport }; runtime.routeScope = 'plan'; return navigate('route-edit'); }
    if (op === 'use-global-commute') { alarmDraft().commuteOverride = { enabled:false }; render(); return; }
    if (op === 'pick-map') { if (config.amapConsent !== 'approved') throw Error('请先在首次启动页同意高德授权。'); if (!runtime.credentials.amapSdkKey) throw Error('请先配置运行时 Android SDK Key。'); const c = activeCommute(); c[runtime.placeTarget] = '地图选点（演示）'; c[`${runtime.placeTarget}Address`] = '离线 fixture · 不含坐标'; notice('已应用地图选点 fixture。'); render(); return; }
    if (op === 'locate-once') return requestCurrentLocation();
    if (op === 'refresh-home-preview') { refreshHomePreview({ force:true, kind:value || null }); render(); return; }
    if (op === 'save-credentials') { runtime.amapCredentialRevision += 1; runtime.weatherCredentialRevision += 1; invalidateHomePreviews(); runtime.credentialStatus = '模拟配置已更新；原型未保存真实凭证'; render(); return; }
    if (op === 'test-credentials') { runtime.credentialStatus = '高德离线 fixture 已验证；未发送网络请求'; render(); return; }
    if (op === 'test-caiyun-credentials') { runtime.caiyunConnectionState = runtime.weatherCredentialConfigured ? 'passed' : 'pending'; invalidateHomePreviews(); runtime.credentialStatus = runtime.weatherCredentialConfigured ? '彩云天气 fixture 连接测试通过；未发送网络请求' : '请先启用天气凭据 fixture，再测试连接'; render(); return; }
    if (op === 'clear-credentials') return openOverlay('clear-credentials');
    if (op === 'confirm-clear-credentials') { runtime.credentials = {}; runtime.weatherCredentialConfigured = false; runtime.caiyunConnectionState = 'pending'; runtime.amapCredentialRevision += 1; runtime.weatherCredentialRevision += 1; invalidateHomePreviews(); runtime.credentialStatus = '当前会话模拟状态已清空'; closeOverlay(); render(); return; }
    if (op === 'preview-sound') { notice('浏览器原型不播放声音；Android 应用可试听。'); return; }
    if (op === 'open-permission-diagnostics') return navigate('diagnostics');
    if (op === 'return-permission-flow') {
      const flow = runtime.permissionFlow;
      if (!flow) return;
      if (runtime.history.at(-1) === flow.originRoute) runtime.history.pop();
      navigate(flow.originRoute, { replace:true });
      runtime.overlay = 'permission-guide';
      render();
      return;
    }
    if (op === 'continue-permission-flow') {
      const flow = runtime.permissionFlow;
      const acceptedSignature = flow ? permissionSignature() : null;
      if (acceptedSignature && !runtime.permissionPrompted.includes(acceptedSignature)) runtime.permissionPrompted.push(acceptedSignature);
      runtime.permissionFlow = null;
      closeOverlay();
      if (flow?.operation === 'save') return saveAlarm();
      if (flow?.operation === 'toggle' && flow.planId) return toggleAlarm(flow.planId, true);
      return render();
    }
    if (op === 'cancel-permission-flow') { runtime.permissionFlow = null; closeOverlay(); render(); return; }
    if (op === 'open-permission-settings') { runtime.permissionSettingsTarget = value; return openOverlay('permission-settings'); }
    if (op === 'return-from-permission-settings') {
      runtime.permissionSettingsTarget = null;
      closeOverlay();
      if (runtime.locationRequest) return requestCurrentLocation();
      notice('已返回权限演示并重新检查。');
      render();
      return;
    }
    if (op === 'set-standard-permission') {
      const [key, status] = value.split(':');
      if (key in runtime.permissionState.standard) runtime.permissionState.standard[key] = status;
      render();
      return;
    }
    if (op === 'confirm-xiaomi-permission') {
      if (value in runtime.permissionState.xiaomi) runtime.permissionState.xiaomi[value] = 'confirmed';
      render();
      return;
    }
    if (op === 'set-location-access') { runtime.permissionState.location.access = value; render(); return; }
    if (op === 'toggle-location-services') { runtime.permissionState.location.services = runtime.permissionState.location.services === 'on' ? 'off' : 'on'; render(); return; }
    if (op === 'resolve-location-request') {
      closeOverlay();
      if (value === 'services-off') {
        runtime.permissionState.location.services = 'off';
        return openOverlay('location-unavailable');
      }
      runtime.permissionState.location.access = value;
      runtime.permissionState.location.services = 'on';
      if (value === 'denied') return openOverlay('location-unavailable');
      runtime.locationRequest = null;
      return applyCurrentLocation();
    }
    if (op === 'cancel-location-request') { closeOverlay(); runtime.locationRequest = null; render(); return; }
    if (op === 'recheck-diagnostics') { notice('已重新检查演示状态；不会读取设备权限。'); return; }
  } catch (error) { notice(error.message); render(); }
}
function handleInput(event) {
  const target = event.target;
  if (target.dataset.credential) { runtime.credentials[target.dataset.credential] = target.value; return; }
  if (target.dataset.alarmField) { const plan = alarmDraft(); const field = target.dataset.alarmField; if (field === 'date') plan.repeat.date = target.value; else plan[field] = target.value; return; }
  if (target.dataset.weatherBuffer) { const profile = runtime.weatherBufferDraft?.[target.dataset.weatherBuffer]; const index = Number(target.dataset.weatherBufferIndex); if (!profile || !Number.isInteger(index) || index < 0 || index > 2) return; profile[index] = target.value.trim() === '' ? NaN : Number(target.value); return; }
  if (target.dataset.overlayField) { const plan = alarmDraft(); const field = target.dataset.overlayField; if (field === 'overrideTime') runtime.overrideDraftTime = target.value; else if (field === 'vibration') plan.vibration = target.checked; else plan[field] = ['snoozeMinutes','preparationMinutes','maxAdvanceMinutes'].includes(field) ? Number(target.value) : target.value; return; }
  if (target.dataset.favoriteField) { runtime.favoriteDraft[target.dataset.favoriteField] = target.value; return; }
  if (target.dataset.field === 'placeQuery') { runtime.placeQuery = target.value; render(); return; }
}
function handleChange(event) {
  const target = event.target;
  if (target.dataset.action === 'toggle-alarm') return requestToggleAlarm(target.dataset.value, target.checked);
  if (target.dataset.amapFixture) { runtime.amapFixture = target.value; render(); }
  if (target.dataset.caiyunFixture) { runtime.caiyunFixture = target.value; invalidateHomePreviews(); render(); }
  if (target.dataset.routeFixture) { runtime.routeFixture = target.value; invalidateHomePreviews(); render(); }
  if (target.dataset.homeConfigurationState) { runtime.homeConfigurationState = target.value; invalidateHomePreviews(); render(); }
  if (target.dataset.weatherCredentialConfigured) { runtime.weatherCredentialConfigured = target.checked; runtime.caiyunConnectionState = target.checked ? 'pending' : 'pending'; runtime.weatherCredentialRevision += 1; invalidateHomePreviews(); render(); }
  if (target.dataset.caiyunConnectionState) { runtime.caiyunConnectionState = target.value; invalidateHomePreviews(); render(); }
  if (target.dataset.overlayField === 'vibration') handleInput(event);
}
function reset() { config = defaults(); persist(); runtime = createRuntime(); runtime.route = 'onboarding'; notice('本地演示数据已重置。'); navigate('onboarding', { replace:true }); }
document.addEventListener('click', handleClick);
document.addEventListener('input', handleInput);
document.addEventListener('change', handleChange);
document.addEventListener('keydown', event => {
  if (event.key !== 'Escape' || !runtime.overlay) return;
  if (runtime.overlay === 'permission-guide') runtime.permissionFlow = null;
  if (runtime.overlay === 'location-request' || runtime.overlay === 'location-unavailable') runtime.locationRequest = null;
  closeOverlay();
  render();
});
let homePullStart = null;
document.addEventListener('pointerdown', event => {
  const scroll = document.querySelector('.prototype-scroll');
  homePullStart = runtime.route === 'home' && event.target?.closest?.('.prototype-scroll') === scroll && scroll?.scrollTop === 0 ? event.clientY : null;
});
document.addEventListener('pointerup', event => {
  if (homePullStart !== null && event.clientY - homePullStart >= 72) {
    homePullStart = null;
    refreshHomePreview({ force:true });
    render();
    return;
  }
  homePullStart = null;
});
document.addEventListener('pointercancel', () => { homePullStart = null; });
document.getElementById('scenario-select').addEventListener('change', event => navigate(event.target.value));
document.getElementById('permission-device-select')?.addEventListener?.('change', event => { runtime.permissionState.device = event.target.value; render(); });
document.getElementById('permission-entry-select')?.addEventListener?.('change', event => { runtime.permissionState.settingsEntry = event.target.value; render(); });
document.getElementById('scenario-reset').addEventListener('click', reset);
window.addEventListener('popstate', () => navigate(location.hash.replace(/^#\/?/, ''), { replace:true, fromHistory:true }));
window.addEventListener('focus', () => { if (runtime.route === 'home') { refreshHomePreview(); render(); } });
document.addEventListener('visibilitychange', () => { if (!document.hidden && runtime.route === 'home') { refreshHomePreview(); render(); } });
function fitPhone() { document.documentElement.style.setProperty('--phone-scale', String(Math.min(1, (window.innerWidth - 24) / 412))); }
window.addEventListener('resize', fitPhone); fitPhone();
function setHomePreviewFixture(fixture = {}) {
  if (fixture.config) config = { ...config, ...fixture.config };
  if (fixture.credentials) runtime.credentials = { ...runtime.credentials, ...fixture.credentials };
  for (const key of ['homeConfigurationState', 'weatherCredentialConfigured', 'caiyunConnectionState', 'caiyunFixture', 'routeFixture', 'selectedRouteIndex', 'fixtureNow', 'weatherObservedAt', 'weatherForecastWindowValid']) {
    if (key in fixture) runtime[key] = fixture[key];
  }
  runtime.amapCredentialRevision += 1;
  runtime.weatherCredentialRevision += 1;
  invalidateHomePreviews();
  if (runtime.route === 'home') refreshHomePreview({ force:true });
  render();
}
function setDiagnosticFixture(fixture = 'records') {
  if (!['records','empty'].includes(fixture)) throw new RangeError('诊断 fixture 无效。');
  runtime.diagnosticFixture = fixture;
  if (runtime.route === 'diagnostics') render();
}
window.ZhituPrototype = { ROUTES, navigate, reset, refreshHomePreview:() => { refreshHomePreview({ force:true }); render(); }, setHomePreviewFixture, setDiagnosticFixture, homePreview:() => clone(runtime.homePreview) };
const initialTarget = location.hash.replace(/^#\/?/, '');
navigate(config.onboardingDone && ROUTES.includes(routeName(initialTarget)) ? initialTarget : (config.onboardingDone ? 'home' : 'onboarding'), { replace:true });
document.fonts.ready.then(() => { document.getElementById('render-status').textContent = '412 × 892 · 本地设计字体已加载'; });
