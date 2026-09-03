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

/** Deterministic, network-free weather-provider states for the prototype. */
export const CAIYUN_FIXTURE_STATES = Object.freeze({
  LOADING: 'loading',
  SUCCESS: 'success',
  CACHED: 'cached',
  ERROR: 'error',
});

/**
 * Session-only outcomes for the automatic-evaluation prototype. They model
 * the handoff states without registering an Android alarm or calling either
 * provider.
 */
export const EVALUATION_FIXTURE_STATES = Object.freeze({
  PENDING: 'pending',
  RUNNING: 'running',
  ADVANCED: 'advanced',
  NO_ADVANCE: 'no-advance',
  RETRY: 'retry',
  DEADLINE: 'deadline',
  EXPIRED: 'expired',
});

const EVALUATION_ROUTE_MINUTES = Object.freeze({
  driving: Object.freeze([47, 52, 58]),
  transit: Object.freeze([47, 52, 58]),
  bicycling: Object.freeze([35, 38, 41]),
  'electric-bicycle': Object.freeze([29, 33, 37]),
  walking: Object.freeze([60, 64, 68]),
});

function assertEvaluationFixtureState(fixture) {
  if (!Object.values(EVALUATION_FIXTURE_STATES).includes(fixture)) {
    throw new RangeError(`Unknown evaluation fixture state: ${fixture}`);
  }
  return fixture;
}

export function caiyunFixtureState(fixture = CAIYUN_FIXTURE_STATES.SUCCESS) {
  if (!Object.values(CAIYUN_FIXTURE_STATES).includes(fixture)) {
    throw new RangeError(`Unknown Caiyun fixture state: ${fixture}`);
  }
  return fixture;
}

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

function routeMinutesForEvaluation(transport, selectedRouteIndex) {
  const routes = EVALUATION_ROUTE_MINUTES[transport] || EVALUATION_ROUTE_MINUTES.driving;
  const index = Number.isInteger(selectedRouteIndex) && selectedRouteIndex >= 0 && selectedRouteIndex < routes.length
    ? selectedRouteIndex
    : 0;
  return routes[index];
}

/**
 * Builds a deterministic assessment record for the prototype UI. The input
 * path is deliberately explicit so the rendered reason can show route,
 * weather and day-kind values separately from the scheduling decision.
 */
