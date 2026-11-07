'use strict';
const { CommandError } = require('./fuel-request');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const validId = x => typeof x === 'string' && ID.test(x);
const exact = (o, keys) => o && typeof o === 'object' && !Array.isArray(o) && Object.keys(o).length === keys.length && keys.every(k => Object.hasOwn(o, k));
function authorize(uid, data, keys) {
  if (!validId(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  if (!exact(data, keys) || !validId(data.tenantId) || typeof data.missionId !== 'string' || !UUID.test(data.missionId))
    throw new CommandError('invalid-argument', 'Invalid mission command.');
}
function activeMember(tenant, member) { return tenant.exists && tenant.data().active === true && member.exists && member.data().active === true; }
async function createMission({ db, serverTimestamp }, uid, data) {
  authorize(uid, data, ['tenantId', 'missionId', 'assignmentId', 'customerIds', 'goods']);
  if (!validId(data.assignmentId) || !Array.isArray(data.customerIds) || data.customerIds.length < 1 || data.customerIds.length > 20 ||
      data.customerIds.some(id => !validId(id)) || new Set(data.customerIds).size !== data.customerIds.length ||
      typeof data.goods !== 'string' || data.goods.trim() !== data.goods || data.goods.length < 1 || data.goods.length > 200 || /[\p{Cc}\p{Cs}]/u.test(data.goods))
    throw new CommandError('invalid-argument', 'Invalid mission details.');
  const tenant = db.collection('tenants').doc(data.tenantId);
  const ref = tenant.collection('missions').doc(data.missionId.toLowerCase());
  return db.runTransaction(async tx => {
    const [ts, actor, existing] = await Promise.all([tenant, tenant.collection('users').doc(uid), ref].map(r => tx.get(r)));
    if (!activeMember(ts, actor) || actor.data().role !== 'manager') throw new CommandError('permission-denied', 'Manager access required.');
    if (existing.exists) {
      const m = existing.data();
      if (m.createdBy !== uid || m.assignmentId !== data.assignmentId || m.goods !== data.goods || JSON.stringify(m.customerIds) !== JSON.stringify(data.customerIds))
        throw new CommandError('already-exists', 'Mission identifier already used.');
      return { missionId: ref.id, status: m.status };
    }
    const assignment = await tx.get(tenant.collection('assignments').doc(data.assignmentId));
    const a = assignment.data();
    if (!assignment.exists || a.active !== true || !validId(a.driverUid) || !validId(a.vehicleId))
      throw new CommandError('failed-precondition', 'Assignment is unavailable.');
    const [driver, vehicle, ...customers] = await Promise.all([
      tenant.collection('users').doc(a.driverUid), tenant.collection('vehicles').doc(a.vehicleId),
      ...data.customerIds.map(id => tenant.collection('customers').doc(id)),
    ].map(r => tx.get(r)));
    if (!driver.exists || driver.data().active !== true || driver.data().role !== 'driver' || !vehicle.exists || vehicle.data().active !== true ||
        customers.some(c => !c.exists || c.data().active !== true || typeof c.data().name !== 'string' || typeof c.data().address !== 'string' || typeof c.data().phone !== 'string'))
      throw new CommandError('failed-precondition', 'Driver, vehicle or customer is unavailable.');
    const destinations = customers.map(c => ({ id: c.id, name: c.data().name, address: c.data().address, phone: c.data().phone }));
    tx.create(ref, { assignmentId: data.assignmentId, driverUid: a.driverUid, vehicleId: a.vehicleId, customerIds: data.customerIds,
      destinations, goods: data.goods, status: 'planned', createdBy: uid, createdAt: serverTimestamp() });
    tx.create(tenant.collection('audit').doc(`mission-created-${ref.id}`), { action: 'mission.created', actorUid: uid, missionId: ref.id, createdAt: serverTimestamp() });
    return { missionId: ref.id, status: 'planned' };
  });
}
async function transitionMission({ db, serverTimestamp }, uid, data) {
  authorize(uid, data, ['tenantId', 'missionId', 'action']);
  if (!['start', 'finish'].includes(data.action)) throw new CommandError('invalid-argument', 'Invalid mission action.');
  const tenant = db.collection('tenants').doc(data.tenantId), ref = tenant.collection('missions').doc(data.missionId.toLowerCase());
  return db.runTransaction(async tx => {
    const [ts, actor, mission] = await Promise.all([tenant, tenant.collection('users').doc(uid), ref].map(r => tx.get(r)));
    if (!activeMember(ts, actor) || !mission.exists) throw new CommandError('permission-denied', 'Mission unavailable.');
    const m = mission.data(), role = actor.data().role;
    if (!(role === 'driver' && m.driverUid === uid) && !(data.action === 'finish' && role === 'manager'))
      throw new CommandError('permission-denied', 'Mission action not authorized.');
    if (!validId(m.driverUid) || !validId(m.vehicleId) || !validId(m.assignmentId)) throw new CommandError('failed-precondition', 'Invalid mission.');
    const target = data.action === 'start' ? 'active' : 'finished';
    if (m.status === target) return { missionId: ref.id, status: target };
    if (m.status !== (data.action === 'start' ? 'planned' : 'active')) throw new CommandError('failed-precondition', 'Mission state changed.');
    const driverLock = tenant.collection('missionLocks').doc(`driver-${m.driverUid}`);
    const vehicleLock = tenant.collection('missionLocks').doc(`vehicle-${m.vehicleId}`);
    const [assignment, driver, vehicle, dl, vl] = await Promise.all([
      tenant.collection('assignments').doc(m.assignmentId), tenant.collection('users').doc(m.driverUid), tenant.collection('vehicles').doc(m.vehicleId), driverLock, vehicleLock,
    ].map(r => tx.get(r)));
    if (data.action === 'start') {
      const a = assignment.data();
      if (!assignment.exists || a.active !== true || a.driverUid !== m.driverUid || a.vehicleId !== m.vehicleId ||
          !driver.exists || driver.data().active !== true || driver.data().role !== 'driver' || !vehicle.exists || vehicle.data().active !== true)
        throw new CommandError('failed-precondition', 'Assignment, driver or vehicle is unavailable.');
      if (dl.exists || vl.exists) throw new CommandError('already-exists', 'Driver or vehicle has an active mission.');
      tx.create(driverLock, { missionId: ref.id }); tx.create(vehicleLock, { missionId: ref.id });
      tx.update(ref, { status: target, startedAt: serverTimestamp(), startedBy: uid });
    } else {
      if (!dl.exists || !vl.exists || dl.data().missionId !== ref.id || vl.data().missionId !== ref.id)
        throw new CommandError('failed-precondition', 'Mission locks require review.');
      tx.delete(driverLock); tx.delete(vehicleLock);
      tx.update(ref, { status: target, finishedAt: serverTimestamp(), finishedBy: uid });
    }
    tx.create(tenant.collection('audit').doc(`mission-${data.action}-${ref.id}`), { action: `mission.${data.action}`, actorUid: uid, missionId: ref.id, createdAt: serverTimestamp() });
    return { missionId: ref.id, status: target };
  });
}
/** A driver's statement, not independent proof of delivery. Records are immutable. */
async function recordMissionDelivery({ db, serverTimestamp, now = () => new Date() }, uid, data) {
  authorize(uid, data, ['tenantId', 'missionId', 'customerId', 'outcome', 'recipient', 'note']);
  const text = (v, max) => typeof v === 'string' && v === v.trim() && v.length <= max && !/[\p{Cc}\p{Cs}]/u.test(v);
  if (!validId(data.customerId) || !['delivered', 'not_delivered'].includes(data.outcome) ||
      !text(data.recipient, 100) || !text(data.note, 300) ||
      (data.outcome === 'delivered' && !data.recipient) || (data.outcome === 'not_delivered' && !data.note) ||
      (data.outcome === 'not_delivered' && data.recipient))
    throw new CommandError('invalid-argument', 'Invalid destination result.');
  const tenant = db.collection('tenants').doc(data.tenantId), ref = tenant.collection('missions').doc(data.missionId.toLowerCase());
  const recordedAt = now();
  if (!(recordedAt instanceof Date) || !Number.isFinite(recordedAt.getTime())) throw new CommandError('internal', 'Clock unavailable.');
  return db.runTransaction(async tx => {
    const [ts, actor, mission] = await Promise.all([tenant, tenant.collection('users').doc(uid), ref].map(r => tx.get(r)));
    if (!activeMember(ts, actor) || actor.data().role !== 'driver' || !mission.exists || mission.data().driverUid !== uid)
      throw new CommandError('permission-denied', 'Driver access required.');
    const m = mission.data();
    if (!Array.isArray(m.customerIds) || m.customerIds.length < 1 || m.customerIds.length > 20 ||
        new Set(m.customerIds).size !== m.customerIds.length || !m.customerIds.every(validId) || !m.customerIds.includes(data.customerId) ||
        (m.deliveryResults !== undefined && !Array.isArray(m.deliveryResults)))
      throw new CommandError('failed-precondition', 'Mission destinations are invalid.');
    const results = m.deliveryResults || [];
    if (results.length > m.customerIds.length || results.some(r => !r || !m.customerIds.includes(r.customerId)) ||
        new Set(results.map(r => r.customerId)).size !== results.length)
      throw new CommandError('failed-precondition', 'Destination results are invalid.');
    const previous = results.find(r => r.customerId === data.customerId);
    if (previous) {
      if (previous.recordedBy !== uid || previous.outcome !== data.outcome || previous.recipient !== data.recipient || previous.note !== data.note)
        throw new CommandError('already-exists', 'Destination already has a result.');
      return { missionId: ref.id, customerId: data.customerId, status: 'recorded' };
    }
    if (m.status !== 'active' || !validId(m.assignmentId) || !validId(m.vehicleId))
      throw new CommandError('failed-precondition', 'Mission is not active.');
    const assignment = await tx.get(tenant.collection('assignments').doc(m.assignmentId));
    if (!assignment.exists || assignment.data().active !== true || assignment.data().driverUid !== uid || assignment.data().vehicleId !== m.vehicleId)
      throw new CommandError('failed-precondition', 'Assignment is no longer active.');
    const result = { customerId: data.customerId, outcome: data.outcome, recipient: data.recipient, note: data.note, recordedBy: uid, recordedAt };
    tx.update(ref, { deliveryResults: [...results, result] });
    tx.create(tenant.collection('audit').doc(`mission-delivery-${ref.id}-${data.customerId}`), {
      action: 'mission.destination_reported', actorUid: uid, missionId: ref.id, customerId: data.customerId, outcome: data.outcome, createdAt: serverTimestamp(),
    });
    return { missionId: ref.id, customerId: data.customerId, status: 'recorded' };
  });
}
module.exports = { createMission, transitionMission, recordMissionDelivery };
