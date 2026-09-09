import assert from 'node:assert/strict';
import test from 'node:test';
import { createAlarmScreens } from '../screens-alarm.mjs';
import { createEvaluationFixture, decisionRecordFromEvaluation, EVALUATION_FIXTURE_STATES } from '../state.mjs';

const action = (label, route, extra = '') => `<button data-route="${route}" ${extra}>${label}</button>`;
const event = (label, name, value = '') => `<button data-action="${name}" data-value="${value}">${label}</button>`;

function render(record, route = 'why') {
  return createAlarmScreens({
    action,
    overlayAction:event,
    asset:() => '',
    state:{ config:{ alarmPlans:[] }, runtime:{ selectedDecision:record, selectedOccurrenceId:record?.occurrence?.id || null, evaluationHistory:record ? [record] : [] } },
  })[route]();
}

test('decision detail uses its saved snapshot and keeps assessment, application and occurrence separate', () => {
  const plan = { id:'plan-a', name:'原计划', time:'07:30', revision:'r2' };
  const record = decisionRecordFromEvaluation(
    createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.ADVANCED, plan }),
    plan,
    { decisionId:'decision-a', occurrence:{ id:'advance-a', kind:'ADVANCE', state:'RINGING' } },
  );
  plan.name = '后来编辑';

  const html = render(record);
  assert.match(html, /原计划/);
  assert.match(html, /评估结果/);
  assert.match(html, /调度应用结果/);
  assert.match(html, /实例当前状态/);
  assert.match(html, /实际注册/);
  assert.doesNotMatch(html, /后来编辑/);
});

test('failure detail renders a real retry time only when the record has one and preserves fallback impact', () => {
  const retry = decisionRecordFromEvaluation(createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.RETRY }), { id:'plan-a', name:'上班', time:'07:30' }, { decisionId:'retry-a' });
  const deadline = decisionRecordFromEvaluation(createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.DEADLINE }), { id:'plan-a', name:'上班', time:'07:30' }, { decisionId:'deadline-a' });

  assert.match(render(retry, 'failure'), /下次重试 2026-09-03 21:00/);
  assert.match(render(retry, 'failure'), /已保留既有提前提醒/);
  assert.doesNotMatch(render(deadline, 'failure'), /下次重试/);
  assert.match(render(deadline, 'failure'), /基础闹钟保持原计划/);
});

test('registration failure and advance-limit details do not claim a successful registration', () => {
  const registration = decisionRecordFromEvaluation(createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.REGISTRATION_FAILED }), { id:'plan-a', name:'上班', time:'07:30' }, { decisionId:'registration-a' });
  const limited = decisionRecordFromEvaluation(createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.INSUFFICIENT_ADVANCE }), { id:'plan-a', name:'上班', time:'07:30' }, { decisionId:'limited-a' });

  assert.match(render(registration, 'failure'), /提前提醒未注册，基础闹钟保持原计划/);
  assert.doesNotMatch(render(registration, 'failure'), /下次重试/);
  assert.match(render(limited, 'failure'), /实际最多提前/);
});

test('missing decision and missing associated fields show an explicit empty state', () => {
  assert.match(render(null), /本次决策不可用/);
  const record = decisionRecordFromEvaluation(createEvaluationFixture({ fixture:EVALUATION_FIXTURE_STATES.EXPIRED }), { id:'plan-a', name:'上班', time:'07:30' }, { decisionId:'expired-a' });
  delete record.expiresAt;
  const html = render(record);
  assert.match(html, /本次未提供/);

  record.inputs = undefined;
  record.schedule = undefined;
  assert.doesNotThrow(() => render(record));
  assert.match(render(record), /本次未提供/);
});
