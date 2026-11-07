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
