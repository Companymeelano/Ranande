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

const { fuelApproval } = require('../fuel-approval');
const approvalDeps = () => ({ db, serverTimestamp: () => FieldValue.serverTimestamp(), now: () => new Date('2025-03-21T12:00:00Z') });
const qFuel = id => fuelApproval(approvalDeps(), 'accountant', { tenantId: 'fleet', requestId: id }, false);
const aFuel = (id, quote, uid = 'accountant') => fuelApproval(approvalDeps(), uid, { tenantId: 'fleet', requestId: id, quote }, true);
async function seedFuelApproval() {
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  await db.doc('tenants/fleet/settings/fuel').set({ version: 1, monthlyQuotaMl: 70000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000 });
  await submit(input);
}

test('financial confirmation is atomic, idempotent and snapshots rates', async () => {
  await seedFuelApproval();
  const { quote } = await qFuel(requestId);
  assert.equal(quote.totalAmount, 2421000);
  const results = await Promise.all([aFuel(requestId, quote), aFuel(requestId, quote)]);
  assert.deepEqual(results[0], results[1]);
  const quota = (await db.doc('tenants/fleet/quotas/v1_140401').get()).data();
  assert.equal(quota.usedMl, 70000);
  assert.equal(quota.version, 1);
  assert.equal((await db.collection('tenants/fleet/fuelTransactions').get()).size, 1);
  assert.equal((await db.collection('tenants/fleet/audit').get()).size, 1);
  await db.doc('tenants/fleet/settings/fuel').update({ governmentPriceTomanPerLiter: 999, version: 2 });
  assert.equal((await db.doc(`tenants/fleet/fuelTransactions/${requestId}`).get()).data().quote.governmentRate, 300);
  const client = env.authenticatedContext('accountant').firestore();
  await assertFails(getDoc(doc(client, 'tenants/fleet/fuelTransactions', requestId)));
  await assertFails(setDoc(doc(client, 'tenants/fleet/quotas/v1_140401'), { usedMl: 0 }));
});

