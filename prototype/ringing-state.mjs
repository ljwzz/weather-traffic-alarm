/** Fixed, local-only ringing fixtures. They are intentionally never persisted. */
export const RINGING_KINDS = Object.freeze({ BASIC: 'basic', EARLY: 'early' });
export const RINGING_PHASES = Object.freeze({ RINGING: 'ringing', STOPPED: 'stopped', SNOOZED: 'snoozed' });

const FIXTURES = Object.freeze({
  [RINGING_KINDS.BASIC]: Object.freeze({
    title: '基础闹钟', date: '2026-09-02', weekday: '星期三', time: '07:30',
    badge: '按设定时间提醒', reasonTitle: '基础闹钟',
    reason: '按计划 07:30 提醒；不依赖天气或路线。',
  }),
  [RINGING_KINDS.EARLY]: Object.freeze({
    title: '提前闹钟', date: '2026-09-02', weekday: '星期三', time: '07:18',
    badge: '今天，比平时早 12 分钟', reasonTitle: '今天路上有小雨',
    reason: '通勤约 47 分钟，建议 08:03 出发。慢慢准备，也能准时到达。',
  }),
});

export function validateSnoozeMinutes(value) {
  const minutes = Number(value);
  if (!Number.isInteger(minutes) || minutes < 1 || minutes > 30) throw new RangeError('贪睡间隔必须为 1–30 分钟。');
  return minutes;
}

function fixture(kind) {
  if (!Object.values(RINGING_KINDS).includes(kind)) throw new TypeError('未知的响铃演示类型。');
  return FIXTURES[kind];
}

function addMinutes(date, time, minutes) {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const instant = new Date(Date.UTC(year, month - 1, day, hour, minute));
  instant.setUTCMinutes(instant.getUTCMinutes() + minutes);
  const iso = instant.toISOString();
  return { date: iso.slice(0, 10), time: iso.slice(11, 16) };
}

export function createRingingSession(kind = RINGING_KINDS.EARLY, options = {}) {
  const item = fixture(kind);
  const date = options.date || item.date;
  const time = options.time || item.time;
  const snoozeMinutes = validateSnoozeMinutes(options.snoozeMinutes ?? 10);
  return {
    kind,
    phase: RINGING_PHASES.RINGING,
    snoozeMinutes,
    occurrence: { id: `${kind}:${date}T${time}:00#0`, parentId: null, sequence: 0, date, time },
  };
}

export function stopRingingSession(session) {
  if (session?.phase !== RINGING_PHASES.RINGING) return session;
  return { ...session, phase: RINGING_PHASES.STOPPED };
}

export function snoozeRingingSession(session, minutes = session?.snoozeMinutes) {
  if (session?.phase !== RINGING_PHASES.RINGING) return session;
  const snoozeMinutes = validateSnoozeMinutes(minutes);
  const next = addMinutes(session.occurrence.date, session.occurrence.time, snoozeMinutes);
  return {
    ...session,
    phase: RINGING_PHASES.SNOOZED,
    snoozeMinutes,
    nextOccurrence: {
      id: `${session.occurrence.id}/${next.date}T${next.time}:00#${session.occurrence.sequence + 1}`,
      parentId: session.occurrence.id,
      sequence: session.occurrence.sequence + 1,
      ...next,
    },
  };
}

export function ringSnoozedSession(session) {
  if (session?.phase !== RINGING_PHASES.SNOOZED || !session.nextOccurrence) return session;
  const { nextOccurrence, ...rest } = session;
  return { ...rest, phase: RINGING_PHASES.RINGING, occurrence: nextOccurrence };
}

export function ringingFixture(kind) {
  return fixture(kind);
}
