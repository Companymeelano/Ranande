'use strict';
const { CommandError } = require('./fuel-request');

const ID = /^[A-Za-z0-9_-]{1,128}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const quoteFields = ['monthKey', 'settingsVersion', 'quotaVersion', 'allocatedMl', 'usedMl', 'governmentMl', 'freeMl', 'governmentRate', 'freeRate', 'governmentAmount', 'freeAmount', 'totalAmount'];

function persianMonth(now) {
  const parts = new Intl.DateTimeFormat('en-US-u-ca-persian-nu-latn', {
    timeZone: 'Asia/Tehran', year: 'numeric', month: '2-digit',
  }).formatToParts(now);
  const year = parts.find(part => part.type === 'year')?.value;
  const month = parts.find(part => part.type === 'month')?.value;
  if (!/^\d{4}$/.test(year || '') || !/^\d{2}$/.test(month || ''))
    throw new CommandError('failed-precondition', 'Unable to determine Persian month.');
  return year + month;
}
function safeNumber(value, name, min = 0) {
  if (!Number.isSafeInteger(value) || value < min) throw new CommandError('failed-precondition', `Invalid ${name}.`);
  return value;
}
function roundedAmount(ml, rate) {
  const value = (BigInt(ml) * BigInt(rate) + 500n) / 1000n;
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new CommandError('failed-precondition', 'Amount exceeds supported range.');
  return Number(value);
}
function computeQuote(request, settings, quota, monthKey) {
  const volume = safeNumber(request.litersMl, 'volume', 1);
  const settingsVersion = safeNumber(settings.version, 'settings version', 1);
  const governmentRate = safeNumber(settings.governmentPriceTomanPerLiter, 'government rate');
  const freeRate = safeNumber(settings.freePriceTomanPerLiter, 'free rate');
  const allocatedMl = safeNumber(quota ? quota.allocatedMl : settings.monthlyQuotaMl, 'allocation');
  const usedMl = safeNumber(quota ? quota.usedMl : 0, 'quota usage');
  const quotaVersion = safeNumber(quota ? quota.version : 0, 'quota version');
  if (usedMl > allocatedMl) throw new CommandError('failed-precondition', 'Invalid quota balance.');
  const governmentMl = Math.min(volume, allocatedMl - usedMl);
  const freeMl = volume - governmentMl;
  const governmentAmount = roundedAmount(governmentMl, governmentRate);
  const freeAmount = roundedAmount(freeMl, freeRate);
  const totalAmount = governmentAmount + freeAmount;
  if (!Number.isSafeInteger(totalAmount)) throw new CommandError('failed-precondition', 'Amount exceeds supported range.');
  return { monthKey, settingsVersion, quotaVersion, allocatedMl, usedMl, governmentMl, freeMl,
    governmentRate, freeRate, governmentAmount, freeAmount, totalAmount };
}
function validateInput(uid, input, approve) {
  if (!uid || typeof uid !== 'string' || !ID.test(uid)) throw new CommandError('unauthenticated', 'Authentication required.');
  const fields = approve ? ['tenantId', 'requestId', 'quote'] : ['tenantId', 'requestId'];
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).length !== fields.length ||
      fields.some(key => !Object.hasOwn(input, key)) || typeof input.tenantId !== 'string' || !ID.test(input.tenantId) ||
      typeof input.requestId !== 'string' || !UUID.test(input.requestId)) throw new CommandError('invalid-argument', 'Invalid command.');
  if (approve && (!input.quote || typeof input.quote !== 'object' || Array.isArray(input.quote) ||
      Object.keys(input.quote).length !== quoteFields.length || quoteFields.some(key => !Object.hasOwn(input.quote, key))))
    throw new CommandError('invalid-argument', 'Invalid quote.');
}

/** Quote is always computed on the server. Confirmation recomputes it atomically and rejects stale quotes. */
async function fuelApproval({ db, serverTimestamp, now = () => new Date() }, uid, input, approve) {
  validateInput(uid, input, approve);
  const tenant = db.collection('tenants').doc(input.tenantId);
  const user = tenant.collection('users').doc(uid);
  const requestRef = tenant.collection('fuelRequests').doc(input.requestId.toLowerCase());
  const ledgerRef = tenant.collection('fuelTransactions').doc(requestRef.id);
  const settingsRef = tenant.collection('settings').doc('fuel');
  // The service clock defines the approval month; offline occurrence time is not authoritative.
  const monthKey = persianMonth(now());
  return db.runTransaction(async tx => {
    const [tenantSnap, userSnap, requestSnap, ledgerSnap, settingsSnap] =
      await Promise.all([tenant, user, requestRef, ledgerRef, settingsRef].map(ref => tx.get(ref)));
    const membership = userSnap.data();
    if (!tenantSnap.exists || tenantSnap.data().active !== true || !userSnap.exists || membership.active !== true ||
        !['manager', 'accountant'].includes(membership.role)) throw new CommandError('permission-denied', 'Approval is not authorized.');
    if (ledgerSnap.exists) {
      if (!approve) throw new CommandError('failed-precondition', 'Request is already approved.');
      return { requestId: requestRef.id, status: 'approved', quote: ledgerSnap.data().quote };
    }
    if (!requestSnap.exists || requestSnap.data().status !== 'pending') throw new CommandError('failed-precondition', 'Request is not pending.');
    const request = requestSnap.data();
    if (typeof request.vehicleId !== 'string' || !ID.test(request.vehicleId) || !settingsSnap.exists)
      throw new CommandError('failed-precondition', 'Vehicle or fuel settings are invalid.');
    const quotaRef = tenant.collection('quotas').doc(`${request.vehicleId}_${monthKey}`);
    const quotaSnap = await tx.get(quotaRef);
    const quota = quotaSnap.exists ? quotaSnap.data() : null;
    if (quota && (quota.vehicleId !== request.vehicleId || quota.monthKey !== monthKey))
      throw new CommandError('failed-precondition', 'Quota does not match vehicle and month.');
    const quote = computeQuote(request, settingsSnap.data(), quota, monthKey);
    if (!approve) return { requestId: requestRef.id, quote };
    if (quoteFields.some(key => input.quote[key] !== quote[key]))
      throw new CommandError('failed-precondition', 'Quote changed; review and confirm again.');
    if (quote.quotaVersion >= Number.MAX_SAFE_INTEGER) throw new CommandError('failed-precondition', 'Quota version exceeded.');
    const nextQuota = {
      vehicleId: request.vehicleId, monthKey, allocatedMl: quote.allocatedMl,
      usedMl: quote.usedMl + quote.governmentMl, version: quote.quotaVersion + 1,
      updatedAt: serverTimestamp(),
    };
    if (quotaSnap.exists) tx.update(quotaRef, nextQuota); else tx.create(quotaRef, nextQuota);
    tx.create(ledgerRef, {
      tenantId: input.tenantId, requestId: requestRef.id, vehicleId: request.vehicleId,
      driverUid: request.driverUid, litersMl: request.litersMl, quote,
      approvedBy: uid, approvedAt: serverTimestamp(),
    });
    tx.update(requestRef, { status: 'approved', approvedBy: uid, approvedAt: serverTimestamp() });
    tx.create(tenant.collection('audit').doc(`fuel-approval-${requestRef.id}`), {
      actorUid: uid, action: 'fuel.approved', requestId: requestRef.id, createdAt: serverTimestamp(),
    });
    return { requestId: requestRef.id, status: 'approved', quote };
  });
}
module.exports = { persianMonth, computeQuote, fuelApproval };
