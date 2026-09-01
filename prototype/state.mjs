/**
 * Prototype-only state helpers.
 *
 * These helpers intentionally model visible demo behaviour. They do not call
 * device alarm, location, notification, calendar, or third-party services.
 */

export const DAY_KINDS = Object.freeze({
  WORKDAY: 'workday',
  WEEKEND: 'weekend',
  STATUTORY_HOLIDAY: 'statutoryHoliday',
});

/** Deterministic, key-free states used by the AMap prototype handoff. */
export const AMAP_FIXTURE_STATES = Object.freeze({
  SUCCESS: 'success',
  LOADING: 'loading',
  NO_KEY: 'no-key',
  DENIED: 'denied',
  ERROR: 'error',
});

export const AMAP_DEMO_TIPS = Object.freeze([
  Object.freeze({ id: 'demo-campus-north', name: '示例园区北门', address: '演示地点 · 不含坐标' }),
  Object.freeze({ id: 'demo-station-east', name: '示例换乘站东口', address: '演示地点 · 不含坐标' }),
  Object.freeze({ id: 'demo-office', name: '示例办公区', address: '演示地点 · 不含坐标' }),
]);

export function amapFixtureState(credentials = {}, fixture = AMAP_FIXTURE_STATES.SUCCESS) {
  if (fixture !== AMAP_FIXTURE_STATES.SUCCESS) return fixture;
  return credentials.amapWebKey || credentials.amapSdkKey
    ? AMAP_FIXTURE_STATES.SUCCESS
    : AMAP_FIXTURE_STATES.NO_KEY;
}

export function resolveCommute(config = {}, plan = null) {
  const override = plan?.commuteOverride;
  return override?.enabled ? {
    enabled: true,
    origin: override.origin || '',
    originAddress: override.originAddress || '',
    destination: override.destination || '',
    destinationAddress: override.destinationAddress || '',
    selectedTransport: override.selectedTransport || 'driving',
  } : {
    enabled: false,
    origin: config.origin || '',
    originAddress: config.originAddress || '',
    destination: config.destination || '',
    destinationAddress: config.destinationAddress || '',
    selectedTransport: config.selectedTransport || 'driving',
  };
}

export const DEFAULT_WEATHER_BUFFERS = Object.freeze({
  [DAY_KINDS.WORKDAY]: Object.freeze([10, 20, 30]),
  [DAY_KINDS.WEEKEND]: Object.freeze([5, 10, 20]),
  [DAY_KINDS.STATUTORY_HOLIDAY]: Object.freeze([10, 15, 25]),
});

const MAX_MINUTES_IN_DAY = 24 * 60 - 1;

export function toMinutes(time) {
  if (typeof time !== 'string') throw new TypeError('Local time must be a string in HH:mm format');
  const match = /^(\d{2}):(\d{2})$/.exec(time);
  if (!match) throw new TypeError(`Invalid local time: ${time}`);
  const hours = Number(match[1]);
  const minutes = Number(match[2]);
  if (hours > 23 || minutes > 59) throw new RangeError(`Invalid local time: ${time}`);
  return hours * 60 + minutes;
}

export function toTime(totalMinutes) {
  if (!Number.isInteger(totalMinutes) || totalMinutes < 0 || totalMinutes > MAX_MINUTES_IN_DAY) {
    throw new RangeError(`Local minute must be an integer from 0 to ${MAX_MINUTES_IN_DAY}: ${totalMinutes}`);
  }
  return `${String(Math.floor(totalMinutes / 60)).padStart(2, '0')}:${String(totalMinutes % 60).padStart(2, '0')}`;
}

/**
 * Converts a signed minute offset relative to an evaluation target date into a
 * local clock time and its day offset. This is the safe counterpart to toTime
 * for cross-midnight timeline values.
 */
export function dayTimeFromMinutes(minutes) {
  if (!Number.isInteger(minutes)) throw new RangeError(`Signed minute must be an integer: ${minutes}`);
  const dayOffset = Math.floor(minutes / (MAX_MINUTES_IN_DAY + 1));
  const minuteOfDay = ((minutes % (MAX_MINUTES_IN_DAY + 1)) + (MAX_MINUTES_IN_DAY + 1)) % (MAX_MINUTES_IN_DAY + 1);
  return { time: toTime(minuteOfDay), dayOffset };
}

