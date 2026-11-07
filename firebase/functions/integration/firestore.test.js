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
    'tenants/fleet': { active: true, vehiclePlateIndexReady: true },
    'tenants/fleet/users/driver': { active: true, role: 'driver' },
    'tenants/fleet/vehicles/v1': { active: true, tankCapacityMl: 300000 },
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

test('vehicle reads require manager and direct writes are denied', async () => {
  const { collection, getDocs, deleteDoc, updateDoc } = require('firebase/firestore');
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  const manager = env.authenticatedContext('manager').firestore();
  const driver = env.authenticatedContext('driver').firestore();
  const accountant = env.authenticatedContext('accountant').firestore();
  const vehicle = { plate: 'Test-01', model: 'Isuzu', tankCapacityMl: 300000, active: true };
  const path = 'tenants/fleet/vehicles/test';
  assert.equal((await getDocs(collection(manager, 'tenants/fleet/vehicles'))).size, 1);
  await assertFails(setDoc(doc(manager, path), vehicle));
  await assertFails(setDoc(doc(driver, path), vehicle));
  await assertFails(getDocs(collection(driver, 'tenants/fleet/vehicles')));
  await assertFails(getDocs(collection(accountant, 'tenants/fleet/vehicles')));
  await assertFails(updateDoc(doc(manager, 'tenants/fleet/vehicles/v1'), { active: false }));
  await assertFails(deleteDoc(doc(manager, 'tenants/fleet/vehicles/v1')));
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
  assert.equal((await getDoc(doc(client, 'tenants/fleet/fuelTransactions', requestId))).data().quote.totalAmount, 2421000);
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

const { changeAssignment } = require('../assignments');
test('assignment create and revoke require manager, are idempotent, and leave an audit', async () => {
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  const assignmentId = '323e4567-e89b-42d3-a456-426614174000';
  const data = { tenantId: 'fleet', assignmentId, driverUid: 'driver', vehicleId: 'v1' };
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await assert.rejects(changeAssignment(deps, 'accountant', data), { code: 'permission-denied' });
  await assert.rejects(changeAssignment(deps, 'driver', data), { code: 'permission-denied' });
  const result = await Promise.all([changeAssignment(deps, 'manager', data), changeAssignment(deps, 'manager', data)]);
  assert.deepEqual(result[0], result[1]);
  assert.equal(result[0].active, true);
  await assert.rejects(changeAssignment(deps, 'manager', { ...data, driverUid: 'accountant' }), { code: 'failed-precondition' });
  const manager = env.authenticatedContext('manager').firestore();
  assert.equal((await getDoc(doc(manager, 'tenants/fleet/assignments', assignmentId))).data().active, true);
  await assertFails(setDoc(doc(manager, 'tenants/fleet/assignments/new'), { active: true }));
  const revoke = { tenantId: 'fleet', assignmentId };
  assert.equal((await changeAssignment(deps, 'manager', revoke, true)).active, false);
  assert.equal((await changeAssignment(deps, 'manager', revoke, true)).active, false);
  assert.equal((await db.collection('tenants/fleet/audit').get()).size, 2);
  const driver = env.authenticatedContext('driver').firestore();
  await assertFails(getDoc(doc(driver, 'tenants/fleet/assignments', assignmentId)));
});

test('concurrent assignment creation cannot duplicate an active driver vehicle pair', async () => {
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  const a = { tenantId: 'fleet', assignmentId: '423e4567-e89b-42d3-a456-426614174000', driverUid: 'driver', vehicleId: 'v1' };
  const b = { ...a, assignmentId: '523e4567-e89b-42d3-a456-426614174000' };
  const results = await Promise.allSettled([changeAssignment(deps, 'manager', a), changeAssignment(deps, 'manager', b)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'already-exists');
  const winner = results.find(r => r.status === 'fulfilled').value.assignmentId;
  await changeAssignment(deps, 'manager', { tenantId: 'fleet', assignmentId: winner }, true);
  const next = winner === a.assignmentId ? b : a;
  assert.equal((await changeAssignment(deps, 'manager', next)).active, true);
});

test('bootstrap creates a tenant once and never escalates access in an existing tenant', async () => {
  const { bootstrapTenant } = require('../scripts/bootstrap-tenant.cjs');
  const deps = { db, auth: { getUser: async uid => ({ uid, disabled: false }) }, serverTimestamp: () => FieldValue.serverTimestamp() };
  await bootstrapTenant(deps, { tenantId: 'newfleet', managerUid: 'manager' });
  assert.equal((await db.doc('tenants/newfleet/users/manager').get()).data().role, 'manager');
  assert.equal((await db.doc('tenants/newfleet/settings/fuel').get()).data().monthlyQuotaMl, 300000);
  await assert.rejects(bootstrapTenant(deps, { tenantId: 'newfleet', managerUid: 'other' }), /already exists/);
  assert.equal((await db.doc('tenants/newfleet/users/other').get()).exists, false);
  await assert.rejects(bootstrapTenant({ ...deps, auth: { getUser: async () => ({ disabled: true }) } }, { tenantId: 'disabledfleet', managerUid: 'disabled' }), /disabled/);
  assert.equal((await db.doc('tenants/disabledfleet').get()).exists, false);
});

test('manager can assign and revoke via callable functions but a driver cannot', async () => {
  const { initializeApp: makeApp, deleteApp: deleteClient } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signInWithEmailAndPassword, signOut } = require('firebase/auth');
  const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
  const app = makeApp({ apiKey: 'emulator-only', projectId }, 'assignment-callable');
  try {
    const auth = getAuth(app);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const functions = getFunctions(app, 'us-central1');
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    const driver = await createUserWithEmailAndPassword(auth, 'assignment-driver@example.test', 'test-password-123');
    const driverUid = driver.user.uid;
    await signOut(auth);
    const manager = await createUserWithEmailAndPassword(auth, 'assignment-manager@example.test', 'test-password-123');
    await db.doc(`tenants/fleet/users/${driverUid}`).set({ active: true, role: 'driver' });
    await db.doc(`tenants/fleet/users/${manager.user.uid}`).set({ active: true, role: 'manager' });
    const assignment = { tenantId: 'fleet', assignmentId: '623e4567-e89b-42d3-a456-426614174000', driverUid, vehicleId: 'v1' };
    assert.equal((await httpsCallable(functions, 'assignDriver')(assignment)).data.active, true);
    await signOut(auth);
    await signInWithEmailAndPassword(auth, 'assignment-driver@example.test', 'test-password-123');
    await assert.rejects(httpsCallable(functions, 'revokeDriverAssignment')({ tenantId: 'fleet', assignmentId: assignment.assignmentId }), e => e.code === 'functions/permission-denied');
    await signOut(auth);
    await signInWithEmailAndPassword(auth, 'assignment-manager@example.test', 'test-password-123');
    assert.equal((await httpsCallable(functions, 'revokeDriverAssignment')({ tenantId: 'fleet', assignmentId: assignment.assignmentId })).data.active, false);
  } finally { await deleteClient(app); }
});

test('vehicle creation is idempotent and concurrent equivalent plates cannot duplicate', async () => {
  const { createVehicle } = require('../vehicles');
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  const a = { tenantId: 'fleet', vehicleId: '723e4567-e89b-42d3-a456-426614174000', plate: '۱۲ ب ۳۴۵', model: 'Isuzu', tankCapacityMl: 300000 };
  const b = { ...a, vehicleId: '823e4567-e89b-42d3-a456-426614174000', plate: '12-ب-345' };
  await assert.rejects(createVehicle(deps, 'accountant', a), { code: 'permission-denied' });
  const results = await Promise.allSettled([createVehicle(deps, 'manager', a), createVehicle(deps, 'manager', b)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'already-exists');
  const winner = results.find(r => r.status === 'fulfilled').value.vehicleId === a.vehicleId ? a : b;
  assert.equal((await createVehicle(deps, 'manager', winner)).vehicleId, winner.vehicleId);
  assert.equal((await db.collection('tenants/fleet/vehiclePlateLocks').get()).size, 1);
  assert.equal((await db.collection('tenants/fleet/audit').get()).size, 1);
  await assert.rejects(createVehicle(deps, 'manager', { ...winner, model: 'changed' }), { code: 'already-exists' });
});

test('fuel capacity and replay policy are enforced by the central service', async () => {
  const command = { ...input, litersMl: 300001 };
  await assert.rejects(submit(command), { code: 'failed-precondition' });
  assert.equal((await db.doc(`tenants/fleet/fuelRequests/${requestId}`).get()).exists, false);
  await submit(input);
  await db.doc('tenants/fleet/assignments/a1').update({ active: false });
  assert.equal((await submit(input)).status, 'pending');
  await assert.rejects(submit({ ...input, requestId: '923e4567-e89b-42d3-a456-426614174000' }), { code: 'permission-denied' });
});

test('legacy plate index migration fails closed on duplicates and unlocks clean tenants', async () => {
  const { migratePlateIndex } = require('../scripts/migrate-vehicle-plates.cjs');
  const { createVehicle } = require('../vehicles');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await db.doc('tenants/legacy').set({ active: true });
  await db.doc('tenants/legacy/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/legacy/vehicles/old1').set({ plate: '۱۲ ب ۳۴۵' });
  await db.doc('tenants/legacy/vehicles/old2').set({ plate: '12-ب-345' });
  const newVehicle = { tenantId: 'legacy', vehicleId: 'a23e4567-e89b-42d3-a456-426614174000', plate: 'new-1', model: 'Isuzu', tankCapacityMl: 300000 };
  await assert.rejects(createVehicle(deps, 'manager', newVehicle), { code: 'failed-precondition' });
  await assert.rejects(migratePlateIndex(deps, 'legacy'), /Duplicate/);
  assert.equal((await db.collection('tenants/legacy/vehiclePlateLocks').get()).size, 0);
  await db.doc('tenants/legacy/vehicles/old2').delete();
  assert.equal((await migratePlateIndex(deps, 'legacy')).count, 1);
  assert.equal((await migratePlateIndex(deps, 'legacy')).status, 'already-ready');
  await assert.rejects(createVehicle(deps, 'manager', { ...newVehicle, plate: '12-ب-345' }), { code: 'already-exists' });
  assert.equal((await createVehicle(deps, 'manager', newVehicle)).status, 'created');
});

test('fuel policy changes are authorized, versioned, replayable and stale quotes fail', async () => {
  const { updateFuelSettings } = require('../fuel-settings');
  const { fuelApproval } = require('../fuel-approval');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp(), now: () => new Date('2025-05-01T12:00:00Z') };
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  await db.doc('tenants/fleet/settings/fuel').set({ version: 1, monthlyQuotaMl: 300000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000 });
  await submit(input);
  const quote = (await fuelApproval(deps, 'accountant', { tenantId: 'fleet', requestId }, false)).quote;
  const change = { tenantId: 'fleet', commandId: 'a23e4567-e89b-42d3-a456-426614174000', expectedVersion: 1, monthlyQuotaMl: 200000, governmentPriceTomanPerLiter: 400, freePriceTomanPerLiter: 90000 };
  await assert.rejects(updateFuelSettings(deps, 'accountant', change), { code: 'permission-denied' });
  const competing = { ...change, commandId: 'b23e4567-e89b-42d3-a456-426614174000' };
  const results = await Promise.allSettled([updateFuelSettings(deps, 'manager', change), updateFuelSettings(deps, 'manager', competing)]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(results.find(r => r.status === 'rejected').reason.code, 'failed-precondition');
  const winner = results[0].status === 'fulfilled' ? change : competing;
  assert.equal((await updateFuelSettings(deps, 'manager', winner)).settings.version, 2);
  await assert.rejects(updateFuelSettings(deps, 'manager', { ...winner, freePriceTomanPerLiter: 1 }), { code: 'already-exists' });
  assert.equal((await db.collection('tenants/fleet/fuelSettingsChanges').get()).size, 1);
  const saved = (await db.doc(`tenants/fleet/fuelSettingsChanges/${winner.commandId}`).get()).data();
  assert.equal(saved.before.version, 1); assert.equal(saved.after.version, 2);
  await assert.rejects(fuelApproval(deps, 'accountant', { tenantId: 'fleet', requestId, quote }, true), { code: 'failed-precondition' });
  const manager = env.authenticatedContext('manager').firestore();
  assert.equal((await getDoc(doc(manager, 'tenants/fleet/settings/fuel'))).data().version, 2);
  await assertFails(setDoc(doc(manager, 'tenants/fleet/settings/fuel'), { version: 3 }));
  await assertFails(getDoc(doc(env.authenticatedContext('driver').firestore(), 'tenants/fleet/settings/fuel')));
});

test('manager updates fuel policy through the authenticated callable', async () => {
  const { initializeApp: makeApp, deleteApp: deleteClient } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword } = require('firebase/auth');
  const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
  const client = makeApp({ apiKey: 'emulator-only', projectId }, 'settings-callable');
  try {
    const auth = getAuth(client);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const manager = await createUserWithEmailAndPassword(auth, 'settings-manager@example.test', 'test-password-123');
    await db.doc(`tenants/fleet/users/${manager.user.uid}`).set({ active: true, role: 'manager' });
    await db.doc('tenants/fleet/settings/fuel').set({ version: 1, monthlyQuotaMl: 300000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000 });
    const functions = getFunctions(client, 'us-central1');
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    const cmd = { tenantId: 'fleet', commandId: 'c23e4567-e89b-42d3-a456-426614174000', expectedVersion: 1, monthlyQuotaMl: 300000, governmentPriceTomanPerLiter: 400, freePriceTomanPerLiter: 80000 };
    assert.equal((await httpsCallable(functions, 'updateFuelSettings')(cmd)).data.settings.version, 2);
    assert.equal((await httpsCallable(functions, 'updateFuelSettings')(cmd)).data.settings.version, 2);
  } finally { await deleteClient(client); }
});

test('customer creation is scoped, atomic, replayable and private', async () => {
  const { createCustomer } = require('../customers');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/accountant').set({ active: true, role: 'accountant' });
  const c = { tenantId: 'fleet', customerId: 'd23e4567-e89b-42d3-a456-426614174000', name: 'فروشگاه', address: 'تهران', phone: '۰۹۱۲۱۲۳۴۵۶۷' };
  await assert.rejects(createCustomer(deps, 'driver', c), { code: 'permission-denied' });
  assert.deepEqual(await Promise.all([createCustomer(deps, 'manager', c), createCustomer(deps, 'manager', c)]), [
    { customerId: c.customerId, status: 'created' }, { customerId: c.customerId, status: 'created' },
  ]);
  await assert.rejects(createCustomer(deps, 'manager', { ...c, address: 'کرج' }), { code: 'already-exists' });
  assert.equal((await db.collection('tenants/fleet/customers').get()).size, 1);
  assert.equal((await db.collection('tenants/fleet/audit').get()).size, 1);
  const manager = env.authenticatedContext('manager').firestore();
  assert.equal((await getDoc(doc(manager, 'tenants/fleet/customers', c.customerId))).data().name, c.name);
  await assertFails(setDoc(doc(manager, 'tenants/fleet/customers', c.customerId), { name: 'change' }));
  await assertFails(getDoc(doc(env.authenticatedContext('accountant').firestore(), 'tenants/fleet/customers', c.customerId)));
  await assertFails(getDoc(doc(env.authenticatedContext('driver').firestore(), 'tenants/fleet/customers', c.customerId)));
  await db.doc('tenants/fleet/users/manager').update({ active: false });
  await assert.rejects(createCustomer(deps, 'manager', c), { code: 'permission-denied' });
  await assertFails(getDoc(doc(manager, 'tenants/fleet/customers', c.customerId)));
});

test('customer callable requires authenticated manager', async () => {
  const { initializeApp: makeApp, deleteApp: deleteClient } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut } = require('firebase/auth');
  const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
  const client = makeApp({ apiKey: 'emulator-only', projectId }, 'customer-callable');
  try {
    const auth = getAuth(client);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const user = await createUserWithEmailAndPassword(auth, 'customer-manager@example.test', 'test-password-123');
    await db.doc(`tenants/fleet/users/${user.user.uid}`).set({ active: true, role: 'manager' });
    const functions = getFunctions(client, 'us-central1');
    connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    const command = { tenantId: 'fleet', customerId: 'e23e4567-e89b-42d3-a456-426614174000', name: 'مشتری', address: 'تهران', phone: '' };
    assert.equal((await httpsCallable(functions, 'createCustomer')(command)).data.status, 'created');
    assert.equal((await httpsCallable(functions, 'createCustomer')(command)).data.status, 'created');
    await signOut(auth);
    await assert.rejects(httpsCallable(functions, 'createCustomer')(command), e => e.code === 'functions/unauthenticated');
  } finally { await deleteClient(client); }
});

test('mission create, concurrency locks, access and state transitions are enforced', async () => {
  const { createMission, transitionMission } = require('../missions');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/other').set({ active: true, role: 'driver' });
  await db.doc('tenants/fleet/customers/c1').set({ active: true, name: 'فروشگاه', address: 'تهران', phone: '' });
  const c = { tenantId: 'fleet', missionId: 'a23e4567-e89b-42d3-a456-426614174000', assignmentId: 'a1', customerIds: ['c1'], goods: 'رب' };
  const c2 = { ...c, missionId: 'b23e4567-e89b-42d3-a456-426614174000' };
  await assert.rejects(createMission(deps, 'driver', c), { code: 'permission-denied' });
  await createMission(deps, 'manager', c); await createMission(deps, 'manager', c2);
  assert.equal((await createMission(deps, 'manager', c)).status, 'planned');
  await assert.rejects(createMission(deps, 'manager', { ...c, goods: 'ماکارونی' }), { code: 'already-exists' });
  const action = (missionId, action) => ({ tenantId: 'fleet', missionId, action });
  await assert.rejects(transitionMission(deps, 'other', action(c.missionId, 'start')), { code: 'permission-denied' });
  const outcomes = await Promise.allSettled([transitionMission(deps, 'driver', action(c.missionId, 'start')), transitionMission(deps, 'driver', action(c2.missionId, 'start'))]);
  assert.equal(outcomes.filter(o => o.status === 'fulfilled').length, 1);
  assert.equal(outcomes.find(o => o.status === 'rejected').reason.code, 'already-exists');
  const winner = outcomes[0].status === 'fulfilled' ? c : c2;
  const loser = winner === c ? c2 : c;
  assert.equal((await transitionMission(deps, 'driver', action(winner.missionId, 'start'))).status, 'active');
  const driver = env.authenticatedContext('driver').firestore(), other = env.authenticatedContext('other').firestore();
  assert.equal((await getDoc(doc(driver, 'tenants/fleet/missions', winner.missionId))).data().destinations[0].name, 'فروشگاه');
  await assertFails(getDoc(doc(other, 'tenants/fleet/missions', winner.missionId)));
  await assertFails(setDoc(doc(driver, 'tenants/fleet/missions', winner.missionId), { status: 'finished' }));
  await db.doc('tenants/fleet/assignments/a1').update({ active: false });
  assert.equal((await transitionMission(deps, 'manager', action(winner.missionId, 'finish'))).status, 'finished');
  assert.equal((await transitionMission(deps, 'driver', action(winner.missionId, 'finish'))).status, 'finished');
  await assert.rejects(transitionMission(deps, 'driver', action(loser.missionId, 'start')), { code: 'failed-precondition' });
  await db.doc('tenants/fleet/assignments/a1').update({ active: true });
  assert.equal((await transitionMission(deps, 'driver', action(loser.missionId, 'start'))).status, 'active');
});

test('mission callable flows through authenticated emulator', async () => {
  const { initializeApp: makeApp, deleteApp: deleteClient } = require('firebase/app');
  const { getAuth, connectAuthEmulator, createUserWithEmailAndPassword, signOut, signInWithEmailAndPassword } = require('firebase/auth');
  const { getFunctions, connectFunctionsEmulator, httpsCallable } = require('firebase/functions');
  const client = makeApp({ apiKey: 'emulator-only', projectId }, 'mission-callable');
  try {
    const auth = getAuth(client); connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, { disableWarnings: true });
    const driver = await createUserWithEmailAndPassword(auth, 'mission-driver@example.test', 'test-password-123');
    const driverUid = driver.user.uid; await signOut(auth);
    const manager = await createUserWithEmailAndPassword(auth, 'mission-manager@example.test', 'test-password-123');
    await db.doc(`tenants/fleet/users/${driverUid}`).set({ active: true, role: 'driver' });
    await db.doc(`tenants/fleet/users/${manager.user.uid}`).set({ active: true, role: 'manager' });
    await db.doc('tenants/fleet/assignments/a1').set({ active: true, driverUid, vehicleId: 'v1' });
    await db.doc('tenants/fleet/customers/c1').set({ active: true, name: 'مشتری', address: 'تهران', phone: '' });
    const functions = getFunctions(client, 'us-central1'); connectFunctionsEmulator(functions, '127.0.0.1', 5001);
    const cmd = { tenantId: 'fleet', missionId: 'c23e4567-e89b-42d3-a456-426614174000', assignmentId: 'a1', customerIds: ['c1'], goods: 'آبمیوه' };
    assert.equal((await httpsCallable(functions, 'createMission')(cmd)).data.status, 'planned');
    await signOut(auth); await signInWithEmailAndPassword(auth, 'mission-driver@example.test', 'test-password-123');
    assert.equal((await httpsCallable(functions, 'transitionMission')({ tenantId: 'fleet', missionId: cmd.missionId, action: 'start' })).data.status, 'active');
    const report = { tenantId: 'fleet', missionId: cmd.missionId, customerId: 'c1', outcome: 'delivered', recipient: 'فروشنده', note: '' };
    assert.equal((await httpsCallable(functions, 'recordMissionDelivery')(report)).data.status, 'recorded');
    assert.equal((await httpsCallable(functions, 'recordMissionDelivery')(report)).data.status, 'recorded');
    assert.equal((await httpsCallable(functions, 'transitionMission')({ tenantId: 'fleet', missionId: cmd.missionId, action: 'finish' })).data.status, 'finished');
  } finally { await deleteClient(client); }
});

