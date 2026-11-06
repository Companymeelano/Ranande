'use strict';
const { createHash } = require('node:crypto');
const { CommandError } = require('./fuel-request');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function normalizePlate(plate) {
  return plate.normalize('NFKC').replace(/[۰-۹]/g, c => String(c.charCodeAt(0) - 0x06f0))
    .replace(/[٠-٩]/g, c => String(c.charCodeAt(0) - 0x0660))
    .replace(/ي/g, 'ی').replace(/ك/g, 'ک').replace(/[\s\-\u200c\u200f\u200e]/gu, '').toUpperCase();
}
function validate(uid, data) {
  if (typeof uid !== 'string' || !ID.test(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  const keys = ['tenantId', 'vehicleId', 'plate', 'model', 'tankCapacityMl'];
  if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).length !== keys.length || keys.some(k => !Object.hasOwn(data, k)) ||
      typeof data.tenantId !== 'string' || !ID.test(data.tenantId) || typeof data.vehicleId !== 'string' || !UUID.test(data.vehicleId) ||
      typeof data.plate !== 'string' || data.plate.trim() !== data.plate || data.plate.length < 1 || data.plate.length > 40 ||
      typeof data.model !== 'string' || data.model.trim() !== data.model || data.model.length < 1 || data.model.length > 100 ||
      !Number.isSafeInteger(data.tankCapacityMl) || data.tankCapacityMl < 1000 || data.tankCapacityMl > 10000000)
    throw new CommandError('invalid-argument', 'Invalid vehicle command.');
  const canonical = normalizePlate(data.plate);
  if (canonical.length < 1 || canonical.length > 40 || !/^[\p{L}\p{N}]+$/u.test(canonical))
    throw new CommandError('invalid-argument', 'Invalid plate.');
  return canonical;
}
async function createVehicle({ db, serverTimestamp }, uid, data) {
  const canonical = validate(uid, data);
  const tenant = db.collection('tenants').doc(data.tenantId);
  const user = tenant.collection('users').doc(uid);
  const vehicle = tenant.collection('vehicles').doc(data.vehicleId.toLowerCase());
  const lock = tenant.collection('vehiclePlateLocks').doc(createHash('sha256').update(canonical).digest('hex'));
  return db.runTransaction(async tx => {
    const [tenantSnap, userSnap, vehicleSnap, lockSnap] = await Promise.all([tenant, user, vehicle, lock].map(ref => tx.get(ref)));
    const member = userSnap.data();
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !userSnap.exists || member.active !== true || member.role !== 'manager')
      throw new CommandError('permission-denied', 'Manager access required.');
    if (vehicleSnap.exists) {
      const old = vehicleSnap.data();
      if (old.plate !== data.plate || old.model !== data.model || old.tankCapacityMl !== data.tankCapacityMl)
        throw new CommandError('already-exists', 'Vehicle identifier already used.');
      return { vehicleId: vehicle.id, status: 'created' };
    }
    if (tenantSnap.data().vehiclePlateIndexReady !== true) throw new CommandError('failed-precondition', 'Vehicle plate index requires migration.');
    if (lockSnap.exists) throw new CommandError('already-exists', 'Plate is already registered.');
    tx.create(lock, { vehicleId: vehicle.id, canonicalPlate: canonical });
    tx.create(vehicle, { plate: data.plate, model: data.model, tankCapacityMl: data.tankCapacityMl,
      active: true, createdAt: serverTimestamp(), createdBy: uid });
    tx.create(tenant.collection('audit').doc(`vehicle-created-${vehicle.id}`), {
      action: 'vehicle.created', actorUid: uid, vehicleId: vehicle.id, createdAt: serverTimestamp(),
    });
    return { vehicleId: vehicle.id, status: 'created' };
  });
}
module.exports = { createVehicle, normalizePlate };
