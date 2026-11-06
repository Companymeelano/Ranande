'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createCustomer } = require('../customers');
const valid = { tenantId: 'fleet', customerId: '123e4567-e89b-42d3-a456-426614174000', name: 'فروشگاه', address: 'تهران', phone: '۰۹۱۲-1234567' };
test('customer input fails closed before database access', async () => {
  await assert.rejects(createCustomer({}, null, valid), { code: 'unauthenticated' });
  for (const value of [{ name: '' }, { address: ' ' }, { phone: 'abc' }, { name: 'نام\nجدید' }, { customerId: '../abc' }, { extra: true }])
    await assert.rejects(createCustomer({}, 'manager', { ...valid, ...value }), { code: 'invalid-argument' });
});