test('destination reports are authorized, immutable, concurrent safe and replayable after route finish', async () => {
  const { createMission, transitionMission, recordMissionDelivery } = require('../missions');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/users/other').set({ active: true, role: 'driver' });
  await db.doc('tenants/fleet/customers/c1').set({ active: true, name: 'الف', address: 'تهران', phone: '' });
  await db.doc('tenants/fleet/customers/c2').set({ active: true, name: 'ب', address: 'کرج', phone: '' });
  const missionId = 'd23e4567-e89b-42d3-a456-426614174000';
  await createMission(deps, 'manager', { tenantId: 'fleet', missionId, assignmentId: 'a1', customerIds: ['c1', 'c2'], goods: 'کالا' });
  const delivery = { tenantId: 'fleet', missionId, customerId: 'c1', outcome: 'delivered', recipient: 'مسئول فروشگاه', note: '' };
  await assert.rejects(recordMissionDelivery(deps, 'driver', delivery), { code: 'failed-precondition' });
  await transitionMission(deps, 'driver', { tenantId: 'fleet', missionId, action: 'start' });
  await assert.rejects(recordMissionDelivery(deps, 'other', delivery), { code: 'permission-denied' });
  await assert.rejects(recordMissionDelivery(deps, 'manager', delivery), { code: 'permission-denied' });
  await assert.rejects(recordMissionDelivery(deps, 'driver', { ...delivery, customerId: 'nope' }), { code: 'failed-precondition' });
  const alternate = { ...delivery, outcome: 'not_delivered', recipient: '', note: 'بسته بود' };
  const both = await Promise.allSettled([recordMissionDelivery(deps, 'driver', delivery), recordMissionDelivery(deps, 'driver', alternate)]);
  assert.equal(both.filter(r => r.status === 'fulfilled').length, 1);
  assert.equal(both.find(r => r.status === 'rejected').reason.code, 'already-exists');
  const winner = both[0].status === 'fulfilled' ? delivery : alternate;
  const second = { ...alternate, customerId: 'c2' };
  await db.doc('tenants/fleet/assignments/a1').update({ active: false });
  await assert.rejects(recordMissionDelivery(deps, 'driver', second), { code: 'failed-precondition' });
  await db.doc('tenants/fleet/assignments/a1').update({ active: true });
  await recordMissionDelivery(deps, 'driver', second);
  assert.equal((await db.doc(`tenants/fleet/missions/${missionId}`).get()).data().deliveryResults.length, 2);
  await transitionMission(deps, 'driver', { tenantId: 'fleet', missionId, action: 'finish' });
  assert.equal((await recordMissionDelivery(deps, 'driver', winner)).status, 'recorded');
  assert.equal((await db.collection('tenants/fleet/audit').get()).size, 5);
});

