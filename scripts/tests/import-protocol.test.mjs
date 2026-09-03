import assert from 'node:assert/strict';
import test from 'node:test';
import { parseCliArguments } from '../lib/cli.mjs';
import { parseAttachedDevices, resolveDeviceSerial } from '../lib/adb.mjs';
import {
  APP_PACKAGE,
  INSTRUMENTATION_CLASS,
  INSTRUMENTATION_RUNNER,
  importDebugCredentials,
} from '../lib/credential-import.mjs';
import { installDebug } from '../install-debug.mjs';
import { runImportDebugCredentials } from '../import-debug-credentials.mjs';
import { runCommand } from '../lib/process.mjs';

const UUID = '123e4567-e89b-42d3-a456-426614174000';
const credentials = Object.freeze({
  amapWebKey: 'web-key',
  amapSdkKey: '',
  caiyunAppKey: 'app-key',
  caiyunSecret: 'secret',
});

function successfulResult(stdout = '') {
  return { code: 0, signal: null, timedOut: false, startFailed: false, stdout };
}

function successfulProcess(stdout = '') {
  return {
    done: Promise.resolve(successfulResult(stdout)),
    stop() {},
  };
}

test('CLI accepts only explicit serial and the install-only flag', () => {
  assert.deepEqual(parseCliArguments(['--serial', 'emulator-5554']), {
    serial: 'emulator-5554', skipCredentials: false,
  });
  assert.deepEqual(parseCliArguments(['--skip-credentials'], { allowSkipCredentials: true }), {
    serial: undefined, skipCredentials: true,
  });
  assert.throws(() => parseCliArguments(['--serial', 'bad serial']), { message: 'INVALID_ARGUMENT' });
  assert.throws(() => parseCliArguments(['--skip-credentials']), { message: 'INVALID_ARGUMENT' });
});

test('requires a serial when more than one ADB device is attached', async () => {
  assert.deepEqual(parseAttachedDevices('List of devices attached\none\tdevice\ntwo\tdevice\noffline\toffline\n'), ['one', 'two']);
  const adb = { run: async () => successfulResult('List of devices attached\none\tdevice\ntwo\tdevice\n') };
  await assert.rejects(resolveDeviceSerial(adb), { message: 'ADB_MULTIPLE_DEVICES' });
  assert.equal(await resolveDeviceSerial(adb, 'two'), 'two');
});

test('independent import loads config, selects its device, and delegates the complete input', async () => {
  const calls = [];
  const adb = {
    run: async (_serial, args) => {
      calls.push(args);
      return successfulResult('List of devices attached\nsolo\tdevice\n');
    },
  };
  await runImportDebugCredentials({
    rootDir: '/repo',
    adb,
    readConfig: async ({ rootDir }) => {
      calls.push(['config', rootDir]);
      return credentials;
    },
    importer: async (input) => calls.push(['import', input.serial, input.credentials]),
  });
  assert.deepEqual(calls, [
    ['config', '/repo'],
    ['devices'],
    ['import', 'solo', credentials],
  ]);
});

test('imports only JSON over stdin after the readiness status, then verifies final statuses', async () => {
  const calls = [];
  const finalOutput = [
    'INSTRUMENTATION_STATUS: credentialImportReady=true',
    'INSTRUMENTATION_STATUS: credentialImportSuccess=true',
    'INSTRUMENTATION_STATUS: hasAmapWebKey=true',
    'INSTRUMENTATION_STATUS: hasAmapSdkKey=false',
    'INSTRUMENTATION_STATUS: hasCaiyunAppKey=true',
    'INSTRUMENTATION_STATUS: hasCaiyunSecret=true',
    'INSTRUMENTATION_CODE: -1',
  ].join('\n');
  const adb = {
    run: async (serial, args) => {
      calls.push({ kind: 'run', serial, args });
      return successfulResult();
    },
    start(serial, args, options) {
      calls.push({ kind: 'start', serial, args, input: options.input });
      if (args.includes('instrument')) {
        queueMicrotask(() => options.onStdout('INSTRUMENTATION_STATUS: credentialImportReady=true\n'));
        return successfulProcess(finalOutput);
      }
      return successfulProcess();
    },
  };

  await importDebugCredentials({ adb, serial: 'device-1', credentials, randomUUID: () => UUID });

  const pipeCreator = calls.find((call) => call.kind === 'start' && call.args.join(' ').includes('mkfifo'));
  assert.deepEqual(pipeCreator.args, [
    'shell', '-T', 'run-as', APP_PACKAGE, 'sh', '-c',
    `'umask 077 && mkfifo cache/credential-import-${UUID}.fifo'`,
  ]);
  const instrumentation = calls.find((call) => call.kind === 'start' && call.args.includes('instrument'));
  assert.deepEqual(instrumentation.args, [
    'shell', '-T', 'am', 'instrument', '-w', '-r',
    '-e', 'class', INSTRUMENTATION_CLASS,
    '-e', 'importCredentials', 'true',
    '-e', 'credentialPipe', `credential-import-${UUID}.fifo`,
    INSTRUMENTATION_RUNNER,
  ]);
  const writer = calls.find((call) => call.kind === 'start' && call.args.includes('timeout'));
  assert.deepEqual(writer.args.slice(-5), ['timeout', '30', 'sh', '-c', `'cat > cache/credential-import-${UUID}.fifo'`]);
  assert.equal(writer.input, JSON.stringify(credentials));
  assert.equal(writer.args.join(' ').includes('web-key'), false);
  assert.equal(calls.at(-1).args.at(-1), `cache/credential-import-${UUID}.fifo`);
});

