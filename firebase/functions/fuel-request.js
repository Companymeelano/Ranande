'use strict';

// This endpoint records a proposal only; it does not allocate quota or create a ledger entry.
class CommandError extends Error {
  constructor(code, message) { super(message); this.code = code; }
}

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const FIELDS = ['tenantId', 'requestId', 'vehicleId', 'assignmentId', 'litersMl', 'occurredAtMillis'];

function validate(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) ||
      Object.keys(data).length !== FIELDS.length || FIELDS.some(k => !Object.hasOwn(data, k))) {
    throw new CommandError('invalid-argument', 'Invalid command fields.');
  }
  for (const field of ['tenantId', 'vehicleId', 'assignmentId']) {
    if (typeof data[field] !== 'string' || !ID.test(data[field]))
      throw new CommandError('invalid-argument', 'Invalid identifier.');
  }
  if (typeof data.requestId !== 'string' || !UUID.test(data.requestId))
    throw new CommandError('invalid-argument', 'Invalid request identifier.');
  if (!Number.isSafeInteger(data.litersMl) || data.litersMl <= 0 || data.litersMl > 2147483647 ||
      !Number.isSafeInteger(data.occurredAtMillis) || data.occurredAtMillis < 0 || data.occurredAtMillis > 8640000000000000)
    throw new CommandError('invalid-argument', 'Invalid volume or timestamp.');
}

/** Runs all authorization and idempotency checks inside the same Firestore transaction. */
async function submitFuelRequest({ db, serverTimestamp }, uid, data) {
  if (!uid || typeof uid !== 'string' || !ID.test(uid))
    throw new CommandError('unauthenticated', 'Authentication required.');
  validate(data);
  const tenant = db.collection('tenants').doc(data.tenantId);
  const user = tenant.collection('users').doc(uid);
  const vehicle = tenant.collection('vehicles').doc(data.vehicleId);
  const assignment = tenant.collection('assignments').doc(data.assignmentId);
  const request = tenant.collection('fuelRequests').doc(data.requestId.toLowerCase());
  return db.runTransaction(async tx => {
    const [tenantSnap, userSnap, vehicleSnap, assignmentSnap, existingSnap] =
      await Promise.all([tenant, user, vehicle, assignment, request].map(ref => tx.get(ref)));
    const membership = userSnap.data();
    const assigned = assignmentSnap.data();
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !userSnap.exists ||
        membership.active !== true || membership.role !== 'driver' ||
        !vehicleSnap.exists || vehicleSnap.data().active !== true || !assignmentSnap.exists ||
        assigned.active !== true || assigned.driverUid !== uid || assigned.vehicleId !== data.vehicleId) {
      throw new CommandError('permission-denied', 'Not authorized for this vehicle.');
    }
    if (existingSnap.exists) {
      const old = existingSnap.data();
      if (old.driverUid !== uid || old.vehicleId !== data.vehicleId || old.assignmentId !== data.assignmentId ||
          old.litersMl !== data.litersMl || old.occurredAtMillis !== data.occurredAtMillis) {
        throw new CommandError('already-exists', 'Request identifier already used.');
      }
      return { requestId: request.id, status: old.status };
    }
    tx.create(request, {
      tenantId: data.tenantId, driverUid: uid, vehicleId: data.vehicleId,
      assignmentId: data.assignmentId, litersMl: data.litersMl,
      occurredAtMillis: data.occurredAtMillis, status: 'pending', createdAt: serverTimestamp(),
    });
    return { requestId: request.id, status: 'pending' };
  });
}

module.exports = { CommandError, submitFuelRequest };