export function createEvaluationFixture({
  fixture = EVALUATION_FIXTURE_STATES.PENDING,
  plan = { id:'fixture-work', name:'上班闹钟（fixture）', time:'07:30', arrivalTime:'09:00', preparationMinutes:30, maxAdvanceMinutes:60 },
  targetDate = '2026-09-04',
  transport = 'driving',
  selectedRouteIndex = 0,
} = {}) {
  assertEvaluationFixtureState(fixture);
  const baseWake = plan.time || '07:30';
  const routeMinutes = routeMinutesForEvaluation(transport, selectedRouteIndex);
  const inputs = {
    targetDate,
    planId: plan.id || 'fixture-work',
    planName: plan.name || '上班闹钟（fixture）',
    baseWake,
    route: { transport, minutes: routeMinutes, source:'高德路线 fixture' },
    weather: { condition:'小雨', severity:3, bufferMinutes:30, source:'彩云天气 fixture', observedAt:'20:45' },
    dayRule: { kind:DAY_KINDS.WORKDAY, label:'工作日', source:'星期规则 fixture' },
    arrivalTime: plan.arrivalTime || '09:00',
    preparationMinutes: Number.isInteger(plan.preparationMinutes) ? plan.preparationMinutes : 30,
    maxAdvanceMinutes: Number.isInteger(plan.maxAdvanceMinutes) ? plan.maxAdvanceMinutes : 60,
  };
  const early = calculateEarlyWake({
    defaultWake: inputs.baseWake,
    arrivalTime: inputs.arrivalTime,
    commuteMinutes: inputs.route.minutes,
    preparationMinutes: inputs.preparationMinutes,
    weatherBufferMinutes: inputs.weather.bufferMinutes,
    maxAdvanceMinutes: inputs.maxAdvanceMinutes,
  });
  const common = { fixture, inputs, evaluatedAt:'2026-09-03 20:45', retryCount:0, expiresAt:'2026-09-04 07:18' };
  if (fixture === EVALUATION_FIXTURE_STATES.PENDING) {
    return { ...common, state:'pending', title:'待评估', detail:'等待次日评估窗口；基础闹钟保持 07:30。', decision:'not_started', schedule:{ baseWake, earlyWake:null, action:'none' } };
  }
  if (fixture === EVALUATION_FIXTURE_STATES.RUNNING) {
    return { ...common, state:'running', title:'评估中', detail:'正在汇总路线、天气和工作日规则；尚未创建提前提醒。', decision:'collecting_inputs', schedule:{ baseWake, earlyWake:null, action:'none' } };
  }
  if (fixture === EVALUATION_FIXTURE_STATES.ADVANCED) {
    return { ...common, state:'advanced', title:`提前 ${early.advanceMinutes} 分钟`, detail:'已创建独立提前提醒；基础闹钟保持 07:30。', decision:'advance_required', schedule:{ baseWake, earlyWake:early.wake, action:'create_early_reminder', capped:early.insufficientAdvance } };
  }
  if (fixture === EVALUATION_FIXTURE_STATES.NO_ADVANCE) {
    return { ...common, state:'no-advance', title:'无需提前', detail:'路线与天气结果满足到岗时间；不创建额外提醒。', decision:'no_advance_required', schedule:{ baseWake, earlyWake:null, action:'none' }, inputs:{ ...inputs, route:{ ...inputs.route, minutes:30 }, weather:{ ...inputs.weather, condition:'晴', severity:1, bufferMinutes:10 } } };
  }
  if (fixture === EVALUATION_FIXTURE_STATES.RETRY) {
    return { ...common, state:'retry', title:'失败，等待重试', detail:'天气结果暂不可用；将在截止前重试，已存在的提前提醒不变。', decision:'retry_scheduled', retryCount:1, retryAt:'2026-09-03 21:00', schedule:{ baseWake, earlyWake:early.wake, action:'preserve_early_reminder' } };
  }
  if (fixture === EVALUATION_FIXTURE_STATES.DEADLINE) {
    return { ...common, state:'deadline', title:'已过评估截止', detail:'未在截止前获得可用结果；不新增或调整提前提醒。', decision:'deadline_passed', retryCount:2, schedule:{ baseWake, earlyWake:null, action:'none' } };
  }
  return { ...common, state:'expired', title:'结果已过期', detail:'目标日期已过去；该结果仅保留在决策记录中，不能用于新的调度。', decision:'expired_result', evaluatedAt:'2026-09-02 20:45', expiresAt:'2026-09-03 07:18', schedule:{ baseWake, earlyWake:null, action:'none' } };
}

export function evaluationFixtureHistory(plan) {
  return [
    EVALUATION_FIXTURE_STATES.ADVANCED,
    EVALUATION_FIXTURE_STATES.NO_ADVANCE,
    EVALUATION_FIXTURE_STATES.RETRY,
    EVALUATION_FIXTURE_STATES.DEADLINE,
    EVALUATION_FIXTURE_STATES.EXPIRED,
  ].map((fixture, index) => ({
    ...createEvaluationFixture({ fixture, plan, targetDate:`2026-09-0${4 - Math.min(index, 3)}` }),
    id:`evaluation-${fixture}`,
  }));
}

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
    arrivalTime: typeof plan.arrivalTime === 'string' ? plan.arrivalTime : '09:00',
    preparationMinutes: Number.isInteger(plan.preparationMinutes) ? plan.preparationMinutes : 30,
    maxAdvanceMinutes: Number.isInteger(plan.maxAdvanceMinutes) ? plan.maxAdvanceMinutes : 60,
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
  toMinutes(normalized.arrivalTime);
  assertMinutes('preparationMinutes', normalized.preparationMinutes, 240);
  assertMinutes('maxAdvanceMinutes', normalized.maxAdvanceMinutes, 180);
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