test('skip-credentials never reads config, builds or installs the test APK, or invokes import', async () => {
  const calls = [];
  const adb = {
    run: async (_serial, args) => {
      calls.push(args);
      if (args[0] === 'devices') return successfulResult('List of devices attached\none\tdevice\n');
      return successfulResult();
    },
  };
  await installDebug({
    argv: ['--skip-credentials'],
    rootDir: '/repo',
    androidDir: '/repo/android',
    adb,
    readConfig: async () => { throw new Error('must not read config'); },
    run: async (_command, args, options) => { calls.push(args, options); return successfulResult(); },
    importer: async () => { throw new Error('must not import'); },
  });
  assert.deepEqual(calls[1], [':app:assembleDebug', '--console=plain']);
  assert.deepEqual(calls[2], { timeoutMs: 600_000, cwd: '/repo/android' });
  assert.equal(calls.some((args) => args.includes?.('androidTest')), false);
  assert.equal(calls.some((args) => args.includes?.('-t')), false);
});

test('default installation builds in android, installs both APKs, then imports', async () => {
  const calls = [];
  const adb = {
    run: async (serial, args) => {
      calls.push(['adb', serial, args]);
      if (args[0] === 'devices') return successfulResult('List of devices attached\nsolo\tdevice\n');
      return successfulResult();
    },
  };
  await installDebug({
    rootDir: '/repo',
    androidDir: '/repo/android',
    adb,
    readConfig: async () => { calls.push(['config']); return credentials; },
    serializeInput: () => calls.push(['preflight']),
    run: async (_command, args, options) => calls.push(['gradle', args, options]),
    importer: async ({ serial, credentials: input }) => calls.push(['import', serial, input]),
  });
  assert.deepEqual(calls, [
    ['config'],
    ['preflight'],
    ['adb', undefined, ['devices']],
    ['gradle', [':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain'], { timeoutMs: 600_000, cwd: '/repo/android' }],
    ['adb', 'solo', ['install', '-r', '/repo/android/app/build/outputs/apk/debug/app-debug.apk']],
    ['adb', 'solo', ['install', '-r', '-t', '/repo/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']],
    ['import', 'solo', credentials],
  ]);
});

test('default install validates credentials before querying a device or building', async () => {
  let adbCalled = false;
  await assert.rejects(installDebug({
    rootDir: '/repo',
    adb: { run: async () => { adbCalled = true; return successfulResult(); } },
    readConfig: async () => credentials,
    serializeInput: () => { throw Object.assign(new Error('invalid'), { code: 'CREDENTIAL_INPUT_TOO_LARGE' }); },
  }), { message: 'invalid' });
  assert.equal(adbCalled, false);
});

test('reports an import failure separately from the completed APK installation', async () => {
  let installed = 0;
  await assert.rejects(installDebug({
    argv: [],
    readConfig: async () => credentials,
    adb: { run: async (_serial, args) => {
      if (args[0] === 'install') installed += 1;
      return successfulResult('List of devices attached\nsolo\tdevice\n');
    } },
    run: async () => successfulResult(),
    importer: async () => { throw Object.assign(new Error('private detail'), { code: 'PIPE_WRITE_FAILED' }); },
  }), (error) => error.stage === 'import' && error.code === 'PIPE_WRITE_FAILED'
    && !error.message.includes('private detail'));
  assert.equal(installed, 2);
});

test('writer interruption fails with a fixed error and removes the FIFO', async () => {
  const calls = [];
  const adb = {
    run: async (_serial, args) => {
      calls.push({ kind: 'run', args });
      return successfulResult();
    },
    start(_serial, args, options) {
      calls.push({ kind: 'start', args });
      if (args.includes('instrument')) {
        queueMicrotask(() => options.onStdout('INSTRUMENTATION_STATUS: credentialImportReady=true\n'));
        return successfulProcess();
      }
      if (args.join(' ').includes('mkfifo')) return successfulProcess();
      return { done: Promise.resolve({ ...successfulResult(), code: 1 }), stop() {} };
    },
  };
  await assert.rejects(
    importDebugCredentials({ adb, serial: 'device-1', credentials, randomUUID: () => UUID }),
    { message: 'PIPE_WRITE_FAILED' },
  );
  assert.equal(calls.at(-1).args.at(-1), `cache/credential-import-${UUID}.fifo`);
});

