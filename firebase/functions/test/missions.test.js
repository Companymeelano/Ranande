'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { createMission, transitionMission } = require('../missions');
const data = { tenantId: 'fleet', missionId: '123e4567-e89b-42d3-a456-426614174000', assignmentId: 'a', customerIds: ['a'], goods: 'کالا' };
test('mission inputs fail closed', async () => {
  await assert.rejects(createMission({}, null, data), { code: 'unauthenticated' });
  for (const bad of [{ customerIds: [] }, { customerIds: ['a', 'a'] }, { goods: '' }, { assignmentId: '../' }, { customerIds: Array(21).fill('a') }])
    await assert.rejects(createMission({}, 'manager', { ...data, ...bad }), { code: 'invalid-argument' });
  await assert.rejects(transitionMission({}, 'driver', { tenantId: 'fleet', missionId: data.missionId, action: 'delete' }), { code: 'invalid-argument' });
});

test('destination result validates before database access', async () => {
  const { recordMissionDelivery } = require('../missions');
  const good = { tenantId: 'fleet', missionId: data.missionId, customerId: 'c1', outcome: 'delivered', recipient: 'علی', note: '' };
  for (const invalid of [{ outcome: 'delivered', recipient: '' }, { outcome: 'not_delivered', recipient: '', note: '' }, { customerId: '../bad' }, { note: '\n' }, { outcome: 'not_delivered', recipient: 'x', note: 'closed' }])
    await assert.rejects(recordMissionDelivery({}, 'driver', { ...good, ...invalid }), { code: 'invalid-argument' });
});