test('concurrent route finish and destination report never write a new result after finish', async () => {
  const { createMission, transitionMission, recordMissionDelivery } = require('../missions');
  const deps = { db, serverTimestamp: () => FieldValue.serverTimestamp() };
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/fleet/customers/c1').set({ active: true, name: 'الف', address: 'تهران', phone: '' });
  const missionId = 'e23e4567-e89b-42d3-a456-426614174000';
  await createMission(deps, 'manager', { tenantId: 'fleet', missionId, assignmentId: 'a1', customerIds: ['c1'], goods: 'کالا' });
  const action = { tenantId: 'fleet', missionId, action: 'start' };
  await transitionMission(deps, 'driver', action);
  const report = { tenantId: 'fleet', missionId, customerId: 'c1', outcome: 'not_delivered', recipient: '', note: 'تعطیل' };
  const [finish, delivery] = await Promise.allSettled([transitionMission(deps, 'driver', { ...action, action: 'finish' }), recordMissionDelivery(deps, 'driver', report)]);
  assert.equal(finish.status, 'fulfilled');
  if (delivery.status === 'rejected') {
    assert.equal(delivery.reason.code, 'failed-precondition');
    assert.equal((await db.doc(`tenants/fleet/missions/${missionId}`).get()).data().deliveryResults, undefined);
    await assert.rejects(recordMissionDelivery(deps, 'driver', report), { code: 'failed-precondition' });
  } else {
    assert.equal((await db.doc(`tenants/fleet/missions/${missionId}`).get()).data().deliveryResults.length, 1);
    assert.equal((await recordMissionDelivery(deps, 'driver', report)).status, 'recorded');
  }
});