function assertMinutes(name, value, max) {
  if (!Number.isInteger(value) || value < 0 || value > max) {
    throw new RangeError(`${name} must be an integer from 0 to ${max}: ${value}`);
  }
}

function assertDayOffset(name, value) {
  if (!Number.isInteger(value) || value < -1 || value > 0) {
    throw new RangeError(`${name} must be -1 or 0: ${value}`);
  }
}

function timeFromSignedMinutes(totalMinutes) {
  const { time: wake, dayOffset: wakeDayOffset } = dayTimeFromMinutes(totalMinutes);
  return { wake, wakeMinutes: toMinutes(wake), wakeDayOffset };
}

function assertDayKind(kind) {
  if (!Object.values(DAY_KINDS).includes(kind)) throw new RangeError(`Unknown day kind: ${kind}`);
}

function assertIsoDate(date) {
  if (typeof date !== 'string') throw new TypeError('Date must be an ISO YYYY-MM-DD string');
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) throw new TypeError(`Invalid ISO date: ${date}`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const isLeapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, isLeapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > daysInMonth[month - 1]) {
    throw new RangeError(`Invalid calendar date: ${date}`);
  }
  return { year, month, day };
}

function weekdayFor(date) {
  const { year, month, day } = assertIsoDate(date);
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay();
}

export function validateWeatherBuffers(buffers) {
  if (!buffers || typeof buffers !== 'object') throw new TypeError('Weather buffers must be an object');
  for (const kind of Object.values(DAY_KINDS)) {
    const profile = buffers[kind];
    if (!Array.isArray(profile) || profile.length !== 3) {
      throw new TypeError(`${kind} weather buffer must contain three severity values`);
    }
    profile.forEach((minutes) => assertMinutes(`${kind} weather buffer`, minutes, 60));
  }
  return buffers;
}

/**
 * Returns the candidate early wake time. An unsuccessful evaluation never
 * changes an already armed temporary alarm; without one, it leaves the normal
 * system-clock time untouched.
 */
export function calculateEarlyWake({
  defaultWake,
  arrivalTime,
  commuteMinutes,
  preparationMinutes,
  weatherBufferMinutes,
  maxAdvanceMinutes,
  existingTempWake = null,
  existingTempWakeDayOffset = 0,
  evaluationSucceeded = true,
}) {
  const defaultWakeMinutes = toMinutes(defaultWake);
  const existingWakeMinutes = existingTempWake === null ? null : toMinutes(existingTempWake);
  if (existingTempWake !== null) assertDayOffset('existingTempWakeDayOffset', existingTempWakeDayOffset);
  const existingWakeAbsolute = existingWakeMinutes === null
    ? null
    : existingTempWakeDayOffset * (MAX_MINUTES_IN_DAY + 1) + existingWakeMinutes;
  assertMinutes('commuteMinutes', commuteMinutes, MAX_MINUTES_IN_DAY);
  assertMinutes('preparationMinutes', preparationMinutes, 240);
  assertMinutes('weatherBufferMinutes', weatherBufferMinutes, 60);
  assertMinutes('maxAdvanceMinutes', maxAdvanceMinutes, 180);
  if (existingWakeAbsolute !== null && existingWakeAbsolute >= defaultWakeMinutes) {
    throw new RangeError('existingTempWake must be earlier than defaultWake');
  }

  if (!evaluationSucceeded) {
    const result = existingWakeAbsolute === null
      ? { wake: defaultWake, wakeMinutes: defaultWakeMinutes, wakeDayOffset: 0 }
      : timeFromSignedMinutes(existingWakeAbsolute);
    return {
      ...result,
      advanceMinutes: Math.max(0, defaultWakeMinutes - (existingWakeAbsolute ?? defaultWakeMinutes)),
      earlyAlarmCreated: existingWakeAbsolute !== null,
      insufficientAdvance: false,
      preservedExistingTempWake: existingWakeAbsolute !== null,
      reason: 'evaluation_failed',
    };
  }

  const calculated = toMinutes(arrivalTime) - commuteMinutes - preparationMinutes - weatherBufferMinutes;
  const earliestAllowed = defaultWakeMinutes - maxAdvanceMinutes;
  const recommendedWake = Math.min(defaultWakeMinutes, Math.max(earliestAllowed, calculated));
  const wake = existingWakeAbsolute === null
    ? recommendedWake
    : Math.min(existingWakeAbsolute, recommendedWake);

  return {
    ...timeFromSignedMinutes(wake),
    advanceMinutes: defaultWakeMinutes - wake,
    earlyAlarmCreated: wake < defaultWakeMinutes,
    insufficientAdvance: calculated < earliestAllowed,
    preservedExistingTempWake: existingWakeAbsolute !== null && wake === existingWakeAbsolute,
    reason: wake < defaultWakeMinutes ? 'advance_required' : 'no_advance_required',
  };
}

