import { nextAlarmOccurrence } from './state.mjs';

export const uses24HourClock = () => Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hourCycle?.startsWith('h2') ?? true;

export function shiftWheelTime(time, part, delta, use24Hour = true) {
  const [hour, minute] = time.split(':').map(Number);
  if (part === 'minute') return `${String(hour).padStart(2, '0')}:${String((minute + delta % 60 + 60) % 60).padStart(2, '0')}`;
  if (part === 'period' && !use24Hour) return delta % 2 === 0 ? time : `${String((hour + 12) % 24).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
  const nextHour = use24Hour ? (hour + delta % 24 + 24) % 24 : Math.floor(hour / 12) * 12 + (hour % 12 + delta % 12 + 12) % 12;
  return `${String(nextHour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

export function wheelValues(time, part, use24Hour = true) {
  const [hour, minute] = time.split(':').map(Number);
  return [-2, -1, 0, 1, 2].map(offset => {
    if (part === 'period') return offset % 2 === 0 ? (hour < 12 ? 'AM' : 'PM') : (hour < 12 ? 'PM' : 'AM');
    const value = part === 'minute' ? (minute + offset + 60) % 60 : use24Hour
      ? (hour + offset + 24) % 24 : ((hour % 12 + offset + 12) % 12 || 12);
    return String(value).padStart(2, '0');
  });
}

export function alarmCountdown(plan, now = new Date(), override = null) {
  if (!plan) return '';
  const next = nextAlarmOccurrence(plan, { now, override });
  if (!next) return '暂无下一次响铃';
  const remaining = new Date(`${next.date}T${next.time}:00`).getTime() - now.getTime();
  const minutes = Math.ceil(remaining / 60_000);
  if (minutes <= 0) return '不到 1 分钟后响铃';
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return `${hours ? `${hours} 小时 ` : ''}${rest ? `${rest} 分钟` : ''}后响铃`;
}
