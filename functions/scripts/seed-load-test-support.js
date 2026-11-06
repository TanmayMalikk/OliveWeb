// Seeds deterministic support work for the fictional load-test accounts.
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}

const app = initializeOliveAdminApp({projectId}, "load-test-support");
const auth = getAuth(app);
const firestore = getFirestore(app);

function documentWrite(collection, documentId, value, mergeFields = []) {
  return {
    reference: firestore.collection(collection).doc(documentId),
    value,
    options: mergeFields.length ? {mergeFields} : undefined,
  };
}

async function commit(writes) {
  for (let index = 0; index < writes.length; index += 500) {
    const batch = firestore.batch();
    writes.slice(index, index + 500).forEach(({reference, value, options}) => {
      if (options) batch.set(reference, value, options);
      else batch.set(reference, value);
    });
    await batch.commit();
  }
}

async function batchGet(references) {
  const snapshots = [];
  for (let index = 0; index < references.length; index += 100) {
    snapshots.push(...await firestore.getAll(...references.slice(index, index + 100)));
  }
  return snapshots;
}

async function accountRecord(fixture) {
  const email = signInEmail(fixture.username, fixture.role, fixture.email);
  try {
    const user = await auth.getUserByEmail(email);
    return {...fixture, uid: user.uid};
  } catch (error) {
    if (error.code === "auth/user-not-found") {
      throw new Error(`${fixture.username} is missing. Run npm run provision-load-test-accounts first.`);
    }
    throw error;
  }
}

function planFields(index) {
  const number = index + 1;
  const values = {
    primary_destination: `Load test ${number}: primary safe destination`,
    primary_travel: `Load test ${number}: primary travel route`,
    primary_contacts: `Load test ${number}: trusted contacts`,
    primary_essentials: `Load test ${number}: essential items`,
    primary_dependants: `Load test ${number}: dependant arrangements`,
    primary_aftercare: `Load test ${number}: aftercare notes`,
    primary_documents: `Load test ${number}: important documents`,
    primary_grab: `Load test ${number}: quick-grab checklist`,
    backup_destination: `Load test ${number}: backup destination`,
    backup_travel: `Load test ${number}: backup travel route`,
    backup_contacts: `Load test ${number}: backup contacts`,
    backup_essentials: `Load test ${number}: backup essentials`,
    backup_dependants: `Load test ${number}: backup dependant arrangements`,
    backup_aftercare: `Load test ${number}: backup aftercare notes`,
    backup_documents: `Load test ${number}: backup documents`,
    backup_grab: `Load test ${number}: backup quick-grab checklist`,
  };
  return Object.fromEntries(Object.entries(values).map(([key, value]) => [
    key, {value, revision: 1, mutationId: "load-test"},
  ]));
}

function conversation(id, owner, staff, origin, summary) {
  return {
    type: "SUPPORT",
    ownerUid: owner.uid,
    staffUid: staff.uid,
    adminUid: "",
    supportOrigin: origin,
    supportTag: origin,
    friendshipId: id,
    participantIds: [owner.uid, staff.uid],
    participantUsernames: {[owner.uid]: owner.username, [staff.uid]: staff.username},
    createdAt: new Date(),
    updatedAt: new Date(),
    lastMessage: summary,
    lastMessageId: `${id}-request`,
    lastSenderUid: owner.uid,
  };
}

async function seed() {
  const users = await Promise.all(accounts.filter(value => value.role === "USER").map(accountRecord));
  const staff = await Promise.all(accounts.filter(value => value.role === "STAFF").map(accountRecord));
  const safeContacts = await Promise.all(accounts.filter(value => value.role === "SAFE_CONTACT").map(accountRecord));
  if (users.length !== 40 || staff.length !== 8 || safeContacts.length !== 5) {
    throw new Error("Expected 40 users, 8 staff, and 5 safe contacts.");
  }

  const writes = [];
  const activeCounts = new Map(staff.map(value => [value.uid, 0]));
  users.forEach((owner, index) => {
    const assigned = staff[index % staff.length];
    const safeContact = safeContacts[index % safeContacts.length];
    const planConversationId = `plan-consult-${owner.uid}-load-test`;
    const supportConversationId = `support-${owner.uid}-load-test`;
    const now = Date.now() + index;

    writes.push(documentWrite("safetyPlans", owner.uid, {
      ownerUid: owner.uid,
      ownerName: owner.fullName,
      staffUid: assigned.uid,
      staffName: assigned.fullName,
      adminUid: "",
      adminName: "",
      consultationRequested: true,
      consultationConversationId: planConversationId,
      safeContactUids: [safeContact.uid],
      safeContactNames: {[safeContact.uid]: safeContact.fullName},
      supportSafeContactUid: "",
      fields: planFields(index),
      updatedAt: now,
    }));
    writes.push(documentWrite("supportRequests", owner.uid, {
      ownerUid: owner.uid,
      ownerName: owner.fullName,
      requested: true,
      staffUid: assigned.uid,
      staffName: assigned.fullName,
      adminUid: "",
      adminName: "",
      supportOrigin: "SUPPORT",
      conversationId: supportConversationId,
      updatedAt: now,
    }));
    writes.push(documentWrite("conversations", planConversationId, conversation(
        planConversationId, owner, assigned, "PLAN_CONSULT", "New load-test plan consultation",
    )));
    writes.push(documentWrite("conversations", supportConversationId, conversation(
        supportConversationId, owner, assigned, "SUPPORT", "New load-test support request",
    )));
    activeCounts.set(assigned.uid, activeCounts.get(assigned.uid) + 2);
  });
  staff.forEach(value => writes.push(documentWrite("safetyStaff", value.uid, {
    available: true,
    activeCount: activeCounts.get(value.uid),
  }, ["available", "activeCount"])));
  await commit(writes);

  const expectedPaths = [
    ...users.flatMap(owner => [
      `safetyPlans/${owner.uid}`,
      `supportRequests/${owner.uid}`,
      `conversations/plan-consult-${owner.uid}-load-test`,
      `conversations/support-${owner.uid}-load-test`,
    ]),
    ...staff.map(value => `safetyStaff/${value.uid}`),
  ];
  const results = await batchGet(expectedPaths.map(path => firestore.doc(path)));
  const found = new Map(results.filter(snapshot => snapshot.exists).map(snapshot => [snapshot.ref.path, snapshot]));
  const missing = expectedPaths.filter(path => !found.has(path));
  if (missing.length) throw new Error(`Seed verification is missing ${missing.length} documents.`);
  const badStaff = staff.filter(value =>
    found.get(`safetyStaff/${value.uid}`)?.get("activeCount") !== 10);
  const badPlans = users.filter(owner => {
    const plan = found.get(`safetyPlans/${owner.uid}`)?.data();
    return plan?.consultationRequested !== true || plan?.safeContactUids?.length !== 1;
  });
  if (badStaff.length || badPlans.length) {
    throw new Error(`Seed verification failed for ${badStaff.length} staff records and ${badPlans.length} plans.`);
  }
  console.log(`Seeded ${users.length} plan consultations and ${users.length} support requests.`);
  console.log(`Shared ${users.length} safety plans with staff and rotating safe contacts.`);
  console.log(`Each of ${staff.length} staff accounts has 5 plan consultations and 5 support requests.`);
  console.log(`Verified ${expectedPaths.length} persisted Firestore documents.`);
}

seed().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
