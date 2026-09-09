export const LEGAL_DAY_KINDS = Object.freeze({
  WORKDAY: 'workday',
  REST_DAY: 'rest-day',
  SPECIAL_HOLIDAY: 'special-holiday',
  SPECIAL_WORKDAY: 'special-workday',
});

const holidayRange = (start, end) => {
  const days = [];
  const date = new Date(`${start}T12:00:00`);
  const last = new Date(`${end}T12:00:00`);
  while (date <= last) {
    days.push(toIso(date));
    date.setDate(date.getDate() + 1);
  }
  return days;
};

const toIso = date => {
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
};

/**
 * 2026-only offline fixture from the State Council holiday notice:
 * https://www.beijing.gov.cn/cs/gncs/zcwj/202603/t20260327_4568275.html
 */
export const LEGAL_CALENDAR_2026 = Object.freeze({
  ...Object.fromEntries([
    ...holidayRange('2026-01-01', '2026-01-03'),
    ...holidayRange('2026-02-15', '2026-02-23'),
    ...holidayRange('2026-04-04', '2026-04-06'),
    ...holidayRange('2026-05-01', '2026-05-05'),
    ...holidayRange('2026-06-19', '2026-06-21'),
    ...holidayRange('2026-09-25', '2026-09-27'),
    ...holidayRange('2026-10-01', '2026-10-07'),
  ].map(date => [date, LEGAL_DAY_KINDS.SPECIAL_HOLIDAY])),
  '2026-01-04': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
  '2026-02-14': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
  '2026-02-28': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
  '2026-05-09': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
  '2026-09-20': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
  '2026-10-10': LEGAL_DAY_KINDS.SPECIAL_WORKDAY,
});

const dateAtNoon = value => new Date(`${value}T12:00:00`);
const addDays = (date, amount) => { const result = new Date(date); result.setDate(result.getDate() + amount); return result; };
const mondayOfWeek = date => addDays(date, -((date.getDay() + 6) % 7));

export function legalDayKind(date) {
  if (LEGAL_CALENDAR_2026[date]) return LEGAL_CALENDAR_2026[date];
  const weekday = dateAtNoon(date).getDay();
  return weekday === 0 || weekday === 6 ? LEGAL_DAY_KINDS.REST_DAY : LEGAL_DAY_KINDS.WORKDAY;
}

export function isLegalWorkday(date) {
  return [LEGAL_DAY_KINDS.WORKDAY, LEGAL_DAY_KINDS.SPECIAL_WORKDAY].includes(legalDayKind(date));
}

const dateLabel = (date, firstDate) => {
  const [, month, day] = date.split('-').map(Number);
  return date === firstDate || day === 1 ? `${month}/${day}` : String(day);
};

export function legalCalendarRangeLabel(calendar) {
  const dates = calendar.flatMap(week => week.days.map(day => day.date));
  const [start, end] = [dates[0], dates.at(-1)];
  const [startYear, startMonth, startDay] = start.split('-').map(Number);
  const [endYear, endMonth, endDay] = end.split('-').map(Number);
  return startYear === endYear
    ? `${startYear}年${startMonth}月${startDay}日 — ${endMonth}月${endDay}日`
    : `${startYear}年${startMonth}月${startDay}日 — ${endYear}年${endMonth}月${endDay}日`;
}

export function fourWeekLegalCalendar(now = new Date()) {
  const today = toIso(now);
  const currentMonday = mondayOfWeek(dateAtNoon(today));
  const firstDate = toIso(addDays(currentMonday, -7));
  return ['上周', '本周', '下周', '下下周'].map((label, index) => {
    const start = addDays(currentMonday, (index - 1) * 7);
    return {
      label,
      days: Array.from({ length: 7 }, (_, dayOffset) => {
        const date = toIso(addDays(start, dayOffset));
        return { date, day: dateLabel(date, firstDate), kind: legalDayKind(date), isToday:date === today };
      }),
    };
  });
}