/** @deprecated Use calculateEarlyWake. */
export const calculateWake = calculateEarlyWake;

/**
 * Calendar data is optional in the prototype. When it is unavailable, use the
 * ISO weekday and surface the fallback state to the UI.
 */
export function resolveDayKind(date, calendarEntry) {
  const weekday = weekdayFor(date);
  if (calendarEntry?.kind) {
    assertDayKind(calendarEntry.kind);
    return {
      kind: calendarEntry.kind,
      isWorkday: calendarEntry.kind === DAY_KINDS.WORKDAY,
      source: calendarEntry.source ?? 'calendar',
      warning: null,
    };
  }

  if (calendarEntry && typeof calendarEntry.isOffDay === 'boolean') {
    return {
      kind: calendarEntry.isOffDay ? DAY_KINDS.STATUTORY_HOLIDAY : DAY_KINDS.WORKDAY,
      isWorkday: !calendarEntry.isOffDay,
      source: calendarEntry.source ?? 'calendar',
      warning: null,
    };
  }
  if (calendarEntry !== undefined && calendarEntry !== null) {
    throw new TypeError('Calendar entry must provide kind or boolean isOffDay');
  }

  return {
    kind: weekday === 0 || weekday === 6 ? DAY_KINDS.WEEKEND : DAY_KINDS.WORKDAY,
    isWorkday: weekday !== 0 && weekday !== 6,
    source: 'weekdayFallback',
    warning: '节假日数据不可用，已按星期判定。',
  };
}

/**
 * Keeps the calendar date type separate from whether this date runs a plan.
 * A single-day overtime override can make a statutory holiday a workday while
 * still selecting the statutory-holiday buffer profile.
 */
export function resolveDateRule({ date, calendarEntry, workdayOverride = null }) {
  if (workdayOverride !== null && workdayOverride !== 'WORKDAY' && workdayOverride !== 'HOLIDAY') {
    throw new RangeError(`Unknown workday override: ${workdayOverride}`);
  }
  const classified = resolveDayKind(date, calendarEntry);
  return {
    ...classified,
    dayKind: classified.kind,
    workdayOverride,
    isWorkday: workdayOverride === 'WORKDAY' ? true : workdayOverride === 'HOLIDAY' ? false : classified.isWorkday,
    source: workdayOverride === null ? classified.source : 'manualOverride',
    classificationSource: classified.source,
  };
}

/**
 * A date uses exactly one day-kind buffer group; values never accumulate across
 * workday, weekend, and statutory-holiday rules.
 */
export function weatherBufferFor({ dayKind, severity, buffers = DEFAULT_WEATHER_BUFFERS }) {
  validateWeatherBuffers(buffers);
  assertDayKind(dayKind);
  const group = buffers[dayKind];
  if (!Number.isInteger(severity) || severity < 0 || severity > 3) {
    throw new RangeError(`Unknown weather severity: ${severity}`);
  }
  return severity === 0 ? 0 : group[severity - 1];
}

export function weatherBufferForDateRule({ dateRule, severity, buffers = DEFAULT_WEATHER_BUFFERS }) {
  if (!dateRule || typeof dateRule !== 'object') throw new TypeError('dateRule is required');
  return weatherBufferFor({ dayKind: dateRule.dayKind ?? dateRule.kind, severity, buffers });
}

export function saveWeatherBufferProfile(state, dayKind, profile) {
  assertDayKind(dayKind);
  if (!Array.isArray(profile) || profile.length !== 3) throw new TypeError('Weather buffer profile must contain three values');
  profile.forEach((minutes) => assertMinutes('Weather buffer', minutes, 60));
  const weatherBuffers = {
    ...state.weatherBuffers,
    [dayKind]: [...profile],
  };
  validateWeatherBuffers(weatherBuffers);
  return { ...state, weatherBuffers };
}

