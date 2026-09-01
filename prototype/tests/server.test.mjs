import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { createPrototypeServer } from '../server.mjs';

let server;
let base;
before(async () => {
  server = createPrototypeServer();
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
});

test('serves the prototype and local fonts without caching', async () => {
  const page = await fetch(base);
  assert.equal(page.status, 200);
  assert.match(await page.text(), /知途/);
  assert.equal(page.headers.get('cache-control'), 'no-store');
  assert.match(page.headers.get('content-security-policy'), /connect-src 'none'/);
  const font = await fetch(`${base}/assets/fonts/Roboto-Variable.ttf`, { method: 'HEAD' });
  assert.equal(font.status, 200);
  assert.equal(font.headers.get('content-type'), 'font/ttf');
  assert.equal(await font.text(), '');
});

test('malformed paths and outside-root requests do not crash or expose repository files', async () => {
  assert.equal((await fetch(`${base}/%ZZ`)).status, 400);
  assert.equal((await fetch(`${base}/..%2fSPEC.md`)).status, 404);
  assert.equal((await fetch(`${base}/missing.html`)).status, 404);
  assert.equal((await fetch(base)).status, 200);
});

test('does not accept form posts or other state-changing requests', async () => {
  const response = await fetch(base, { method: 'POST', body: 'demo-only' });
  assert.equal(response.status, 405);
  assert.equal(response.headers.get('allow'), 'GET, HEAD');
});
