export class CliArgumentError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function validSerial(serial) {
  return typeof serial === 'string'
    && /^[A-Za-z0-9._:-]+$/u.test(serial);
}

export function parseCliArguments(argv, { allowSkipCredentials = false } = {}) {
  let serial;
  let skipCredentials = false;

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--serial') {
      if (serial !== undefined || !validSerial(argv[index + 1])) {
        throw new CliArgumentError('INVALID_ARGUMENT');
      }
      serial = argv[index + 1];
      index += 1;
      continue;
    }
    if (allowSkipCredentials && argument === '--skip-credentials' && !skipCredentials) {
      skipCredentials = true;
      continue;
    }
    throw new CliArgumentError('INVALID_ARGUMENT');
  }

  return Object.freeze({ serial, skipCredentials });
}
