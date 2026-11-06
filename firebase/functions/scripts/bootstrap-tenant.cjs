'use strict';
// Run only with an authorized Google identity and explicit confirmation. Never accepts a password or key file.
const ID = /^[A-Za-z0-9_-]{1,128}$/;
async function bootstrapTenant({ db, auth, serverTimestamp }, { tenantId, managerUid }) {
  if (!ID.test(tenantId || '') || !ID.test(managerUid || '')) throw new Error('Invalid tenant ID or manager UID.');
  const manager = await auth.getUser(managerUid);
  if (manager.disabled) throw new Error('Firebase Authentication account is disabled.');
  const tenant = db.collection('tenants').doc(tenantId);
  const managerRef = tenant.collection('users').doc(managerUid);
  const settings = tenant.collection('settings').doc('fuel');
  const audit = tenant.collection('audit').doc('bootstrap');
  return db.runTransaction(async tx => {
    const existing = await tx.get(tenant);
    if (existing.exists) throw new Error('Tenant already exists; bootstrap will not modify it.');
    tx.create(tenant, { active: true, name: 'پخش آل ناصر', timezone: 'Asia/Tehran', vehiclePlateIndexReady: true, createdAt: serverTimestamp() });
    tx.create(managerRef, { active: true, role: 'manager', createdAt: serverTimestamp() });
    tx.create(settings, { version: 1, monthlyQuotaMl: 300000, governmentPriceTomanPerLiter: 300, freePriceTomanPerLiter: 80000, createdAt: serverTimestamp() });
    tx.create(audit, { action: 'tenant.bootstrapped', actorType: 'bootstrap-cli', targetUid: managerUid, createdAt: serverTimestamp() });
    return { tenantId, managerUid };
  });
}

async function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help')) {
    console.log('Usage: node bootstrap-tenant.cjs --project=PROJECT_ID --tenant=TENANT_ID --manager-uid=AUTH_UID --confirm-new-tenant');
    return;
  }
  const allowed = ['--project=', '--tenant=', '--manager-uid='];
  if (!args.includes('--confirm-new-tenant') || args.length !== 4 || allowed.some(prefix => args.filter(a => a.startsWith(prefix)).length !== 1))
    throw new Error('Explicit project, tenant, existing Auth UID and --confirm-new-tenant are required.');
  const value = prefix => args.find(a => a.startsWith(prefix)).slice(prefix.length);
  const projectId = value('--project=');
  if (!/^[a-z][a-z0-9-]{4,29}$/.test(projectId)) throw new Error('Invalid project ID.');
  const { initializeApp, applicationDefault } = require('firebase-admin/app');
  const { getFirestore, FieldValue } = require('firebase-admin/firestore');
  const { getAuth } = require('firebase-admin/auth');
  const app = initializeApp({ credential: applicationDefault(), projectId });
  await bootstrapTenant({ db: getFirestore(app), auth: getAuth(app), serverTimestamp: () => FieldValue.serverTimestamp() },
    { tenantId: value('--tenant='), managerUid: value('--manager-uid=') });
  console.log('Tenant, initial manager and initial fuel settings created.');
}
if (require.main === module) main().catch(error => { console.error('Bootstrap failed:', error.message); process.exitCode = 1; });
module.exports = { bootstrapTenant };
