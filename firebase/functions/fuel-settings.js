'use strict';
const { CommandError } = require('./fuel-request');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const FIELDS = ['tenantId', 'commandId', 'expectedVersion', 'monthlyQuotaMl', 'governmentPriceTomanPerLiter', 'freePriceTomanPerLiter'];
const policy = s => ({ version: s.version, monthlyQuotaMl: s.monthlyQuotaMl, governmentPriceTomanPerLiter: s.governmentPriceTomanPerLiter, freePriceTomanPerLiter: s.freePriceTomanPerLiter });
function validPolicy(s) {
  return Number.isSafeInteger(s.version) && s.version >= 1 && s.version < Number.MAX_SAFE_INTEGER &&
    Number.isSafeInteger(s.monthlyQuotaMl) && s.monthlyQuotaMl >= 0 && s.monthlyQuotaMl <= 10000000 &&
    Number.isSafeInteger(s.governmentPriceTomanPerLiter) && s.governmentPriceTomanPerLiter >= 0 && s.governmentPriceTomanPerLiter <= 100000000 &&
    Number.isSafeInteger(s.freePriceTomanPerLiter) && s.freePriceTomanPerLiter >= 0 && s.freePriceTomanPerLiter <= 100000000;
}
async function updateFuelSettings({ db, serverTimestamp }, uid, data) {
  if (typeof uid !== 'string' || !ID.test(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  if (!data || typeof data !== 'object' || Array.isArray(data) || Object.keys(data).length !== FIELDS.length ||
      FIELDS.some(k => !Object.hasOwn(data, k)) || typeof data.tenantId !== 'string' || !ID.test(data.tenantId) ||
      typeof data.commandId !== 'string' || !UUID.test(data.commandId) || !validPolicy({ ...data, version: data.expectedVersion }))
    throw new CommandError('invalid-argument', 'Invalid settings command.');
  const tenant = db.collection('tenants').doc(data.tenantId);
  const settings = tenant.collection('settings').doc('fuel');
  const operation = tenant.collection('fuelSettingsChanges').doc(data.commandId.toLowerCase());
  return db.runTransaction(async tx => {
    const [tenantSnap, userSnap, currentSnap, opSnap] = await Promise.all([
      tenant, tenant.collection('users').doc(uid), settings, operation,
    ].map(ref => tx.get(ref)));
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !userSnap.exists || userSnap.data().active !== true || userSnap.data().role !== 'manager')
      throw new CommandError('permission-denied', 'Manager access required.');
    if (opSnap.exists) {
      const op = opSnap.data();
      if (op.actorUid !== uid || FIELDS.some(k => op.input[k] !== data[k])) throw new CommandError('already-exists', 'Command identifier already used.');
      return { commandId: operation.id, status: 'updated', settings: op.after };
    }
    if (!currentSnap.exists || !validPolicy(currentSnap.data())) throw new CommandError('failed-precondition', 'Current settings are invalid.');
    const before = policy(currentSnap.data());
    if (before.version !== data.expectedVersion) throw new CommandError('failed-precondition', 'Settings changed; refresh and review.');
    const after = { version: before.version + 1, monthlyQuotaMl: data.monthlyQuotaMl,
      governmentPriceTomanPerLiter: data.governmentPriceTomanPerLiter, freePriceTomanPerLiter: data.freePriceTomanPerLiter };
    tx.update(settings, { ...after, updatedBy: uid, updatedAt: serverTimestamp() });
    tx.create(operation, { actorUid: uid, input: data, before, after, createdAt: serverTimestamp() });
    tx.create(tenant.collection('audit').doc(`fuel-settings-${operation.id}`), { action: 'fuel.settings_updated', actorUid: uid, commandId: operation.id, createdAt: serverTimestamp() });
    return { commandId: operation.id, status: 'updated', settings: after };
  });
}
module.exports = { updateFuelSettings, validPolicy };
