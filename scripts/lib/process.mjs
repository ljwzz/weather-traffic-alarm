import { spawn as nodeSpawn } from 'node:child_process';

export class CommandError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

const OUTPUT_LIMIT = 16 * 1024;

function appendLimited(current, chunk) {
  return (current + chunk).slice(-OUTPUT_LIMIT);
}

export function startCommand(command, args, {
  input,
  timeoutMs,
  onStdout,
  cwd,
  spawn = nodeSpawn,
} = {}) {
  let child;
  try {
    child = spawn(command, args, {
      stdio: ['pipe', 'pipe', 'pipe'],
      windowsHide: true,
      cwd,
    });
  } catch {
    throw new CommandError('COMMAND_START_FAILED');
  }

  let stdout = '';
  let stderr = '';
  let timedOut = false;
  let settled = false;
  let timer;
  let forceKillTimer;
  let resolveDone;
  const done = new Promise((resolve) => { resolveDone = resolve; });
  const finish = (result) => {
    if (settled) return;
    settled = true;
    if (timer) clearTimeout(timer);
    if (forceKillTimer) clearTimeout(forceKillTimer);
    resolveDone({ ...result, stdout, stderr, timedOut });
  };

  child.stdout?.on('data', (data) => {
    const chunk = data.toString('utf8');
    stdout = appendLimited(stdout, chunk);
    onStdout?.(chunk);
  });
  child.stderr?.on('data', (data) => { stderr = appendLimited(stderr, data.toString('utf8')); });
  child.on('error', () => finish({ code: null, signal: null, startFailed: true }));
  child.on('close', (code, signal) => finish({ code, signal, startFailed: false }));

  if (timeoutMs !== undefined) {
    timer = setTimeout(() => {
      timedOut = true;
      stopChild();
    }, timeoutMs);
  }

  child.stdin?.on('error', () => {});
  if (input === undefined) child.stdin?.end();
  else child.stdin?.end(input);

  return {
    done,
    stop: stopChild,
  };

  function stopChild() {
    if (settled) return;
    child.kill('SIGTERM');
    if (!forceKillTimer) forceKillTimer = setTimeout(() => child.kill('SIGKILL'), 2_000);
  }
}

export async function runCommand(command, args, options) {
  const running = startCommand(command, args, options);
  const result = await running.done;
  if (result.timedOut) throw new CommandError('COMMAND_TIMEOUT');
  if (result.startFailed || result.code !== 0) throw new CommandError('COMMAND_FAILED');
  return result;
}
