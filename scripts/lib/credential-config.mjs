import { readFile } from 'node:fs/promises';
import { parseEnv } from 'node:util';
import { join } from 'node:path';

export const CREDENTIAL_ENV_KEYS = Object.freeze([
  'AMAP_WEB_KEY',
  'AMAP_SDK_KEY',
  'CAIYUN_APP_KEY',
  'CAIYUN_APP_SECRET',
]);

const CREDENTIAL_FIELDS = Object.freeze([
  ['AMAP_WEB_KEY', 'amapWebKey'],
  ['AMAP_SDK_KEY', 'amapSdkKey'],
  ['CAIYUN_APP_KEY', 'caiyunAppKey'],
  ['CAIYUN_APP_SECRET', 'caiyunSecret'],
]);

export class CredentialConfigError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function hasClosingQuote(value, quote) {
  return value.indexOf(quote, 1);
}

function validateDotenvSyntax(source) {
  const lines = source.split(/\r?\n/u);
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed === '' || trimmed.startsWith('#')) continue;

    const assignment = /^(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/u.exec(trimmed);
    if (!assignment) throw new CredentialConfigError('ENV_FORMAT_INVALID');

    const value = assignment[2];
    if (value.startsWith('"') || value.startsWith("'") || value.startsWith('`')) {
      const closing = hasClosingQuote(value, value[0]);
      if (closing < 0) throw new CredentialConfigError('ENV_FORMAT_INVALID');
      const trailing = value.slice(closing + 1).trim();
      if (trailing !== '' && !trailing.startsWith('#')) {
        throw new CredentialConfigError('ENV_FORMAT_INVALID');
      }
    }
  }
}

function parseCredentialEnv(source) {
  validateDotenvSyntax(source);
  const parsed = parseEnv(source);
  for (const key of Object.keys(parsed)) {
    if (!CREDENTIAL_ENV_KEYS.includes(key)) {
      throw new CredentialConfigError('ENV_FORMAT_INVALID');
    }
  }
  return parsed;
}

function decodeUtf8(contents) {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(contents);
  } catch {
    throw new CredentialConfigError('ENV_FORMAT_INVALID');
  }
}

async function readRequiredEnv(file, fs) {
  try {
    return parseCredentialEnv(decodeUtf8(await fs.readFile(file)));
  } catch (error) {
    if (error?.code === 'ENOENT') throw new CredentialConfigError('ENV_FILE_MISSING');
    throw error;
  }
}

async function readOptionalEnv(file, fs) {
  try {
    return parseCredentialEnv(decodeUtf8(await fs.readFile(file)));
  } catch (error) {
    if (error?.code === 'ENOENT') return {};
    throw error;
  }
}

export function credentialInputFromEnv(values) {
  const input = {};
  for (const [envKey, field] of CREDENTIAL_FIELDS) {
    const value = values[envKey] ?? '';
    if (typeof value !== 'string' || /[\u0000-\u001F\u007F]/u.test(value)) {
      throw new CredentialConfigError('ENV_VALUE_INVALID');
    }
    input[field] = value.trim() === '' ? '' : value;
  }
  return Object.freeze(input);
}

export async function readCredentialConfig({ rootDir, fs = { readFile } }) {
  const base = await readRequiredEnv(join(rootDir, '.env'), fs);
  const local = await readOptionalEnv(join(rootDir, '.env.local'), fs);
  return credentialInputFromEnv({ ...base, ...local });
}