test('concurrent different approvals cannot exceed quota and stale quote requires review', async () => {
  await seedFuelApproval();
  const otherId = '223e4567-e89b-42d3-a456-426614174000';
  await submit({ ...input, requestId: otherId });
  const first = (await qFuel(requestId)).quote;
  const second = (await qFuel(otherId)).quote;
  const results = await Promise.allSettled([aFuel(requestId, first), aFuel(otherId, second)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'failed-precondition');
  const remaining = (await db.doc(`tenants/fleet/fuelRequests/${requestId}`).get()).data().status === 'pending' ? requestId : otherId;
  const revised = (await qFuel(remaining)).quote;
  assert.equal(revised.governmentMl, 0);
  assert.equal(revised.freeMl, 100000);
  await aFuel(remaining, revised);
  assert.equal((await db.doc('tenants/fleet/quotas/v1_140401').get()).data().usedMl, 70000);
  assert.equal((await db.collection('tenants/fleet/fuelTransactions').get()).size, 2);
});

test('approval rejects unauthorized and stale settings without writes', async () => {
  await seedFuelApproval();
  const quote = (await qFuel(requestId)).quote;
  await assert.rejects(aFuel(requestId, quote, 'driver'), { code: 'permission-denied' });
  await db.doc('tenants/fleet/settings/fuel').update({ freePriceTomanPerLiter: 90000, version: 2 });
  await assert.rejects(aFuel(requestId, quote), { code: 'failed-precondition' });
  assert.equal((await db.collection('tenants/fleet/fuelTransactions').get()).size, 0);
  assert.equal((await db.collection('tenants/fleet/quotas').get()).size, 0);
  assert.equal((await db.doc(`tenants/fleet/fuelRequests/${requestId}`).get()).data().status, 'pending');
});

test('fuel and assignment reads are scoped by role and ownership; direct writes denied', async () => {
  const { collection, query, where, getDocs, serverTimestamp } = require('firebase/firestore');
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  await db.doc('tenants/fleet/users/driver2').set({ active: true, role: 'driver' });
  await db.doc('tenants/fleet/assignments/a2').set({ active: true, driverUid: 'driver2', vehicleId: 'v2' });
  await db.doc('tenants/fleet/assignments/a3').set({ active: false, driverUid: 'driver', vehicleId: 'v3' });
  await db.doc('tenants/fleet/fuelRequests/r1').set({ driverUid: 'driver', status: 'pending' });
  await db.doc('tenants/fleet/fuelRequests/r2').set({ driverUid: 'driver2', status: 'pending' });
  const driver = env.authenticatedContext('driver').firestore();
  const accountant = env.authenticatedContext('accountant').firestore();
  const manager = env.authenticatedContext('manager').firestore();
  const assignments = collection(driver, 'tenants/fleet/assignments');
  assert.equal((await getDocs(query(assignments, where('driverUid', '==', 'driver'), where('active', '==', true)))).size, 1);
  await assertFails(getDocs(assignments));
  await assertFails(getDoc(doc(driver, 'tenants/fleet/assignments/a2')));
  await assertFails(getDoc(doc(driver, 'tenants/fleet/assignments/a3')));
  const fuel = collection(driver, 'tenants/fleet/fuelRequests');
  assert.equal((await getDocs(query(fuel, where('driverUid', '==', 'driver')))).size, 1);
  await assertFails(getDocs(fuel));
  assert.equal((await getDocs(collection(accountant, 'tenants/fleet/fuelRequests'))).size, 2);
  assert.equal((await getDocs(collection(manager, 'tenants/fleet/fuelRequests'))).size, 2);
  await assertFails(setDoc(doc(driver, 'tenants/fleet/fuelRequests/fake'), { driverUid: 'driver', createdAt: serverTimestamp() }));
  await assertFails(setDoc(doc(manager, 'tenants/fleet/assignments/a4'), { driverUid: 'driver', active: true }));
  await db.doc('tenants/fleet/users/driver').update({ active: false });
  await assertFails(getDocs(query(assignments, where('driverUid', '==', 'driver'), where('active', '==', true))));
});

test('authenticated callable submission, quote and confirmation run end to end', async () => {
  const { initializeApp: makeApp, deleteApp: deleteClient } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signInWithEmailAndPassword, signOut } = require('firebase/auth');
  const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
  const app = makeApp({ apiKey: 'emulator-only', projectId }, 'callable-integration');
  try {
    const auth = getAuth(app);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const functions = getFunctions(app, 'us-central1');
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    const driver = await createUserWithEmailAndPassword(auth, 'driver-callable@example.test', 'test-password-123');
    const driverUid = driver.user.uid;
    await signOut(auth);
    const accountant = await createUserWithEmailAndPassword(auth, 'accountant-callable@example.test', 'test-password-123');
    const accountantUid = accountant.user.uid;
    await db.doc(`tenants/fleet/users/${driverUid}`).set({ active: true, role: 'driver' });
    await db.doc(`tenants/fleet/users/${accountantUid}`).set({ active: true, role: 'accountant' });
    await db.doc('tenants/fleet/assignments/a-callable').set({ active: true, driverUid, vehicleId: 'v1' });
    await db.doc('tenants/fleet/settings/fuel').set({ version: 1, monthlyQuotaMl: 70000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000 });
    await signOut(auth);
    await signInWithEmailAndPassword(auth, 'driver-callable@example.test', 'test-password-123');
    const submitCallable = httpsCallable(functions, 'submitFuelRequest');
    const payload = { ...input, assignmentId: 'a-callable' };
    assert.equal((await submitCallable(payload)).data.status, 'pending');
    assert.equal((await submitCallable(payload)).data.status, 'pending');
    await assert.rejects(httpsCallable(functions, 'quoteFuelRequest')({ tenantId: 'fleet', requestId }), e => e.code === 'functions/permission-denied');
    await signOut(auth);
    await signInWithEmailAndPassword(auth, 'accountant-callable@example.test', 'test-password-123');
    const preview = (await httpsCallable(functions, 'quoteFuelRequest')({ tenantId: 'fleet', requestId })).data;
    assert.equal(preview.quote.governmentMl, 70000);
    assert.equal(preview.quote.totalAmount, 2421000);
    const final = (await httpsCallable(functions, 'approveFuelRequest')({ tenantId: 'fleet', requestId, quote: preview.quote })).data;
    assert.equal(final.status, 'approved');
    assert.equal((await db.doc(`tenants/fleet/fuelRequests/${requestId}`).get()).data().status, 'approved');
    assert.equal((await db.collection('tenants/fleet/fuelTransactions').get()).size, 1);
  } finally {
    await deleteClient(app);
  }
});
