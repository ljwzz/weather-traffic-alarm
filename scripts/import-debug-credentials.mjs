#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { dirname } from 'node:path';
import { parseCliArguments } from './lib/cli.mjs';
import { readCredentialConfig } from './lib/credential-config.mjs';
import { createAdbClient, resolveDeviceSerial } from './lib/adb.mjs';
import { importDebugCredentials } from './lib/credential-import.mjs';

const ROOT_DIR = dirname(dirname(fileURLToPath(import.meta.url)));

function fail(code) {
  process.stderr.write(`Credential import failed: ${code}\n`);
  process.exitCode = 1;
}

export async function runImportDebugCredentials({
  argv = process.argv.slice(2),
  rootDir = ROOT_DIR,
  adb = createAdbClient(),
  readConfig = readCredentialConfig,
  importer = importDebugCredentials,
} = {}) {
  const { serial: requestedSerial } = parseCliArguments(argv);
  const credentials = await readConfig({ rootDir });
  const serial = await resolveDeviceSerial(adb, requestedSerial);
  await importer({ adb, serial, credentials });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  runImportDebugCredentials()
    .then(() => process.stdout.write('Credential import completed.\n'))
    .catch((error) => fail(error?.code ?? 'CREDENTIAL_IMPORT_FAILED'));
}
