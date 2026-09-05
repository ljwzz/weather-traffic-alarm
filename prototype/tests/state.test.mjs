import assert from 'node:assert/strict';
import test from 'node:test';
import {
  defaultAlarmDraft,
  AMAP_FIXTURE_STATES,
  amapFixtureState,
  CAIYUN_FIXTURE_STATES,
  caiyunFixtureState,
  EVALUATION_FIXTURE_STATES,
  HOME_PREVIEW_STATES,
  applyHomePreviewResponse,
  createHomePreviewState,
  createEvaluationFixture,
  evaluationFixtureHistory,
  homePreviewInputSignature,
  homePreviewIsFresh,
  homePreviewPrerequisite,
  nextAlarmOccurrence,
  normalizeAlarmPlan,
  persistentSettingsSnapshot,
  REPEAT_KINDS,
  repeatLabel,
  resolveCommute,
  routePreviewResult,
  todayIso,
  validateAlarmPlan,
  weatherPreviewResult,
} from '../state.mjs';

const at = value => new Date(value);

test('new alarm defaults to a future one-time 06:00 alarm', () => {
  const early = defaultAlarmDraft(at('2026-08-31T05:00:00+08:00'));
  assert.equal(early.time, '06:00');
  assert.deepEqual(early.repeat, { kind: REPEAT_KINDS.ONCE, date: '2026-08-31' });
  assert.equal(early.enabled, true);

  const late = defaultAlarmDraft(at('2026-08-31T07:00:00+08:00'));
  assert.equal(late.repeat.date, '2026-09-01');
});

test('one-time alarms reject an enabled time that has already passed', () => {
  assert.throws(() => validateAlarmPlan({
    id: 'once', name: '单次', time: '06:00', enabled: true,
    repeat: { kind: REPEAT_KINDS.ONCE, date: '2026-08-31' },
  }, { now: at('2026-08-31T07:00:00+08:00') }), /future/);
});

test('a disabled one-time alarm can preserve a past date while editing', () => {
  const plan = validateAlarmPlan({
    id: 'once', name: '单次', time: '06:00', enabled: false,
    repeat: { kind: REPEAT_KINDS.ONCE, date: '2026-08-31' },
  }, { now: at('2026-08-31T07:00:00+08:00') });
  assert.equal(plan.enabled, false);
});

test('weekly alarms require at least one weekday', () => {
  assert.throws(() => normalizeAlarmPlan({
    id: 'weekly', time: '07:00', repeat: { kind: REPEAT_KINDS.WEEKLY, weekdays: [] },
  }), /at least one weekday/);
});

test('weekly next occurrence crosses a week boundary', () => {
  const result = nextAlarmOccurrence({
    id: 'weekly', time: '07:00', enabled: true,
    repeat: { kind: REPEAT_KINDS.WEEKLY, weekdays: [1] },
  }, { now: at('2026-08-31T08:00:00+08:00') });
  assert.deepEqual(result, { date: '2026-09-07', time: '07:00' });
});

test('workday next occurrence skips a weekend', () => {
  const result = nextAlarmOccurrence({
    id: 'workday', time: '06:00', enabled: true,
    repeat: { kind: REPEAT_KINDS.WORKDAYS },
  }, { now: at('2026-08-28T07:00:00+08:00') });
  assert.deepEqual(result, { date: '2026-08-31', time: '06:00' });
});

test('date override only affects its matching plan and date', () => {
  const plan = { id: 'a', time: '07:00', enabled: true, repeat: { kind: REPEAT_KINDS.WORKDAYS } };
  const first = nextAlarmOccurrence(plan, { now: at('2026-08-31T06:00:00+08:00'), override: { 'a:2026-08-31': { enabled: false }, 'b:2026-08-31': { time: '05:30' } } });
  assert.deepEqual(first, { date: '2026-09-01', time: '07:00' });
  const replacement = nextAlarmOccurrence(plan, { now: at('2026-08-31T06:00:00+08:00'), override: { 'a:2026-08-31': { enabled: true, time: '05:30' } } });
  assert.deepEqual(replacement, { date: '2026-08-31', time: '05:30', overridden: true });
});

test('one-time alarms are completed when no future occurrence remains', () => {
  const result = nextAlarmOccurrence({
    id: 'completed', time: '06:00', enabled: true,
    repeat: { kind: REPEAT_KINDS.ONCE, date: '2026-08-30' },
  }, { now: at('2026-08-31T07:00:00+08:00') });
  assert.equal(result, null);
});

