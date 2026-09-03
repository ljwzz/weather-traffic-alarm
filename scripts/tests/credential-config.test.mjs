import assert from 'node:assert/strict';
import test from 'node:test';
import { CredentialConfigError, readCredentialConfig } from '../lib/credential-config.mjs';

function memoryFs(files) {
  return {
    async readFile(path) {
      if (!(path in files)) {
        const error = new Error('missing');
        error.code = 'ENOENT';
        throw error;
      }
      return Buffer.from(files[path], 'utf8');
    },
  };
}

test('reads Vite-style base configuration and lets .env.local override it', async () => {
  const config = await readCredentialConfig({
    rootDir: '/repo',
    fs: memoryFs({
      '/repo/.env': '# comment\nAMAP_WEB_KEY="base web"\nAMAP_SDK_KEY=base-sdk # comment\nCAIYUN_APP_KEY=\nCAIYUN_APP_SECRET=base-secret\n',
      '/repo/.env.local': "AMAP_WEB_KEY='local web'\nCAIYUN_APP_SECRET=local-secret\n",
    }),
  });
  assert.deepEqual(config, {
    amapWebKey: 'local web',
    amapSdkKey: 'base-sdk',
    caiyunAppKey: '',
    caiyunSecret: 'local-secret',
  });
});

test('allows missing keys and blank values as an explicit complete clear', async () => {
  const config = await readCredentialConfig({
    rootDir: '/repo',
    fs: memoryFs({ '/repo/.env': 'AMAP_WEB_KEY=   \n' }),
  });
  assert.deepEqual(config, {
    amapWebKey: '',
    amapSdkKey: '',
    caiyunAppKey: '',
    caiyunSecret: '',
  });
});

test('rejects a missing root .env without exposing a path or value', async () => {
  await assert.rejects(
    readCredentialConfig({ rootDir: '/repo', fs: memoryFs({}) }),
    (error) => error instanceof CredentialConfigError && error.code === 'ENV_FILE_MISSING',
  );
});

test('rejects malformed lines, unknown fields, unterminated quotes, and controls', async () => {
  for (const source of [
    'AMAP_WEB_KEY=value\ninvalid line\n',
    'UNRELATED_KEY=value\n',
    'AMAP_WEB_KEY="unterminated\n',
    'AMAP_WEB_KEY="line\nnext"\n',
    'AMAP_WEB_KEY="first\\"second"\n',
    'AMAP_WEB_KEY="has\tcontrol"\n',
  ]) {
    await assert.rejects(
      readCredentialConfig({ rootDir: '/repo', fs: memoryFs({ '/repo/.env': source }) }),
      (error) => error instanceof CredentialConfigError
        && ['ENV_FORMAT_INVALID', 'ENV_VALUE_INVALID'].includes(error.code),
    );
  }
});
