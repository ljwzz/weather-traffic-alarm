import assert from 'node:assert/strict';
import test from 'node:test';
import { alarmCountdown, shiftWheelTime, wheelValues } from '../time-wheel.mjs';

test('time wheels wrap at midnight and minute boundaries in both clock modes', () => {
  assert.equal(shiftWheelTime('23:59', 'hour', 1), '00:59');
  assert.equal(shiftWheelTime('23:59', 'minute', 1), '23:00');
  assert.equal(shiftWheelTime('00:00', 'minute', -1), '00:59');
  assert.deepEqual(wheelValues('23:59', 'minute').slice(1, 4), ['58', '59', '00']);
  assert.equal(shiftWheelTime('11:00', 'period', 1, false), '23:00');
  assert.equal(shiftWheelTime('11:00', 'hour', 1, false), '00:00');
});

test('countdown uses the next selected occurrence and allows an empty note', () => {
  const plan = { id:'alarm-test', name:'', time:'11:00', repeat:{ kind:'once', date:'2026-09-28' }, enabled:true };
  assert.equal(alarmCountdown(plan, new Date('2026-09-28T10:32:00')), '28 分钟后响铃');
  assert.equal(alarmCountdown(plan, new Date('2026-09-28T11:01:00')), '暂无下一次响铃');
  assert.equal(alarmCountdown(plan, new Date('2026-09-28T10:32:00'), { 'alarm-test:2026-09-28':{ enabled:true, time:'10:45' } }), '13 分钟后响铃');
  assert.equal(alarmCountdown(plan, new Date('2026-09-28T10:46:00'), { 'alarm-test:2026-09-28':{ enabled:true, time:'10:45' } }), '暂无下一次响铃');
});
