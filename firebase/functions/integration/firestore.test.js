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

test('active users can read only their own profile in an active tenant', async () => {
  const driver = env.authenticatedContext('driver').firestore();
  const own = doc(driver, 'tenants/fleet/users/driver');
  assert.equal((await getDoc(own)).data().role, 'driver');
  await assertFails(setDoc(own, { active: true, role: 'manager' }));
  await assertFails(getDoc(doc(env.authenticatedContext('other').firestore(), 'tenants/fleet/users/driver')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'tenants/fleet/users/driver')));
  await db.doc('tenants/fleet/users/driver').update({ active: false });
  await assertFails(getDoc(own));
  await db.doc('tenants/fleet/users/driver').update({ active: true });
  await db.doc('tenants/fleet').update({ active: false });
  await assertFails(getDoc(own));
});

test('email account created by Auth emulator can read only its provisioned profile', async () => {
  if (!process.env.FIREBASE_AUTH_EMULATOR_HOST) throw new Error('Auth emulator required');
  const { initializeApp: createClientApp, deleteApp: deleteClientApp } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut, signInWithEmailAndPassword } = require('firebase/auth');
  const { getFirestore: clientFirestore, connectFirestoreEmulator } = require('firebase/firestore');
  const clientApp = createClientApp({ apiKey: 'emulator-only', projectId }, 'auth-integration');
  try {
    const clientAuth = getAuth(clientApp);
    connectAuthEmulator(clientAuth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const clientDb = clientFirestore(clientApp);
    const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
    connectFirestoreEmulator(clientDb, host, Number(port));
    const created = await createUserWithEmailAndPassword(clientAuth, 'driver@example.test', 'test-password-123');
    await db.doc(`tenants/fleet/users/${created.user.uid}`).set({ active: true, role: 'driver' });
    await signOut(clientAuth);
    const signedIn = await signInWithEmailAndPassword(clientAuth, 'driver@example.test', 'test-password-123');
    const profile = await getDoc(doc(clientDb, 'tenants/fleet/users', signedIn.user.uid));
    assert.equal(profile.data().role, 'driver');
    await assertFails(getDoc(doc(clientDb, 'tenants/fleet/users/driver')));
  } finally {
    await deleteClientApp(clientApp);
  }
});

test('only active managers can read and create validated vehicles', async () => {
  const { serverTimestamp, collection, getDocs, deleteDoc, updateDoc } = require('firebase/firestore');
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  const manager = env.authenticatedContext('manager').firestore();
  const driver = env.authenticatedContext('driver').firestore();
  const accountant = env.authenticatedContext('accountant').firestore();
  const vehicle = { plate: 'Test-01', model: 'Isuzu', tankCapacityMl: 300000, active: true, createdBy: 'manager', createdAt: serverTimestamp() };
  const path = 'tenants/fleet/vehicles/test';
  await setDoc(doc(manager, path), vehicle);
  assert.equal((await getDocs(collection(manager, 'tenants/fleet/vehicles'))).size, 2);
  await assertFails(setDoc(doc(driver, 'tenants/fleet/vehicles/driver-test'), vehicle));
  await assertFails(getDocs(collection(driver, 'tenants/fleet/vehicles')));
  await assertFails(getDocs(collection(accountant, 'tenants/fleet/vehicles')));
  await assertFails(setDoc(doc(manager, 'tenants/fleet/vehicles/invalid'), { ...vehicle, tankCapacityMl: -1 }));
  await assertFails(setDoc(doc(manager, 'tenants/fleet/vehicles/forged'), { ...vehicle, createdBy: 'driver' }));
  await assertFails(updateDoc(doc(manager, path), { plate: 'changed' }));
  await assertFails(deleteDoc(doc(manager, path)));
  await db.doc('tenants/fleet/users/manager').update({ active: false });
  await assertFails(getDocs(collection(manager, 'tenants/fleet/vehicles')));
});
