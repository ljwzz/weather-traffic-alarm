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
      runtime: { amapFixture: 'success', credentials: { amapWebKey: 'fixture' }, selectedRouteIndex },
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
