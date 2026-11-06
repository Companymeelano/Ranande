'use strict';
const { CommandError } = require('./fuel-request');
const { createHash } = require('node:crypto');
const lockId = (driver, vehicle) => createHash('sha256').update(driver).update('\0').update(vehicle).digest('hex');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function validate(uid, data, revoke) {
  if (typeof uid !== 'string' || !ID.test(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  const keys = revoke ? ['tenantId', 'assignmentId'] : ['tenantId', 'assignmentId', 'driverUid', 'vehicleId'];
  if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).length !== keys.length || keys.some(k => !Object.hasOwn(data, k)))
    throw new CommandError('invalid-argument', 'Invalid assignment command.');
  for (const key of revoke ? ['tenantId'] : ['tenantId', 'driverUid', 'vehicleId'])
    if (typeof data[key] !== 'string' || !ID.test(data[key])) throw new CommandError('invalid-argument', 'Invalid identifier.');
  if (typeof data.assignmentId !== 'string' || !UUID.test(data.assignmentId)) throw new CommandError('invalid-argument', 'Invalid assignment identifier.');
}

async function changeAssignment({ db, serverTimestamp }, uid, data, revoke = false) {
  validate(uid, data, revoke);
  const tenant = db.collection('tenants').doc(data.tenantId);
  const managerRef = tenant.collection('users').doc(uid);
  const assignmentRef = tenant.collection('assignments').doc(data.assignmentId.toLowerCase());
  const driverRef = revoke ? null : tenant.collection('users').doc(data.driverUid);
  const vehicleRef = revoke ? null : tenant.collection('vehicles').doc(data.vehicleId);
  return db.runTransaction(async tx => {
    const refs = [tenant, managerRef, assignmentRef, ...(revoke ? [] : [driverRef, vehicleRef])];
    const [tenantSnap, managerSnap, assignmentSnap, driverSnap, vehicleSnap] = await Promise.all(refs.map(ref => tx.get(ref)));
    const manager = managerSnap.data();
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !managerSnap.exists || manager.active !== true || manager.role !== 'manager')
      throw new CommandError('permission-denied', 'Manager access required.');
    if (revoke) {
      if (!assignmentSnap.exists) throw new CommandError('not-found', 'Assignment not found.');
      const existing = assignmentSnap.data();
      if (existing.active !== true) return { assignmentId: assignmentRef.id, active: false };
      if (typeof existing.driverUid !== 'string' || !ID.test(existing.driverUid) || typeof existing.vehicleId !== 'string' || !ID.test(existing.vehicleId))
        throw new CommandError('failed-precondition', 'Assignment is invalid.');
      const lockRef = tenant.collection('assignmentLocks').doc(lockId(existing.driverUid, existing.vehicleId));
      const lock = await tx.get(lockRef);
      if (lock.exists && lock.data().assignmentId === assignmentRef.id) tx.delete(lockRef);
      tx.update(assignmentRef, { active: false, revokedBy: uid, revokedAt: serverTimestamp() });
      tx.create(tenant.collection('audit').doc(`assignment-revoked-${assignmentRef.id}`), {
        action: 'assignment.revoked', actorUid: uid, assignmentId: assignmentRef.id, createdAt: serverTimestamp(),
      });
      return { assignmentId: assignmentRef.id, active: false };
    }
    if (!driverSnap.exists || driverSnap.data().active !== true || driverSnap.data().role !== 'driver' ||
        !vehicleSnap.exists || vehicleSnap.data().active !== true)
      throw new CommandError('failed-precondition', 'Driver or vehicle is unavailable.');
    const lockRef = tenant.collection('assignmentLocks').doc(lockId(data.driverUid, data.vehicleId));
    const lock = await tx.get(lockRef);
    if (assignmentSnap.exists) {
      const existing = assignmentSnap.data();
      if (existing.driverUid !== data.driverUid || existing.vehicleId !== data.vehicleId)
        throw new CommandError('already-exists', 'Assignment identifier already used.');
      return { assignmentId: assignmentRef.id, active: existing.active === true };
    }
    if (lock.exists) throw new CommandError('already-exists', 'An active assignment already exists.');
    tx.create(lockRef, { assignmentId: assignmentRef.id, driverUid: data.driverUid, vehicleId: data.vehicleId });
    tx.create(assignmentRef, { driverUid: data.driverUid, vehicleId: data.vehicleId, active: true, createdBy: uid, createdAt: serverTimestamp() });
    tx.create(tenant.collection('audit').doc(`assignment-created-${assignmentRef.id}`), {
      action: 'assignment.created', actorUid: uid, assignmentId: assignmentRef.id, createdAt: serverTimestamp(),
    });
    return { assignmentId: assignmentRef.id, active: true };
  });
}
module.exports = { changeAssignment };
