#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { runCommand } from './lib/process.mjs';
import { parseCliArguments } from './lib/cli.mjs';
import { readCredentialConfig } from './lib/credential-config.mjs';
import { createAdbClient, resolveDeviceSerial } from './lib/adb.mjs';
import { importDebugCredentials, serializeCredentialInput } from './lib/credential-import.mjs';

const ROOT_DIR = dirname(dirname(fileURLToPath(import.meta.url)));
const ANDROID_DIR = join(ROOT_DIR, 'android');

function fail(error) {
  const phase = error?.stage === 'import'
    ? 'Credential import failed (APK installation completed)'
    : 'Debug installation failed';
  process.stderr.write(`${phase}: ${error?.code ?? 'DEBUG_INSTALL_FAILED'}\n`);
  process.exitCode = 1;
}

export async function installDebug({
  argv = process.argv.slice(2),
  rootDir = ROOT_DIR,
  androidDir = ANDROID_DIR,
  adb = createAdbClient(),
  readConfig = readCredentialConfig,
  run = runCommand,
  importer = importDebugCredentials,
  serializeInput = serializeCredentialInput,
} = {}) {
  const { serial: requestedSerial, skipCredentials } = parseCliArguments(argv, { allowSkipCredentials: true });
  const credentials = skipCredentials ? undefined : await readConfig({ rootDir });
  if (!skipCredentials) serializeInput(credentials);
  const serial = await resolveDeviceSerial(adb, requestedSerial);

  const buildTasks = skipCredentials
    ? [':app:assembleDebug', '--console=plain']
    : [':app:assembleDebug', ':app:assembleDebugAndroidTest', '--console=plain'];
  await run(join(androidDir, 'gradlew'), buildTasks, {
    timeoutMs: 10 * 60_000,
    cwd: androidDir,
  });
  await adb.run(serial, ['install', '-r', join(androidDir, 'app/build/outputs/apk/debug/app-debug.apk')], { timeoutMs: 60_000 });
  if (!skipCredentials) {
    await adb.run(serial, ['install', '-r', '-t', join(androidDir, 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')], { timeoutMs: 60_000 });
  }
  process.stdout.write('Debug APK installation completed.\n');
  if (!skipCredentials) {
    try {
      await importer({ adb, serial, credentials });
    } catch (error) {
      const failure = new Error('CREDENTIAL_IMPORT_FAILED');
      failure.code = error?.code ?? 'CREDENTIAL_IMPORT_FAILED';
      failure.stage = 'import';
      throw failure;
    }
  }
  return Object.freeze({ skipCredentials });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  installDebug()
    .then((outcome) => {
      if (!outcome.skipCredentials) process.stdout.write('Debug credential import completed.\n');
    })
    .catch(fail);
}