test('does not accept an invalid final instrumentation code or incomplete field status', async () => {
  const validStatuses = [
    'INSTRUMENTATION_STATUS: credentialImportReady=true',
    'INSTRUMENTATION_STATUS: credentialImportSuccess=true',
    'INSTRUMENTATION_STATUS: hasAmapWebKey=true',
    'INSTRUMENTATION_STATUS: hasAmapSdkKey=false',
    'INSTRUMENTATION_STATUS: hasCaiyunAppKey=true',
    'INSTRUMENTATION_STATUS: hasCaiyunSecret=true',
    'INSTRUMENTATION_CODE: -1',
  ];
  for (const [output, expected] of [
    [validStatuses.map((line) => line.replace('-1', '0')).join('\n'), 'INSTRUMENTATION_FAILED'],
    [[...validStatuses, 'INSTRUMENTATION_STATUS_CODE: -2'].join('\n'), 'INSTRUMENTATION_FAILED'],
    [validStatuses.filter((line) => !line.includes('hasCaiyunSecret')).join('\n'), 'IMPORT_STATUS_MISSING'],
  ]) {
    const adb = {
      run: async () => successfulResult(),
      start(_serial, args, options) {
        if (args.join(' ').includes('mkfifo')) return successfulProcess();
        if (args.includes('instrument')) {
          queueMicrotask(() => options.onStdout('INSTRUMENTATION_STATUS: credentialImportReady=true\n'));
          return successfulProcess(output);
        }
        return successfulProcess();
      },
    };
    await assert.rejects(
      importDebugCredentials({ adb, serial: 'device-1', credentials, randomUUID: () => UUID }),
      { message: expected },
    );
  }
});

test('readiness timeout stops instrumentation and cleans its FIFO', async () => {
  let stopped = false;
  let cleaned = false;
  const adb = {
    run: async (_serial, args) => {
      if (args.includes('rm')) cleaned = true;
      return successfulResult();
    },
    start(_serial, args) {
      if (args.join(' ').includes('mkfifo')) return successfulProcess();
      if (args.includes('instrument')) return { done: new Promise(() => {}), stop() { stopped = true; } };
      throw new Error('writer must not start');
    },
  };
  await assert.rejects(
    importDebugCredentials({ adb, serial: 'device-1', credentials, randomUUID: () => UUID, timeoutMs: 10 }),
    { message: 'INSTRUMENTATION_READY_TIMEOUT' },
  );
  assert.equal(stopped, true);
  assert.equal(cleaned, true);
});

test('SIGINT clears the readiness wait timer before cleanup', async () => {
  let stopCount = 0;
  let cleanupCount = 0;
  const adb = {
    run: async (_serial, args) => {
      if (args.includes('rm')) cleanupCount += 1;
      return successfulResult();
    },
    start(_serial, args) {
      if (args.join(' ').includes('mkfifo')) return successfulProcess();
      if (args.includes('instrument')) return { done: new Promise(() => {}), stop() { stopCount += 1; } };
      throw new Error('writer must not start');
    },
  };
  const importing = importDebugCredentials({ adb, serial: 'device-1', credentials, randomUUID: () => UUID, timeoutMs: 25 });
  await new Promise((resolve) => setImmediate(resolve));
  process.emit('SIGINT');
  await assert.rejects(importing, { message: 'IMPORT_INTERRUPTED' });
  await new Promise((resolve) => setTimeout(resolve, 35));
  assert.equal(stopCount, 2);
  assert.equal(cleanupCount, 1);
});

test('real child process receives credentials only on stdin, never as an argument', async () => {
  const program = "let input=''; process.stdin.on('data', c => input += c); process.stdin.on('end', () => process.stdout.write(JSON.stringify({ args: process.argv.slice(1), input })));";
  const payload = JSON.stringify(credentials);
  const result = await runCommand(process.execPath, ['-e', program, 'fixed-argument'], { input: payload });
  const observed = JSON.parse(result.stdout);
  assert.deepEqual(observed.args, ['fixed-argument']);
  assert.equal(observed.input, payload);
});

test('command output retains final protocol status when preceding output exceeds the cap', async () => {
  const result = await runCommand(process.execPath, ['-e', "process.stdout.write('x'.repeat(20000) + 'FINAL_STATUS')"]);
  assert.equal(result.stdout.endsWith('FINAL_STATUS'), true);
  assert.equal(result.stdout.length, 16 * 1024);
});
