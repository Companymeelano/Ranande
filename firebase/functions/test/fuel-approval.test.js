'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { persianMonth, computeQuote } = require('../fuel-approval');
const settings = { monthlyQuotaMl: 70000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000, version: 1 };

test('Persian month follows Tehran midnight at Nowruz', () => {
  assert.equal(persianMonth(new Date('2025-03-20T20:29:59Z')), '140312');
  assert.equal(persianMonth(new Date('2025-03-20T20:30:00Z')), '140401');
});
test('splits fuel and snapshots integer prices with correct amounts', () => {
  const q = computeQuote({ litersMl: 100000 }, settings, null, '140401');
  assert.equal(q.governmentMl, 70000); assert.equal(q.freeMl, 30000);
  assert.equal(q.governmentAmount, 21000); assert.equal(q.freeAmount, 2400000); assert.equal(q.totalAmount, 2421000);
});
test('rounds each component half up and rejects overflow or corrupted quota', () => {
  const q = computeQuote({ litersMl: 1 }, { ...settings, monthlyQuotaMl: 1, governmentPriceTomanPerLiter: 500 }, null, '140401');
  assert.equal(q.governmentAmount, 1);
  assert.throws(() => computeQuote({ litersMl: 1 }, settings, { allocatedMl: 1, usedMl: 2, version: 0 }, '140401'), { code: 'failed-precondition' });
  assert.throws(() => computeQuote({ litersMl: Number.MAX_SAFE_INTEGER }, { ...settings, monthlyQuotaMl: 0, freePriceTomanPerLiter: Number.MAX_SAFE_INTEGER }, null, '140401'), { code: 'failed-precondition' });
});