export function enableOneDayOvertime(state, override) {
  if (!override || typeof override !== 'object') throw new TypeError('One-day overtime override is required');
  const dateRule = resolveDateRule({
    date: override.date,
    calendarEntry: override.calendarEntry,
    workdayOverride: 'WORKDAY',
  });
  const overrides = { ...(state.oneDayOverrides ?? {}) };
  overrides[override.date] = {
    ...override,
    bufferDayKind: dateRule.dayKind,
    workdayOverride: 'WORKDAY',
    enabled: true,
  };
  return { ...state, oneDayOverrides: overrides };
}

export function undoOneDayOvertime(state, date) {
  assertIsoDate(date);
  const overrides = { ...(state.oneDayOverrides ?? {}) };
  delete overrides[date];
  return { ...state, oneDayOverrides: overrides };
}

export function createDefaultState() {
  return {
    activeTab: 'today',
    currentScreen: 'today',
    selectedTransport: 'driving',
    selectedDate: todayIso(),
    restPlanEnabled: false,
    // Kept only so legacy visual states can render during the Android
    // transition. New alarm flows use alarmPlans below.
    recurringPlan: { enabled: false, defaultWake: '06:00', arrivalTime: '09:00', preparationMinutes: 30, maxAdvanceMinutes: 60 },
    alarmPlans: [],
    alarmEvents: [],
    dateOverrides: {},
    weatherBuffers: structuredClone(DEFAULT_WEATHER_BUFFERS),
    oneDayOverrides: {},
    preferences: {
      vibration: true,
      snoozeMinutes: 10,
      departureReminderMinutes: 10,
    },
    credentials: {
      amapWebKey: '',
      amapSdkKey: '',
      caiyunAppKey: '',
      caiyunAppSecret: '',
    },
    amapConsent: 'pending',
  };
}

/**
 * Browser storage holds ordinary demo settings only. Credential text remains
 * process-memory-only and is intentionally omitted from the snapshot.
 */
export function persistentSettingsSnapshot({ credentials, ...settings }) {
  return structuredClone(settings);
}

export function persistSettings(storage, key, state) {
  storage.setItem(key, JSON.stringify(persistentSettingsSnapshot(state)));
}

export function loadSettings(storage, key, defaults = createDefaultState()) {
  const serialized = storage.getItem(key);
  if (!serialized) return defaults;
  try {
    const saved = JSON.parse(serialized);
    return {
      ...defaults,
      ...saved,
      preferences: { ...defaults.preferences, ...saved.preferences },
      recurringPlan: { ...defaults.recurringPlan, ...saved.recurringPlan },
      alarmPlans: Array.isArray(saved.alarmPlans) ? saved.alarmPlans.map(normalizeAlarmPlan) : [],
      alarmEvents: Array.isArray(saved.alarmEvents) ? saved.alarmEvents : [],
      dateOverrides: saved.dateOverrides && typeof saved.dateOverrides === 'object' ? saved.dateOverrides : {},
      credentials: defaults.credentials,
    };
  } catch {
    return defaults;
  }
}

/** Local prototype alarm domain. It intentionally contains no Android API calls. */
export const REPEAT_KINDS = Object.freeze({ ONCE: 'once', WEEKLY: 'weekly', WORKDAYS: 'workdays' });

