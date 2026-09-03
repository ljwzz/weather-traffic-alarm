import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createRingingSession,
  RINGING_KINDS,
  RINGING_PHASES,
  ringSnoozedSession,
  snoozeRingingSession,
  stopRingingSession,
  validateSnoozeMinutes,
} from '../ringing-state.mjs';

test('basic and early fixtures create independent local occurrences', () => {
  const basic = createRingingSession(RINGING_KINDS.BASIC);
  const early = createRingingSession(RINGING_KINDS.EARLY);

  assert.deepEqual(basic.occurrence, { id:'basic:2026-09-02T07:30:00#0', parentId:null, sequence:0, date:'2026-09-02', time:'07:30' });
  assert.equal(early.occurrence.time, '07:18');
  assert.equal(early.snoozeMinutes, 10);
  assert.equal(basic.phase, RINGING_PHASES.RINGING);
});

test('snooze is idempotent and a replayed alarm uses a child occurrence', () => {
  const first = createRingingSession(RINGING_KINDS.EARLY);
  const snoozed = snoozeRingingSession(first);
  const repeated = snoozeRingingSession(snoozed);
  const child = ringSnoozedSession(snoozed);

  assert.equal(snoozed.phase, RINGING_PHASES.SNOOZED);
  assert.equal(snoozed.nextOccurrence.time, '07:28');
  assert.equal(repeated, snoozed);
  assert.equal(child.phase, RINGING_PHASES.RINGING);
  assert.equal(child.occurrence.parentId, first.occurrence.id);
  assert.equal(child.occurrence.sequence, 1);
  assert.equal(snoozeRingingSession(child).nextOccurrence.time, '07:38');
});

test('snooze crosses calendar dates from the current child occurrence', () => {
  const late = createRingingSession(RINGING_KINDS.BASIC, { date:'2026-09-02', time:'23:55', snoozeMinutes:10 });
  const firstSnooze = snoozeRingingSession(late);
  const child = ringSnoozedSession(firstSnooze);
  const secondSnooze = snoozeRingingSession(child);

  assert.deepEqual(firstSnooze.nextOccurrence, {
    id:'basic:2026-09-02T23:55:00#0/2026-09-03T00:05:00#1', parentId:'basic:2026-09-02T23:55:00#0', sequence:1, date:'2026-09-03', time:'00:05',
  });
  assert.equal(secondSnooze.nextOccurrence.time, '00:15');
  assert.equal(secondSnooze.nextOccurrence.sequence, 2);
});

test('stop is idempotent and snooze accepts only 1–30 minutes', () => {
  const stopped = stopRingingSession(createRingingSession());
  assert.equal(stopped.phase, RINGING_PHASES.STOPPED);
  assert.equal(stopRingingSession(stopped), stopped);
  assert.equal(snoozeRingingSession(stopped), stopped);
  assert.equal(validateSnoozeMinutes(1), 1);
  assert.equal(validateSnoozeMinutes(30), 30);
  assert.throws(() => validateSnoozeMinutes(0), /1–30/);
  assert.throws(() => validateSnoozeMinutes(31), /1–30/);
  assert.throws(() => validateSnoozeMinutes(1.5), /1–30/);
});
