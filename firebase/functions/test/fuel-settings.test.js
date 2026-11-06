'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { validPolicy, updateFuelSettings } = require('../fuel-settings');
const good = { version: 1, monthlyQuotaMl: 300000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000 };
test('settings limits reject invalid and unsafe values', async () => {
  assert.equal(validPolicy(good), true);
  for (const invalid of [{ monthlyQuotaMl: -1 }, { monthlyQuotaMl: 10000001 }, { freePriceTomanPerLiter: 1.5 }, { version: Number.MAX_SAFE_INTEGER }])
    assert.equal(validPolicy({ ...good, ...invalid }), false);
  await assert.rejects(updateFuelSettings({}, null, {}), { code: 'unauthenticated' });
  await assert.rejects(updateFuelSettings({}, 'manager', { ...good }), { code: 'invalid-argument' });
});