export function todayIso(now = new Date()) {
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

export function normalizeAlarmPlan(plan = {}) {
  const repeat = plan.repeat && typeof plan.repeat === 'object' ? plan.repeat : { kind: REPEAT_KINDS.ONCE, date: todayIso() };
  const kind = Object.values(REPEAT_KINDS).includes(repeat.kind) ? repeat.kind : REPEAT_KINDS.ONCE;
  const weekdays = [...new Set((repeat.weekdays || []).map(Number).filter(day => Number.isInteger(day) && day >= 1 && day <= 7))].sort();
  const normalized = {
    id: typeof plan.id === 'string' && plan.id ? plan.id : `alarm-${Date.now().toString(36)}`,
    name: String(plan.name || '闹钟').trim() || '闹钟',
    time: typeof plan.time === 'string' ? plan.time : '06:00',
    enabled: Boolean(plan.enabled),
    scheduleStatus: ['pendingPermission', 'registered', 'failed', 'completed'].includes(plan.scheduleStatus) ? plan.scheduleStatus : 'pendingPermission',
    repeat: kind === REPEAT_KINDS.ONCE
      ? { kind, date: typeof repeat.date === 'string' ? repeat.date : todayIso() }
      : kind === REPEAT_KINDS.WEEKLY
        ? { kind, weekdays }
        : { kind },
    ringtone: String(plan.ringtone || '晨光'),
    vibration: plan.vibration !== false,
    snoozeMinutes: Number.isInteger(plan.snoozeMinutes) ? plan.snoozeMinutes : 10,
    zoneId: String(plan.zoneId || Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai'),
    createdAt: plan.createdAt || new Date().toISOString(),
    updatedAt: plan.updatedAt || new Date().toISOString(),
    commuteOverride: plan.commuteOverride?.enabled ? {
      enabled: true,
      origin: String(plan.commuteOverride.origin || ''),
      originAddress: String(plan.commuteOverride.originAddress || ''),
      destination: String(plan.commuteOverride.destination || ''),
      destinationAddress: String(plan.commuteOverride.destinationAddress || ''),
      selectedTransport: String(plan.commuteOverride.selectedTransport || 'driving'),
    } : { enabled: false },
  };
  toMinutes(normalized.time);
  if (normalized.repeat.kind === REPEAT_KINDS.ONCE) assertIsoDate(normalized.repeat.date);
  if (normalized.repeat.kind === REPEAT_KINDS.WEEKLY && !normalized.repeat.weekdays.length) {
    throw new RangeError('Weekly alarm requires at least one weekday');
  }
  assertMinutes('snoozeMinutes', normalized.snoozeMinutes, 30);
  if (normalized.snoozeMinutes < 1) throw new RangeError('snoozeMinutes must be at least 1');
  return normalized;
}

export function defaultAlarmDraft(now = new Date()) {
  const date = todayIso(now);
  const candidate = new Date(`${date}T06:00:00`);
  const nextDate = candidate.getTime() <= now.getTime()
    ? todayIso(new Date(now.getTime() + 86_400_000))
    : date;
  return normalizeAlarmPlan({ name: '闹钟', time: '06:00', repeat: { kind: REPEAT_KINDS.ONCE, date: nextDate }, enabled: true });
}

export function validateAlarmPlan(plan, { now = new Date() } = {}) {
  const normalized = normalizeAlarmPlan(plan);
  if (normalized.repeat.kind === REPEAT_KINDS.ONCE && normalized.enabled) {
    const instant = new Date(`${normalized.repeat.date}T${normalized.time}:00`);
    if (Number.isNaN(instant.getTime()) || instant.getTime() <= now.getTime()) {
      throw new RangeError('Single alarm must be scheduled in the future');
    }
  }
  return normalized;
}

function weekdayNumber(date) {
  const jsDay = new Date(`${date}T12:00:00`).getDay();
  return jsDay === 0 ? 7 : jsDay;
}

function addDays(date, days) {
  const result = new Date(`${date}T12:00:00`);
  result.setDate(result.getDate() + days);
  return todayIso(result);
}

/** Returns the next local occurrence without registering a system alarm. */
export function nextAlarmOccurrence(plan, { now = new Date(), isWorkday = date => weekdayNumber(date) <= 5, override = null } = {}) {
  const item = normalizeAlarmPlan(plan);
  if (!item.enabled || item.scheduleStatus === 'completed') return null;
  if (item.repeat.kind === REPEAT_KINDS.ONCE) {
    const instant = new Date(`${item.repeat.date}T${item.time}:00`);
    return instant.getTime() > now.getTime() ? { date: item.repeat.date, time: item.time } : null;
  }
  for (let offset = 0; offset <= 370; offset += 1) {
    const date = addDays(todayIso(now), offset);
    const occurrence = new Date(`${date}T${item.time}:00`);
    if (occurrence.getTime() <= now.getTime()) continue;
    const overridden = override?.[`${item.id}:${date}`];
    if (overridden?.enabled === false) continue;
    if (overridden?.time) return { date, time: overridden.time, overridden: true };
    if (item.repeat.kind === REPEAT_KINDS.WEEKLY && item.repeat.weekdays.includes(weekdayNumber(date))) return { date, time: item.time };
    if (item.repeat.kind === REPEAT_KINDS.WORKDAYS && isWorkday(date)) return { date, time: item.time };
  }
  return null;
}

export function repeatLabel(plan) {
  const item = normalizeAlarmPlan(plan);
  if (item.repeat.kind === REPEAT_KINDS.ONCE) return `${item.repeat.date} 单次`;
  if (item.repeat.kind === REPEAT_KINDS.WORKDAYS) return '工作日';
  return item.repeat.weekdays.map(day => ['一', '二', '三', '四', '五', '六', '日'][day - 1]).join('、');
}