test('repeat labels cover one-time, weekly and workday rules', () => {
  assert.equal(repeatLabel({ id:'a', time:'06:00', repeat:{ kind:REPEAT_KINDS.ONCE, date:'2026-08-31' } }), '2026-08-31 单次');
  assert.equal(repeatLabel({ id:'b', time:'06:00', repeat:{ kind:REPEAT_KINDS.WEEKLY, weekdays:[1,3,5] } }), '一、三、五');
  assert.equal(repeatLabel({ id:'c', time:'06:00', repeat:{ kind:REPEAT_KINDS.WORKDAYS } }), '工作日');
});

test('plan normalization keeps registration state, ringtone and snooze configuration', () => {
  const plan = normalizeAlarmPlan({ id:'alarm', name:'上班', time:'06:30', enabled:true, scheduleStatus:'registered', ringtone:'清风', vibration:false, snoozeMinutes:15, repeat:{ kind:REPEAT_KINDS.WORKDAYS } });
  assert.equal(plan.scheduleStatus, 'registered');
  assert.equal(plan.ringtone, '清风');
  assert.equal(plan.vibration, false);
  assert.equal(plan.snoozeMinutes, 15);
});

test('browser persistence excludes credentials but preserves local alarm plans', () => {
  const snapshot = persistentSettingsSnapshot({ alarmPlans:[{ id:'alarm' }], credentials:{ amapWebKey:'secret', caiyunAppKey:'fixture-key', caiyunAppSecret:'fixture-secret' } });
  assert.deepEqual(snapshot, { alarmPlans:[{ id:'alarm' }] });
});

test('todayIso uses the device-local calendar day', () => {
  assert.equal(todayIso(at('2026-08-31T01:00:00+08:00')), '2026-08-31');
});

test('AMap fixture state never needs a real key and exposes explicit unavailable states', () => {
  assert.equal(amapFixtureState({}), AMAP_FIXTURE_STATES.NO_KEY);
  assert.equal(amapFixtureState({ amapWebKey: 'runtime-only' }), AMAP_FIXTURE_STATES.SUCCESS);
  assert.equal(amapFixtureState({}, AMAP_FIXTURE_STATES.DENIED), AMAP_FIXTURE_STATES.DENIED);
});

test('Caiyun fixture supports loading, success, cached and error without credentials', () => {
  assert.equal(caiyunFixtureState(), CAIYUN_FIXTURE_STATES.SUCCESS);
  for (const fixture of Object.values(CAIYUN_FIXTURE_STATES)) {
    assert.equal(caiyunFixtureState(fixture), fixture);
  }
  assert.throws(() => caiyunFixtureState('network'), /Unknown Caiyun fixture state/);
});

test('automatic evaluation fixture keeps the base alarm separate from an early reminder', () => {
  const result = createEvaluationFixture({
    fixture: EVALUATION_FIXTURE_STATES.ADVANCED,
    plan: { id:'work', name:'上班', time:'07:30' },
    transport:'driving',
  });

  assert.equal(result.inputs.dayRule.kind, 'workday');
  assert.equal(result.schedule.baseWake, '07:30');
  assert.equal(result.schedule.earlyWake, '07:13');
  assert.equal(result.schedule.action, 'create_early_reminder');
  assert.equal(result.schedule.capped, false);
});

test('retry, deadline and expired fixtures do not create a new early reminder', () => {
  const retry = createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.RETRY });
  const deadline = createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.DEADLINE });
  const expired = createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.EXPIRED });

  assert.equal(retry.schedule.action, 'preserve_early_reminder');
  assert.equal(deadline.schedule.earlyWake, null);
  assert.equal(expired.decision, 'expired_result');
});

test('evaluation history includes success, no-advance and recovery states', () => {
  const history = evaluationFixtureHistory({ id:'work', name:'上班', time:'07:30' });
  assert.deepEqual(history.map(item => item.state), ['advanced', 'no-advance', 'retry', 'deadline', 'expired']);
});

test('alarm plans persist arrival, preparation and maximum advance settings', () => {
  const plan = normalizeAlarmPlan({
    id:'work', time:'07:30', arrivalTime:'09:15', preparationMinutes:45, maxAdvanceMinutes:90,
    repeat:{ kind:REPEAT_KINDS.WORKDAYS },
  });
  assert.equal(plan.arrivalTime, '09:15');
  assert.equal(plan.preparationMinutes, 45);
  assert.equal(plan.maxAdvanceMinutes, 90);
  assert.throws(() => normalizeAlarmPlan({ id:'invalid', time:'07:30', preparationMinutes:241, repeat:{ kind:REPEAT_KINDS.WORKDAYS } }), /preparationMinutes/);
  assert.throws(() => normalizeAlarmPlan({ id:'invalid', time:'07:30', maxAdvanceMinutes:181, repeat:{ kind:REPEAT_KINDS.WORKDAYS } }), /maxAdvanceMinutes/);
});

