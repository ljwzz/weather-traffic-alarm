import assert from 'node:assert/strict';
import test from 'node:test';
import { createRingingSession, ringSnoozedSession, snoozeRingingSession, stopRingingSession } from '../ringing-state.mjs';
import { createSystemScreens } from '../screens-system.mjs';

function render(route, ringingSession) {
  return createSystemScreens({ asset: () => '', state: { runtime:{ ringingSession } } })[route]();
}

test('ringing renderers keep the full-screen fixture and separate basic wording', () => {
  const basic = render('ringing-basic', createRingingSession('basic'));
  const early = render('ringing', createRingingSession('early'));

  assert.match(basic, /system-screen system-ringing/);
  assert.match(basic, /知途 · 基础闹钟/);
  assert.match(basic, /按计划 07:30 提醒；<br>不依赖天气或路线。/);
  assert.doesNotMatch(basic, /今天路上有小雨/);
  assert.match(early, /知途 · 提前闹钟/);
  assert.match(early, /通勤约 47 分钟，建议 08:03 出发。<br>慢慢准备，也能准时到达。/);
  assert.match(early, /贪睡 10 分钟/);
});

test('result states retain the ringing screen and display stop or next simulated occurrence', () => {
  const stopped = render('ringing-basic', stopRingingSession(createRingingSession('basic')));
  const snoozed = render('ringing', snoozeRingingSession(createRingingSession('early')));

  assert.match(stopped, /本次响铃已停止/);
  assert.match(stopped, /返回闹钟/);
  assert.match(stopped, /重新演示/);
  assert.match(snoozed, /07:28/);
  assert.match(snoozed, /已贪睡 10 分钟/);
  assert.match(snoozed, /下次模拟响铃/);
  assert.match(snoozed, /模拟再次响铃/);
});

test('snoozed renderer calculates the weekday from its child occurrence date', () => {
  const session = snoozeRingingSession(createRingingSession('basic', { date:'2026-09-02', time:'23:55' }));
  const html = render('ringing-basic', session);

  assert.match(html, /9月3日　星期四/);
  assert.match(html, /00:05/);
});

test('a child occurrence has its own snooze reminder copy and white status treatment', () => {
  const child = ringSnoozedSession(snoozeRingingSession(createRingingSession('early')));
  const html = render('ringing', child);

  assert.match(html, /07:28/);
  assert.match(html, /贪睡后再次响铃/);
  assert.match(html, /贪睡提醒/);
  assert.match(html, /本次为贪睡后的模拟提醒。<br>不修改已保存计划或基础闹钟。/);
  assert.doesNotMatch(html, /system-status is-light/);
  assert.doesNotMatch(html, /提前闹钟壁纸/);
  assert.doesNotMatch(html, /system-ringing-rain/);
});