test('approved fuel ledger report is read-only and scoped to active finance roles', async () => {
  const { collection, getDocs, query, orderBy, limit } = require('firebase/firestore');
  await seedFuelApproval();
  await db.doc('tenants/fleet/users/manager').set({ active: true, role: 'manager' });
  await db.doc('tenants/other').set({ active: true });
  await db.doc('tenants/other/fuelTransactions/secret').set({ quote: { totalAmount: 123 }, approvedAt: FieldValue.serverTimestamp() });
  const quote = (await qFuel(requestId)).quote; await aFuel(requestId, quote);
  for (const uid of ['manager', 'accountant']) {
    const client = env.authenticatedContext(uid).firestore();
    const records = await getDocs(query(collection(client, 'tenants/fleet/fuelTransactions'), orderBy('approvedAt', 'desc'), limit(51)));
    assert.equal(records.size, 1); assert.equal(records.docs[0].data().quote.totalAmount, 2421000);
    await assertFails(setDoc(doc(client, 'tenants/fleet/fuelTransactions', requestId), { quote: { totalAmount: 0 } }));
    await assertFails(getDocs(collection(client, 'tenants/other/fuelTransactions')));
  }
  await assertFails(getDoc(doc(env.authenticatedContext('driver').firestore(), 'tenants/fleet/fuelTransactions', requestId)));
  await db.doc('tenants/fleet/users/accountant').update({ active: false });
  await assertFails(getDocs(collection(env.authenticatedContext('accountant').firestore(), 'tenants/fleet/fuelTransactions')));
  await db.doc('tenants/fleet').update({ active: false });
  await assertFails(getDoc(doc(env.authenticatedContext('manager').firestore(), 'tenants/fleet/fuelTransactions', requestId)));
});
