'use strict';
const { CommandError } = require('./fuel-request');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const text = (v, max, optional = false) => typeof v === 'string' && v === v.trim() && v.length <= max &&
  (optional || v.length > 0) && !/[\p{Cc}\p{Cs}]/u.test(v);
function validate(uid, input) {
  if (typeof uid !== 'string' || !ID.test(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  const fields = ['tenantId', 'customerId', 'name', 'address', 'phone'];
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).length !== fields.length ||
      fields.some(k => !Object.hasOwn(input, k)) || typeof input.tenantId !== 'string' || !ID.test(input.tenantId) ||
      typeof input.customerId !== 'string' || !UUID.test(input.customerId) || !text(input.name, 100) || !text(input.address, 300) ||
      !text(input.phone, 30, true) || !/^[+()\-\s0-9۰-۹٠-٩]*$/u.test(input.phone))
    throw new CommandError('invalid-argument', 'Invalid customer command.');
}
async function createCustomer({ db, serverTimestamp }, uid, input) {
  validate(uid, input);
  const tenant = db.collection('tenants').doc(input.tenantId);
  const customer = tenant.collection('customers').doc(input.customerId.toLowerCase());
  return db.runTransaction(async tx => {
    const [tenantSnap, userSnap, customerSnap] = await Promise.all([tenant, tenant.collection('users').doc(uid), customer].map(ref => tx.get(ref)));
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !userSnap.exists || userSnap.data().active !== true || userSnap.data().role !== 'manager')
      throw new CommandError('permission-denied', 'Manager access required.');
    if (customerSnap.exists) {
      const old = customerSnap.data();
      if (old.createdBy !== uid || old.name !== input.name || old.address !== input.address || old.phone !== input.phone)
        throw new CommandError('already-exists', 'Customer identifier already used.');
      return { customerId: customer.id, status: 'created' };
    }
    tx.create(customer, { name: input.name, address: input.address, phone: input.phone, active: true, createdBy: uid, createdAt: serverTimestamp() });
    tx.create(tenant.collection('audit').doc(`customer-created-${customer.id}`), { action: 'customer.created', actorUid: uid, customerId: customer.id, createdAt: serverTimestamp() });
    return { customerId: customer.id, status: 'created' };
  });
}
module.exports = { createCustomer };