test('plan commute override replaces only that plan effective commute', () => {
  const global = { origin: '全局起点', destination: '全局终点', selectedTransport: 'transit' };
  assert.equal(resolveCommute(global).origin, '全局起点');
  const commute = resolveCommute(global, { commuteOverride: { enabled:true, origin:'计划起点', destination:'计划终点', selectedTransport:'walking' } });
  assert.deepEqual(commute, { enabled:true, origin:'计划起点', originAddress:'', destination:'计划终点', destinationAddress:'', selectedTransport:'walking' });
});

test('home preview prerequisites separately expose weather credentials, connection and route authorization', () => {
  const configured = { amapConsent:'approved', origin:'家', destination:'公司' };
  assert.equal(homePreviewPrerequisite('weather', configured, {}), HOME_PREVIEW_STATES.CREDENTIAL_MISSING);
  assert.equal(homePreviewPrerequisite('weather', configured, { weatherCredentialConfigured:true, caiyunConnectionState:'pending' }), HOME_PREVIEW_STATES.CONNECTION_PENDING);
  assert.equal(homePreviewPrerequisite('weather', configured, { weatherCredentialConfigured:true, caiyunConnectionState:'failed' }), HOME_PREVIEW_STATES.CONNECTION_FAILED);
  assert.equal(homePreviewPrerequisite('weather', configured, { homeConfigurationState:'error' }), HOME_PREVIEW_STATES.CONFIG_ERROR);
  assert.equal(homePreviewPrerequisite('weather', configured, { weatherCredentialConfigured:true, caiyunConnectionState:'passed' }), null);
  assert.equal(homePreviewPrerequisite('route', { ...configured, amapConsent:'pending' }, { credentials:{ amapWebKey:'fixture' } }), HOME_PREVIEW_STATES.AUTHORIZATION_MISSING);
  assert.equal(homePreviewPrerequisite('route', configured, { credentials:{} }), HOME_PREVIEW_STATES.WEB_KEY_MISSING);
  assert.equal(homePreviewPrerequisite('route', { ...configured, destination:'' }, { credentials:{ amapWebKey:'fixture' } }), HOME_PREVIEW_STATES.LOCATION_MISSING);
});

test('home preview input changes for same place text after a commute or credential replacement', () => {
  const config = { amapConsent:'approved', origin:'家', destination:'公司', selectedTransport:'driving', commuteRevision:1 };
  const runtime = { credentials:{ amapWebKey:'fixture' }, amapCredentialRevision:1, weatherCredentialConfigured:true, weatherCredentialRevision:1, caiyunConnectionState:'passed' };
  const initial = homePreviewInputSignature(config, runtime);
  assert.notEqual(homePreviewInputSignature({ ...config, commuteRevision:2 }, runtime), initial);
  assert.notEqual(homePreviewInputSignature(config, { ...runtime, amapCredentialRevision:2 }), initial);
  assert.notEqual(homePreviewInputSignature(config, { ...runtime, weatherCredentialRevision:2 }), initial);
});

test('home preview response rejects stale generations and keeps an error fallback result', () => {
  const preview = { ...createHomePreviewState().weather, generation:3, inputSignature:'new', result:{ severity:'晴好天气' }, updatedAt:10, refreshing:true };
  assert.equal(applyHomePreviewResponse(preview, { generation:2, inputSignature:'new', state:'success', result:{ severity:'旧数据' } }), preview);
  const failure = applyHomePreviewResponse(preview, { generation:3, inputSignature:'new', state:'error', updatedAt:20 });
  assert.equal(failure.state, HOME_PREVIEW_STATES.ERROR);
  assert.deepEqual(failure.result, { severity:'晴好天气' });
  assert.equal(homePreviewIsFresh({ state:'success', result:{}, inputSignature:'new', updatedAt:100 }, 'new', 100, 199), true);
  assert.equal(homePreviewIsFresh({ state:'success', result:{ sourceTimestampMs:100, forecastWindowEndsAtMs:150, forecastWindowValid:true }, inputSignature:'new', updatedAt:100 }, 'new', 100, 151), false);
});

test('home preview summaries use deterministic shared weather and selected route fields', () => {
  const config = { origin:'家', destination:'公司', selectedTransport:'driving' };
  assert.deepEqual(weatherPreviewResult(config, { fixtureNow:100 }), { severity:'晴好天气', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自彩云天气', sourceTimestampMs:100, forecastWindowEndsAtMs:900100, forecastWindowValid:true });
  assert.deepEqual(routePreviewResult(config, { selectedRouteIndex:0 }), { transport:'驾车', distance:'12.4 km', duration:'18 分钟', endpoints:'家 → 公司', observedAt:'09-05 07:00', source:'数据来自高德路线服务' });
});
