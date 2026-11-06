'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { submitFuelRequest } = require('../fuel-request');

const id = '123e4567-e89b-42d3-a456-426614174000';
const command = { tenantId: 'fleet', requestId: id, vehicleId: 'v1', assignmentId: 'a1', litersMl: 100000, occurredAtMillis: 1700000000000 };
function fakeDb() {
  const documents = new Map(Object.entries({
    'tenants/fleet': { active: true },
    'tenants/fleet/users/u1': { active: true, role: 'driver' },
    'tenants/fleet/vehicles/v1': { active: true, tankCapacityMl: 300000 },
    'tenants/fleet/assignments/a1': { active: true, driverUid: 'u1', vehicleId: 'v1' },
  }));
  function doc(path) { return { path, id: path.split('/').at(-1), collection: name => collection(`${path}/${name}`) }; }
  function collection(path) { return { doc: id => doc(`${path}/${id}`) }; }
  const db = { collection, async runTransaction(fn) {
    const writes = [];
    const result = await fn({ get: async ref => ({ exists: documents.has(ref.path), data: () => documents.get(ref.path) }),
      create: (ref, value) => writes.push([ref.path, value]) });
    for (const [path, value] of writes) { if (documents.has(path)) throw Error('duplicate'); documents.set(path, value); }
    return result;
  } };
  return { db, documents };
}
const invoke = (db, uid = 'u1', data = command) => submitFuelRequest({ db, serverTimestamp: () => 'server-time' }, uid, data);

test('creates only a pending proposal; retry is idempotent', async () => {
  const { db, documents } = fakeDb();
  assert.deepEqual(await invoke(db), { requestId: id, status: 'pending' });
  assert.deepEqual(await invoke(db), { requestId: id, status: 'pending' });
  assert.equal(documents.get(`tenants/fleet/fuelRequests/${id}`).litersMl, 100000);
  assert.equal(documents.size, 5);
});
test('same key with changed payload is rejected', async () => {
  const { db } = fakeDb(); await invoke(db);
  await assert.rejects(invoke(db, 'u1', { ...command, litersMl: 200000 }), { code: 'already-exists' });
});
test('replay after assignment revocation returns the accepted result but cannot create a new request', async () => {
  const { db, documents } = fakeDb(); await invoke(db);
  documents.get('tenants/fleet/assignments/a1').active = false;
  assert.equal((await invoke(db)).status, 'pending');
  await assert.rejects(invoke(db, 'u1', { ...command, requestId: '223e4567-e89b-42d3-a456-426614174000' }), { code: 'permission-denied' });
  documents.get('tenants/fleet/users/u1').active = false;
  await assert.rejects(invoke(db), { code: 'permission-denied' });
});
test('rejects volume above capacity or missing or invalid capacity', async () => {
  const { db, documents } = fakeDb();
  for (const capacity of [null, 0, 99999, 123.5]) {
    documents.get('tenants/fleet/vehicles/v1').tankCapacityMl = capacity;
    await assert.rejects(invoke(db), { code: 'failed-precondition' });
  }
  assert.equal(documents.size, 4);
  documents.get('tenants/fleet/vehicles/v1').tankCapacityMl = 100000;
  assert.equal((await invoke(db)).status, 'pending');
  documents.get('tenants/fleet/vehicles/v1').tankCapacityMl = 50000;
  assert.equal((await invoke(db)).status, 'pending');
});
test('denies missing auth, wrong user, role, tenant, vehicle or assignment', async () => {
  const { db, documents } = fakeDb();
  await assert.rejects(invoke(db, ''), { code: 'unauthenticated' });
  await assert.rejects(invoke(db, 'u2'), { code: 'permission-denied' });
  for (const path of ['tenants/fleet', 'tenants/fleet/users/u1', 'tenants/fleet/vehicles/v1', 'tenants/fleet/assignments/a1']) {
    const obj = documents.get(path); const previous = obj.active; obj.active = false;
    await assert.rejects(invoke(db), { code: 'permission-denied' }); obj.active = previous;
  }
  documents.get('tenants/fleet/users/u1').role = 'accountant';
  await assert.rejects(invoke(db), { code: 'permission-denied' });
});
test('rejects invalid or extra input before any writes', async () => {
  const { db, documents } = fakeDb();
  const bad = [
    { ...command, litersMl: 0 }, { ...command, litersMl: 1.5 }, { ...command, litersMl: Number.MAX_SAFE_INTEGER },
    { ...command, occurredAtMillis: -1 }, { ...command, vehicleId: '../secret' },
    { ...command, requestId: 'invalid' }, { ...command, status: 'approved' },
    { ...command, tenantId: 'x/y' }, null,
  ];
  for (const input of bad) await assert.rejects(invoke(db, 'u1', input), { code: 'invalid-argument' });
  assert.equal(documents.size, 4);
});
test('assignment must match both vehicle and driver', async () => {
  const { db, documents } = fakeDb();
  const assignment = documents.get('tenants/fleet/assignments/a1');
  assignment.vehicleId = 'other';
  await assert.rejects(invoke(db), { code: 'permission-denied' });
  assignment.vehicleId = 'v1'; assignment.driverUid = 'other';
  await assert.rejects(invoke(db), { code: 'permission-denied' });
});
test('UUID casing resolves to the same request', async () => {
  const { db, documents } = fakeDb();
  await invoke(db, 'u1', { ...command, requestId: id.toUpperCase() });
  await invoke(db);
  assert.equal(documents.size, 5);
});
