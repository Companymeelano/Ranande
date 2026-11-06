'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { normalizePlate } = require('../vehicles');
test('canonicalizes Persian, Arabic and ASCII digits and common separators', () => {
  assert.equal(normalizePlate('۱۲ ب ۳۴۵'), normalizePlate('12-ب-345'));
  assert.equal(normalizePlate('١٢ ك ۳۴۵'), normalizePlate('۱۲ ک 345'));
  assert.equal(normalizePlate('abc-123'), 'ABC123');
});
