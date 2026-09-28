import assert from 'node:assert/strict';
import test from 'node:test';
import { createTravelScreens } from '../screens-travel.mjs';

function renderRoute(selectedRouteIndex) {
  return createTravelScreens({
    action: () => '',
    overlayAction: (label, name, value) => `<button data-action="${name}" data-value="${value}">${label}</button>`,
    asset: () => '',
    state: {
      config: { selectedTransport: 'transit' },
      runtime: { amapFixture: 'success', credentials: { amapWebKey: 'fixture', amapSdkKey:'fixture' }, selectedRouteIndex, homePreview:{ route:{ state:'success', result:{ transport:'公交', distance:'15.6 km', duration:'39 分钟', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自高德路线服务' } } } },
    },
  }).route();
}

function selectedControls(html, index) {
  return html.match(new RegExp(`<button(?=[^>]*data-action="select-route")(?=[^>]*data-value="${index}")(?=[^>]*aria-pressed="true")[^>]*>`, 'g')) || [];
}

test('route fixture renders three selectable cards and map lines for one selected index', () => {
  const html = renderRoute(2);

  assert.equal((html.match(/data-action="select-route"/g) || []).length, 6);
  assert.equal((html.match(/aria-pressed="true"/g) || []).length, 2);
  assert.equal(selectedControls(html, 2).length, 2);
});

test('route fixture falls back to the first candidate when its selected index is invalid', () => {
  const html = renderRoute(7);

  assert.equal(selectedControls(html, 0).length, 2);
});

test('weather fixture exposes a selected plan and an immediate deterministic evaluation entry', () => {
  const html = createTravelScreens({
    action: (label, route) => `<button data-route="${route}">${label}</button>`,
    overlayAction: (label, name, value) => `<button data-action="${name}" data-value="${value}">${label}</button>`,
    asset: () => '',
    state: {
      config: { selectedTransport:'driving' },
      runtime: {
        caiyunFixture:'success',
        selectedRouteIndex:0,
        selectedEvaluationPlanId:'work',
        evaluationFixture:'advanced',
        evaluationPlan:{ id:'work', name:'上班', time:'07:30' },
        evaluationPlans:[{ id:'work', name:'上班', time:'07:30' }],
      },
    },
  }).weather();

  assert.match(html, /data-action="select-evaluation-plan" data-value="work"/);
  assert.match(html, /data-action="evaluate-now"/);
  assert.match(html, /提前 17 分钟/);
});

test('home renders an immediate evaluation entry for every eligible plan', () => {
  const html = createTravelScreens({
    action: (label, route) => `<button data-route="${route}">${label}</button>`,
    overlayAction: () => '',
    asset: () => '',
    state: {
      config: { selectedTransport:'driving', alarmPlans:[{ id:'work-a', name:'早班', time:'07:00', enabled:true }, { id:'work-b', name:'晚班', time:'08:00', enabled:true }] },
      runtime: { caiyunFixture:'success', evaluationPlans:[{ id:'work-a', name:'早班', time:'07:00' }, { id:'work-b', name:'晚班', time:'08:00' }] },
    },
  }).home();

  assert.match(html, /data-action="evaluate-plan" data-value="work-a"/);
  assert.match(html, /data-action="evaluate-plan" data-value="work-b"/);
});

test('home status cards use the shared weather and route success summaries before opening details', () => {
  const state = {
    config: { origin:'家', destination:'公司', selectedTransport:'driving', alarmPlans:[] },
    runtime: {
      homePreview: {
        weather:{ state:'success', result:{ severity:'晴好天气', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自彩云天气' }, refreshing:false },
        route:{ state:'success', result:{ transport:'驾车', distance:'12.4 km', duration:'18 分钟', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自高德路线服务' }, refreshing:false },
      },
      evaluationPlans:[],
    },
  };
  const screens = createTravelScreens({ action:(label, route) => `<button data-route="${route}">${label}</button>`, overlayAction:(label, name, value) => `<button data-action="${name}" data-value="${value}">${label}</button>`, asset:() => '', state });
  const home = screens.home();
  assert.match(home, /彩云天气/);
  assert.match(home, /晴好天气/);
  assert.match(home, /家 → 公司 · 数据时间：09-05 07:00 · 数据来自彩云天气/);
  assert.match(home, /通勤路线/);
  assert.match(home, /驾车 · 12.4 km · 18 分钟/);
  assert.match(home, /刷新通勤预览/);
  assert.ok(home.indexOf('彩云天气') < home.indexOf('自动评估') && home.indexOf('自动评估') < home.indexOf('本地闹钟') && home.indexOf('本地闹钟') < home.indexOf('通勤路线'));
  assert.match(screens.weather(), /首页预览：晴好天气 · 家 → 公司/);
  assert.match(screens.route(), /首页预览：驾车 · 12.4 km · 18 分钟/);
});

test('home status cards expose recovery controls and preserve cached data on an update error', () => {
  const html = createTravelScreens({
    action:(label, route) => `<button data-route="${route}">${label}</button>`,
    overlayAction:(label, name, value) => `<button data-action="${name}" data-value="${value}">${label}</button>`,
    asset:() => '',
    state: { config:{}, runtime:{ homePreview:{ weather:{ state:'credential-missing', result:null }, route:{ state:'error', result:{ transport:'驾车', distance:'12.4 km', duration:'18 分钟', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自高德路线服务' } }, evaluationPlans:[] } } },
  }).home();
  assert.match(html, /尚未配置彩云凭据/);
  assert.match(html, /配置凭据/);
  assert.match(html, /无法获取路线/);
  assert.match(html, /更新失败，保留上次结果/);
  assert.match(html, /data-action="refresh-home-preview" data-value="route"/);
});

test('weather and route detail bodies are gated by their shared home preview state', () => {
  const state = {
    config:{},
    runtime:{
      homePreview:{ weather:{ state:'credential-missing', result:null }, route:{ state:'loading', result:null } },
      evaluationPlans:[],
    },
  };
  const screens = createTravelScreens({ action:(label, route) => `<button data-route="${route}">${label}</button>`, overlayAction:() => '', asset:() => '', state });
  assert.match(screens.weather(), /尚未配置彩云凭据/);
  assert.doesNotMatch(screens.weather(), /caiyun-weather-fixture/);
  assert.match(screens.route(), /正在查询驾车路线/);
  assert.doesNotMatch(screens.route(), /amap-route-options/);
});
