import { runCommand, startCommand } from './process.mjs';

export class AdbError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

export const DEVICE_QUERY_TIMEOUT_MS = 15_000;

export function adbArguments(serial, commandArguments) {
  return serial === undefined ? commandArguments : ['-s', serial, ...commandArguments];
}

export function createAdbClient({ adbPath = 'adb', run = runCommand, start = startCommand } = {}) {
  return Object.freeze({
    run(serial, commandArguments, options) {
      return run(adbPath, adbArguments(serial, commandArguments), options);
    },
    start(serial, commandArguments, options) {
      return start(adbPath, adbArguments(serial, commandArguments), options);
    },
  });
}

export function parseAttachedDevices(output) {
  return output.split(/\r?\n/u)
    .map((line) => /^([^\s]+)\s+(\S+)\s*$/u.exec(line))
    .filter((match) => match?.[2] === 'device')
    .map((match) => match[1]);
}

export async function resolveDeviceSerial(adb, requestedSerial) {
  let result;
  try {
    result = await adb.run(undefined, ['devices'], { timeoutMs: DEVICE_QUERY_TIMEOUT_MS });
  } catch {
    throw new AdbError('ADB_DEVICE_QUERY_FAILED');
  }
  const devices = parseAttachedDevices(result.stdout);
  if (requestedSerial !== undefined) {
    if (devices.includes(requestedSerial)) return requestedSerial;
    throw new AdbError('ADB_REQUESTED_DEVICE_UNAVAILABLE');
  }
  if (devices.length === 1) return devices[0];
  if (devices.length === 0) throw new AdbError('ADB_NO_DEVICE');
  throw new AdbError('ADB_MULTIPLE_DEVICES');
}
