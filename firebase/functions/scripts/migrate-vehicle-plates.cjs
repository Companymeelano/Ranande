'use strict';
const { createHash } = require('node:crypto');
const { normalizePlate } = require('../vehicles');
const ID = /^[A-Za-z0-9_-]{1,128}$/;
const LIMIT = 200;

// Run during a maintenance window with all privileged vehicle writers stopped.
async function migratePlateIndex({ db, serverTimestamp }, tenantId) {
  if (typeof tenantId !== 'string' || !ID.test(tenantId)) throw new Error('Invalid tenant ID.');
  const tenant = db.collection('tenants').doc(tenantId);
  return db.runTransaction(async tx => {
    const tenantSnap = await tx.get(tenant);
    if (!tenantSnap.exists) throw new Error('Tenant does not exist.');
    if (tenantSnap.data().vehiclePlateIndexReady === true) return { status: 'already-ready' };
    const vehicles = await tx.get(tenant.collection('vehicles').limit(LIMIT + 1));
    if (vehicles.size > LIMIT) throw new Error('Too many vehicles for this migration; manual migration required.');
    const seen = new Map();
    const mappings = vehicles.docs.map(doc => {
      const plate = doc.data().plate;
      if (typeof plate !== 'string' || plate.length < 1 || plate.length > 40) throw new Error('A vehicle has an invalid plate.');
      const canonical = normalizePlate(plate);
      if (canonical.length < 1 || canonical.length > 40 || !/^[\p{L}\p{N}]+$/u.test(canonical)) throw new Error('A vehicle has an invalid plate.');
      if (seen.has(canonical)) throw new Error('Duplicate normalized plates require manual resolution.');
      seen.set(canonical, doc.id);
      return { id: doc.id, canonical, lock: tenant.collection('vehiclePlateLocks').doc(createHash('sha256').update(canonical).digest('hex')) };
    });
    const locks = await Promise.all(mappings.map(item => tx.get(item.lock)));
    const audit = tenant.collection('audit').doc('vehicle-plate-index-v1');
    const auditSnap = await tx.get(audit);
    if (auditSnap.exists) throw new Error('Migration audit already exists; manual review required.');
    for (let i = 0; i < mappings.length; i++) {
      const item = mappings[i], lock = locks[i];
      if (lock.exists && (lock.data().vehicleId !== item.id || lock.data().canonicalPlate !== item.canonical))
        throw new Error('Conflicting plate lock requires manual review.');
    }
    for (let i = 0; i < mappings.length; i++) {
      const item = mappings[i];
      if (!locks[i].exists) tx.create(item.lock, { vehicleId: item.id, canonicalPlate: item.canonical });
    }
    tx.update(tenant, { vehiclePlateIndexReady: true });
    tx.create(audit, { action: 'vehicle.plate_index_initialized', actorType: 'migration-cli', count: mappings.length, createdAt: serverTimestamp() });
    return { status: 'migrated', count: mappings.length };
  });
}
async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) { console.log('Usage: node migrate-vehicle-plates.cjs --project=PROJECT_ID --tenant=TENANT_ID --confirm-maintenance'); return; }
  const prefixes = ['--project=', '--tenant='];
  if (args.length !== 3 || !args.includes('--confirm-maintenance') || prefixes.some(p => args.filter(a => a.startsWith(p)).length !== 1))
    throw new Error('Explicit project, tenant and maintenance confirmation are required.');
  const value = prefix => args.find(a => a.startsWith(prefix)).slice(prefix.length);
  const projectId = value('--project=');
  if (!/^[a-z][a-z0-9-]{4,29}$/.test(projectId)) throw new Error('Invalid project ID.');
  const { initializeApp, applicationDefault } = require('firebase-admin/app');
  const { getFirestore, FieldValue } = require('firebase-admin/firestore');
  const app = initializeApp({ projectId, credential: applicationDefault() });
  const result = await migratePlateIndex({ db: getFirestore(app), serverTimestamp: () => FieldValue.serverTimestamp() }, value('--tenant='));
  console.log(JSON.stringify(result));
}
if (require.main === module) main().catch(error => { console.error('Migration failed:', error.message); process.exitCode = 1; });
module.exports = { migratePlateIndex };
