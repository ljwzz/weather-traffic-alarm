import assert from 'node:assert/strict';
import test from 'node:test';
import {
  defaultAlarmDraft,
  AMAP_FIXTURE_STATES,
  amapFixtureState,
  nextAlarmOccurrence,
  normalizeAlarmPlan,
  persistentSettingsSnapshot,
  REPEAT_KINDS,
  repeatLabel,
  resolveCommute,
  todayIso,
  validateAlarmPlan,
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
  const snapshot = persistentSettingsSnapshot({ alarmPlans:[{ id:'alarm' }], credentials:{ amapWebKey:'secret' } });
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

test('plan commute override replaces only that plan effective commute', () => {
  const global = { origin: '全局起点', destination: '全局终点', selectedTransport: 'transit' };
  assert.equal(resolveCommute(global).origin, '全局起点');
  const commute = resolveCommute(global, { commuteOverride: { enabled:true, origin:'计划起点', destination:'计划终点', selectedTransport:'walking' } });
  assert.deepEqual(commute, { enabled:true, origin:'计划起点', originAddress:'', destination:'计划终点', destinationAddress:'', selectedTransport:'walking' });
});
