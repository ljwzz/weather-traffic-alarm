import assert from 'node:assert/strict';
import test from 'node:test';
import { createAlarmScreens } from '../screens-alarm.mjs';
import { LEGAL_DAY_KINDS, fourWeekLegalCalendar, isLegalWorkday, legalDayKind } from '../legal-calendar.mjs';
import { REPEAT_KINDS, nextAlarmOccurrence } from '../state.mjs';

test('four-week legal-workday preview uses Monday-to-Sunday weeks around today', () => {
  const calendar = fourWeekLegalCalendar(new Date('2026-09-03T12:00:00+08:00'));

  assert.deepEqual(calendar.map(week => week.label), ['上周', '本周', '下周', '下下周']);
  assert.ok(calendar.every(week => week.days.length === 7));
  assert.equal(calendar[0].days[0].date, '2026-08-24');
  assert.equal(calendar[1].days[0].date, '2026-08-31');
  assert.equal(calendar[3].days[6].date, '2026-09-20');
  assert.equal(calendar[3].days[6].kind, LEGAL_DAY_KINDS.SPECIAL_WORKDAY);
  assert.equal(calendar[1].days[3].isToday, true);
});

test('four-week preview and scheduling retain 2026 rules across the year boundary', () => {
  const calendar = fourWeekLegalCalendar(new Date('2026-01-02T12:00:00+08:00'));
  const januaryFourth = calendar.flatMap(week => week.days).find(day => day.date === '2026-01-04');
  const plan = { id:'work', time:'07:30', enabled:true, repeat:{ kind:REPEAT_KINDS.WORKDAYS } };

  assert.equal(calendar[0].days[0].date, '2025-12-22');
  assert.equal(januaryFourth?.kind, LEGAL_DAY_KINDS.SPECIAL_WORKDAY);
  assert.deepEqual(nextAlarmOccurrence(plan, { now:new Date('2025-12-31T12:00:00+08:00') }), { date:'2026-01-04', time:'07:30' });
});

test('2026 fixture distinguishes ordinary rest days, statutory holidays, and make-up workdays', () => {
  assert.equal(legalDayKind('2026-09-19'), LEGAL_DAY_KINDS.REST_DAY);
  assert.equal(legalDayKind('2026-09-20'), LEGAL_DAY_KINDS.SPECIAL_WORKDAY);
  assert.equal(legalDayKind('2026-09-25'), LEGAL_DAY_KINDS.SPECIAL_HOLIDAY);
  assert.equal(legalDayKind('2026-09-28'), LEGAL_DAY_KINDS.WORKDAY);
  assert.equal(isLegalWorkday('2026-09-20'), true);
  assert.equal(isLegalWorkday('2026-09-25'), false);
});

test('workday alarm scheduling uses the legal-calendar fixture across the September boundary', () => {
  const plan = { id:'work', time:'07:30', enabled:true, repeat:{ kind:REPEAT_KINDS.WORKDAYS } };

  assert.deepEqual(nextAlarmOccurrence(plan, { now:new Date('2026-09-19T12:00:00+08:00') }), { date:'2026-09-20', time:'07:30' });
  assert.deepEqual(nextAlarmOccurrence(plan, { now:new Date('2026-09-24T12:00:00+08:00') }), { date:'2026-09-28', time:'07:30' });
});

test('plan editor adds the Figma workday preview only for legal-workday schedules', () => {
  const render = repeat => createAlarmScreens({
    action: () => '', overlayAction: () => '', asset: () => '',
    now: () => new Date('2026-09-03T12:00:00+08:00'),
    state: { config: {}, runtime: { alarmDraft: { id:'work', name:'上班', time:'07:30', repeat, ringtone:'晨光', vibration:true, snoozeMinutes:10 } } },
  })['plan-edit']();
  const workdayHtml = render({ kind:REPEAT_KINDS.WORKDAYS });

  assert.match(workdayHtml, /工作日预览/);
  assert.match(workdayHtml, /<div class="workday-calendar-headings"><span>一<\/span><span>二<\/span><span>三<\/span><span>四<\/span><span>五<\/span><span>六<\/span><span>日<\/span><\/div>/);
  assert.equal((workdayHtml.match(/class="workday-calendar-week"/g) || []).length, 4);
  assert.equal((workdayHtml.match(/data-calendar-date=/g) || []).length, 28);
  assert.doesNotMatch(workdayHtml, /上周|本周|下周|下下周|特殊节假日|特殊工作日/);
  assert.match(workdayHtml, /data-calendar-date="2026-08-24">8\/24/);
  assert.match(workdayHtml, /data-calendar-date="2026-09-01">9\/1/);
  assert.match(workdayHtml, /data-calendar-date="2026-09-20"/);
  assert.match(workdayHtml, /data-calendar-date="2026-09-03">3<\/span>/);
  assert.match(workdayHtml, /is-today/);
  assert.doesNotMatch(render({ kind:REPEAT_KINDS.ONCE, date:'2026-09-04' }), /workday-calendar/);
});
