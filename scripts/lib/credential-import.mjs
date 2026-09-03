import { randomUUID as nodeRandomUUID } from 'node:crypto';

export const APP_PACKAGE = 'com.ljwzz.weathertrafficalarm';
export const INSTRUMENTATION_RUNNER = `${APP_PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner`;
export const INSTRUMENTATION_CLASS = `${APP_PACKAGE}.CredentialImportDeviceTest#importFromPipe`;
export const IMPORT_TIMEOUT_MS = 30_000;
export const IMPORT_MAX_BYTES = 8 * 1024;

const STATUS_FIELDS = Object.freeze([
  ['amapWebKey', 'hasAmapWebKey'],
  ['amapSdkKey', 'hasAmapSdkKey'],
  ['caiyunAppKey', 'hasCaiyunAppKey'],
  ['caiyunSecret', 'hasCaiyunSecret'],
]);

export class CredentialImportError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function statusPresent(output, key, value) {
  return new RegExp(`(?:^|\\s)${key}=${value}(?=\\s|$)`, 'u').test(output);
}

export function serializeCredentialInput(credentials) {
  const input = {};
  for (const [field] of STATUS_FIELDS) {
    const value = credentials[field];
    if (typeof value !== 'string' || /[\u0000-\u001F\u007F]/u.test(value)) {
      throw new CredentialImportError('CREDENTIAL_INPUT_INVALID');
    }
    input[field] = value.trim() === '' ? '' : value;
  }
  const json = JSON.stringify(input);
  if (Buffer.byteLength(json, 'utf8') > IMPORT_MAX_BYTES) {
    throw new CredentialImportError('CREDENTIAL_INPUT_TOO_LARGE');
  }
  return json;
}

function pipeName(randomUUID) {
  const uuid = randomUUID();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/iu.test(uuid)) {
    throw new CredentialImportError('PIPE_NAME_INVALID');
  }
  return `credential-import-${uuid}.fifo`;
}

function resultSucceeded(result) {
  return result && !result.timedOut && !result.startFailed && result.code === 0;
}

async function requireSuccessful(resultPromise, errorCode) {
  let result;
  try {
    result = await resultPromise;
  } catch {
    throw new CredentialImportError(errorCode);
  }
  if (!resultSucceeded(result)) throw new CredentialImportError(errorCode);
  return result;
}

function waitForStatus(process, key, value, timeoutMs, timeoutCode, completionCode) {
  let cancel;
  const promise = new Promise((resolve, reject) => {
    let output = '';
    let settled = false;
    const settle = (fn, payload) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(payload);
    };
    const timer = setTimeout(() => {
      process.stop();
      settle(reject, new CredentialImportError(timeoutCode));
    }, timeoutMs);

    process.observe((chunk) => {
      output = (output + chunk).slice(-16 * 1024);
      if (statusPresent(output, key, value)) settle(resolve, undefined);
    });
    process.done.then((result) => {
      if (!settled) settle(reject, new CredentialImportError(completionCode));
    });
    cancel = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
    };
  });
  return { promise, cancel };
}

function waitForCompletion(process, timeoutMs, timeoutCode) {
  let cancel;
  const promise = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      process.stop();
      reject(new CredentialImportError(timeoutCode));
    }, timeoutMs);
    process.done.then((result) => {
      clearTimeout(timer);
      resolve(result);
    });
    cancel = () => clearTimeout(timer);
  });
  return { promise, cancel };
}

function startInstrumentation(adb, serial, pipe, timeoutMs) {
  let observer = () => {};
  let outputBeforeObservation = '';
  const command = adb.start(serial, [
    'shell', '-T', 'am', 'instrument', '-w', '-r',
    '-e', 'class', INSTRUMENTATION_CLASS,
    '-e', 'importCredentials', 'true',
    '-e', 'credentialPipe', pipe,
    INSTRUMENTATION_RUNNER,
  ], {
    timeoutMs: timeoutMs * 3,
    onStdout: (chunk) => {
      outputBeforeObservation = (outputBeforeObservation + chunk).slice(-16 * 1024);
      observer(chunk);
    },
  });
  return {
    ...command,
    observe(callback) {
      observer = callback;
      if (outputBeforeObservation !== '') callback(outputBeforeObservation);
    },
  };
}

