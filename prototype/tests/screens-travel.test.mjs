import assert from 'node:assert/strict';
import test from 'node:test';
import { createTravelScreens } from '../screens-travel.mjs';

function renderRoute(selectedRouteIndex) {
  return createTravelScreens({
    action: () => '',
    overlayAction: (label, name, value) => `<button data-action="${name}" data-value="${value}">${label}</button>`,
    asset: () => '',
    state: {
      config: { selectedTransport: 'transit' },
      runtime: { amapFixture: 'success', credentials: { amapWebKey: 'fixture' }, selectedRouteIndex },
    },
  }).route();
}

function selectedControls(html, index) {
  return html.match(new RegExp(`<button(?=[^>]*data-action="select-route")(?=[^>]*data-value="${index}")(?=[^>]*aria-pressed="true")[^>]*>`, 'g')) || [];
}

test('route fixture renders three selectable cards and map lines for one selected index', () => {
  const html = renderRoute(2);

  assert.equal((html.match(/data-action="select-route"/g) || []).length, 6);
  assert.equal((html.match(/aria-pressed="true"/g) || []).length, 2);
  assert.equal(selectedControls(html, 2).length, 2);
});

test('route fixture falls back to the first candidate when its selected index is invalid', () => {
  const html = renderRoute(7);

  assert.equal(selectedControls(html, 0).length, 2);
});
