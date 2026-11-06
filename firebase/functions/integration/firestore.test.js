'use strict';
const { before, after, beforeEach, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { initializeTestEnvironment, assertFails } = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc } = require('firebase/firestore');
const { initializeApp, deleteApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { submitFuelRequest } = require('../fuel-request');

const projectId = 'demo-alnasser';
const requestId = '123e4567-e89b-42d3-a456-426614174000';
const input = { tenantId: 'fleet', requestId, vehicleId: 'v1', assignmentId: 'a1', litersMl: 100000, occurredAtMillis: 1700000000000 };
let env, app, db;

before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('This test must run against the Firestore emulator');
  env = await initializeTestEnvironment({ projectId, firestore: { rules: fs.readFileSync(path.join(__dirname, '../../firestore.rules'), 'utf8') } });
  app = initializeApp({ projectId }, 'integration');
  db = getFirestore(app);
});
beforeEach(async () => {
  await env.clearFirestore();
  const batch = db.batch();
  for (const [path, data] of Object.entries({
    'tenants/fleet': { active: true },
    'tenants/fleet/users/driver': { active: true, role: 'driver' },
    'tenants/fleet/vehicles/v1': { active: true },
    'tenants/fleet/assignments/a1': { active: true, driverUid: 'driver', vehicleId: 'v1' },
  })) batch.set(db.doc(path), data);
  await batch.commit();
});
after(async () => {
  if (env) await env.cleanup();
  if (app) await deleteApp(app);
});

const submit = data => submitFuelRequest({ db, serverTimestamp: () => FieldValue.serverTimestamp() }, 'driver', data);

test('simultaneous identical submissions create one pending record', async () => {
  const results = await Promise.all([submit(input), submit(input)]);
  assert.deepEqual(results, [{ requestId, status: 'pending' }, { requestId, status: 'pending' }]);
  const requests = await db.collection('tenants/fleet/fuelRequests').get();
  assert.equal(requests.size, 1);
  assert.equal(requests.docs[0].data().litersMl, 100000);
});

test('simultaneous different payloads cannot reuse an id', async () => {
  const outcomes = await Promise.allSettled([submit(input), submit({ ...input, litersMl: 200000 })]);
  assert.equal(outcomes.filter(result => result.status === 'fulfilled').length, 1);
  const rejected = outcomes.find(result => result.status === 'rejected');
  assert.equal(rejected.reason.code, 'already-exists');
  assert.equal((await db.collection('tenants/fleet/fuelRequests').get()).size, 1);
});

test('client reads and writes are denied even with authentication', async () => {
  const client = env.authenticatedContext('driver').firestore();
  const ref = doc(client, 'tenants/fleet/fuelRequests', requestId);
  await assertFails(setDoc(ref, { ...input, status: 'approved' }));
  await assertFails(getDoc(doc(client, 'tenants/fleet/vehicles/v1')));
  const anonymous = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(anonymous, 'tenants/fleet')));
});