function verifyCompletion(result, credentials) {
  if (!resultSucceeded(result)) throw new CredentialImportError('INSTRUMENTATION_FAILED');
  if (/INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS_CODE:\s*-[1-4](?=\s|$)/u.test(result.stdout)) {
    throw new CredentialImportError('INSTRUMENTATION_FAILED');
  }
  if (!/(?:^|\s)INSTRUMENTATION_CODE:\s*-1(?=\s|$)/u.test(result.stdout)) {
    throw new CredentialImportError('INSTRUMENTATION_FAILED');
  }
  if (!statusPresent(result.stdout, 'credentialImportSuccess', 'true')) {
    throw new CredentialImportError('IMPORT_STATUS_MISSING');
  }
  for (const [field, status] of STATUS_FIELDS) {
    const expected = credentials[field].trim() === '' ? 'false' : 'true';
    if (!statusPresent(result.stdout, status, expected)) {
      throw new CredentialImportError('IMPORT_STATUS_MISSING');
    }
  }
}

export async function importDebugCredentials({
  adb,
  serial,
  credentials,
  timeoutMs = IMPORT_TIMEOUT_MS,
  randomUUID = nodeRandomUUID,
}) {
  const json = serializeCredentialInput(credentials);
  const pipe = pipeName(randomUUID);
  let instrumentation;
  let writer;
  let pipeCreationStarted = false;
  let primaryError;
  let rejectInterrupted;
  let stopActiveCommand = () => {};
  const interrupted = new Promise((_, reject) => { rejectInterrupted = reject; });
  const interrupt = () => {
    stopActiveCommand();
    rejectInterrupted(new CredentialImportError('IMPORT_INTERRUPTED'));
  };
  process.once('SIGINT', interrupt);
  process.once('SIGTERM', interrupt);
  const interruptible = (promise) => Promise.race([promise, interrupted]);
  try {
    pipeCreationStarted = true;
    const pipeCreator = adb.start(serial, [
      'shell', '-T', 'run-as', APP_PACKAGE, 'sh', '-c', `'umask 077 && mkfifo cache/${pipe}'`,
    ], { timeoutMs });
    stopActiveCommand = () => pipeCreator.stop();
    await interruptible(requireSuccessful(
      pipeCreator.done,
      'PIPE_CREATE_FAILED',
    ));

    instrumentation = startInstrumentation(adb, serial, pipe, timeoutMs);
    const readiness = waitForStatus(
      instrumentation,
      'credentialImportReady',
      'true',
      timeoutMs,
      'INSTRUMENTATION_READY_TIMEOUT',
      'INSTRUMENTATION_READY_FAILED',
    );
    stopActiveCommand = () => {
      instrumentation?.stop();
      readiness.cancel();
    };
    await interruptible(readiness.promise);

    writer = adb.start(serial, [
      'shell', '-T', 'run-as', APP_PACKAGE, 'timeout', '30', 'sh', '-c', `'cat > cache/${pipe}'`,
    ], { input: json, timeoutMs: timeoutMs + 2_000 });
    stopActiveCommand = () => {
      instrumentation?.stop();
      writer?.stop();
    };
    await interruptible(requireSuccessful(writer.done, 'PIPE_WRITE_FAILED'));

    const completion = waitForCompletion(instrumentation, timeoutMs, 'INSTRUMENTATION_EXIT_TIMEOUT');
    stopActiveCommand = () => {
      instrumentation?.stop();
      completion.cancel();
    };
    const result = await interruptible(requireSuccessful(
      completion.promise,
      'INSTRUMENTATION_EXIT_TIMEOUT',
    ));
    verifyCompletion(result, credentials);
  } catch (error) {
    primaryError = error;
    throw error;
  } finally {
    process.off('SIGINT', interrupt);
    process.off('SIGTERM', interrupt);
    instrumentation?.stop();
    writer?.stop();
    if (pipeCreationStarted) {
      try {
        await requireSuccessful(
          adb.run(serial, ['shell', '-T', 'run-as', APP_PACKAGE, 'rm', '-f', `cache/${pipe}`], { timeoutMs }),
          'PIPE_CLEANUP_FAILED',
        );
      } catch (cleanupError) {
        if (!primaryError) throw cleanupError;
      }
    }
  }
}
