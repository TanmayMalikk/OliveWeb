// Seeds support tickets, safety-plan consultations, and accepted contacts for the large load test.
const {getAuth} = require("firebase-admin/auth");
const {getFirestore} = require("firebase-admin/firestore");
const {signInEmail} = require("../src/accountPolicy");
const {accounts, COUNTS} = require("./load-test-account-fixtures");
const {initializeOliveAdminApp} = require("./firebase-admin-app");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
const completionRatio = 0.55;
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}

const app = initializeOliveAdminApp({projectId}, "load-test-support");
const auth = getAuth(app);
const firestore = getFirestore(app);

// Describes one Firestore write so large fixture sets can be committed in safe 500-write batches.
function documentWrite(path, value) {
  return {reference: firestore.doc(path), value};
}

// Keeps every Firestore batch below the platform's write limit.
async function commit(writes) {
  for (let index = 0; index < writes.length; index += 500) {
    const batch = firestore.batch();
    writes.slice(index, index + 500).forEach(({reference, value}) => batch.set(reference, value));
    await batch.commit();
  }
}

// Reads large reference lists in smaller groups accepted by the Admin SDK.
async function batchGet(references) {
  const snapshots = [];
  for (let index = 0; index < references.length; index += 100) {
    snapshots.push(...await firestore.getAll(...references.slice(index, index + 100)));
  }
  return snapshots;
}

// Finds the Auth UID that belongs to one fixture and explains how to repair a missing account.
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

// Resolves accounts in controlled groups so 360 Auth lookups do not flood the API.
async function accountRecords(fixtures) {
  const records = [];
  for (let index = 0; index < fixtures.length; index += 25) {
    records.push(...await Promise.all(fixtures.slice(index, index + 25).map(accountRecord)));
  }
  return records;
}

// Gives each generated safety plan realistic populated sections for staff/admin UI testing.
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

// Marks exactly 55% of users complete while spreading completed records across the full list.
function completed(index, multiplier, offset) {
  const target = Math.round(COUNTS.USER * completionRatio);
  return ((index * multiplier + offset) % COUNTS.USER) < target;
}

// Chooses among the least-busy load staff, matching the app's support assignment policy.
function chooseStaff(staff, activeCounts, seed) {
  const least = Math.min(...staff.map(member => activeCounts.get(member.uid) || 0));
  const available = staff.filter(member => (activeCounts.get(member.uid) || 0) === least);
  return available[seed % available.length];
}

// Builds the support conversation read by user, staff, and admin message screens.
function supportConversation(id, owner, staff, origin, ticketNumber, isComplete, createdAt) {
  return {
    type: "SUPPORT",
    ownerUid: owner.uid,
    staffUid: staff.uid,
    adminUid: "",
    supportOrigin: origin,
    supportTag: origin,
    requestSource: origin === "SUPPORT" ? "SUPPORT_BUTTON" : "SAFETY_PLAN",
    sourceConversationId: id,
    friendshipId: id,
    participantIds: [owner.uid, staff.uid],
    participantUsernames: {[owner.uid]: owner.username, [staff.uid]: staff.username},
    createdAt,
    updatedAt: createdAt,
    lastMessage: "",
    lastMessageId: "",
    lastSenderUid: "",
    ticketNumber,
    ticketStatus: isComplete ? "COMPLETED" : "OPEN",
    ...(isComplete ? {closedAt: createdAt, completedAt: createdAt} : {}),
  };
}

// Reserves ticket numbers without re-numbering fixtures that already exist from an earlier run.
async function ticketNumbers(conversationIds) {
  const snapshots = await batchGet(conversationIds.map(id => firestore.doc(`conversations/${id}`)));
  const existing = new Map(snapshots.filter(snapshot => snapshot.exists &&
    Number.isSafeInteger(snapshot.get("ticketNumber")) && snapshot.get("ticketNumber") >= 0)
      .map(snapshot => [snapshot.id, snapshot.get("ticketNumber")]));
  const missing = conversationIds.filter(id => !existing.has(id));
  if (!missing.length) return existing;

  const counter = firestore.doc("systemCounters/supportTickets");
  const allocated = await firestore.runTransaction(async transaction => {
    const snapshot = await transaction.get(counter);
    const stored = snapshot.get("nextNumber");
    const first = Number.isSafeInteger(stored) && stored >= 0 ? stored : 0;
    transaction.set(counter, {nextNumber: first + missing.length}, {merge: true});
    return first;
  });
  missing.forEach((id, index) => existing.set(id, allocated + index));
  return existing;
}

// Creates a two-way accepted friendship and its blank direct chat.
function friendshipWrites(user, safeContact, createdAt) {
  const friendshipId = `${user.uid}--${safeContact.uid}`;
  const participantIds = [user.uid, safeContact.uid];
  const participantUsernames = {
    [user.uid]: user.username,
    [safeContact.uid]: safeContact.username,
  };
  return [
    documentWrite(`friendships/${friendshipId}`, {
      participantIds, participantUsernames, acceptedRequestId: friendshipId, createdAt,
    }),
    documentWrite(`friendLists/${user.uid}/friends/${friendshipId}`, {
      friendshipId, friendUid: safeContact.uid, friendUsername: safeContact.username, createdAt,
    }),
    documentWrite(`friendLists/${safeContact.uid}/friends/${friendshipId}`, {
      friendshipId, friendUid: user.uid, friendUsername: user.username, createdAt,
    }),
    documentWrite(`conversations/${friendshipId}`, {
      type: "DIRECT", friendshipId, participantIds, participantUsernames,
      createdAt, updatedAt: createdAt, lastMessage: "", lastMessageId: "", lastSenderUid: "",
    }),
  ];
}

// Creates all requested assignments, completions, plans, and accepted personal contacts.
async function seed() {
  const users = await accountRecords(accounts.filter(value => value.role === "USER"));
  const safeContacts = await accountRecords(accounts.filter(value => value.role === "SAFE_CONTACT"));
  const staff = await accountRecords(accounts.filter(value => value.role === "STAFF"));
  if (users.length !== COUNTS.USER || staff.length !== COUNTS.STAFF ||
      safeContacts.length !== COUNTS.SAFE_CONTACT) {
    throw new Error(`Expected ${COUNTS.USER} users, ${COUNTS.STAFF} staff, and ` +
      `${COUNTS.SAFE_CONTACT} safe contacts.`);
  }

  const supportIds = users.map(owner => `support-${owner.uid}-load-test`);
  const planIds = users.map(owner => `plan-consult-${owner.uid}-load-test`);
  const numbers = await ticketNumbers([...supportIds, ...planIds]);
  const writes = [];
  const activeCounts = new Map(staff.map(value => [value.uid, 0]));
  let completedSupport = 0;
  let completedPlans = 0;

  // Every safe contact receives five accepted load users. The rotation gives every user two safe contacts.
  const safeContactsByUser = new Map(users.map(user => [user.uid, []]));
  safeContacts.forEach((safeContact, safeIndex) => {
    for (let friendIndex = 0; friendIndex < 5; friendIndex++) {
      const user = users[(safeIndex * 5 + friendIndex) % users.length];
      safeContactsByUser.get(user.uid).push(safeContact);
      writes.push(...friendshipWrites(user, safeContact, new Date(Date.now() + safeIndex * 5 + friendIndex)));
    }
  });

  // The two assignment passes use the same least-busy rule as the request buttons.
  users.forEach((owner, index) => {
    const supportId = supportIds[index];
    const supportComplete = completed(index, 73, 17);
    const supportStaff = chooseStaff(staff, activeCounts, index * 3 + 1);
    if (!supportComplete) activeCounts.set(supportStaff.uid, activeCounts.get(supportStaff.uid) + 1);
    else completedSupport++;
    const supportCreatedAt = new Date(Date.now() + index);
    writes.push(documentWrite(`supportRequests/${supportId}`, {
      ownerUid: owner.uid,
      ownerName: owner.fullName,
      requested: !supportComplete,
      staffUid: supportStaff.uid,
      staffName: supportStaff.fullName,
      adminUid: "",
      adminName: "",
      supportOrigin: "SUPPORT",
      requestSource: "SUPPORT_BUTTON",
      sourceConversationId: supportId,
      conversationId: supportId,
      ticketNumber: numbers.get(supportId),
      updatedAt: supportCreatedAt.getTime(),
    }));
    writes.push(documentWrite(`conversations/${supportId}`, supportConversation(
        supportId, owner, supportStaff, "SUPPORT", numbers.get(supportId),
        supportComplete, supportCreatedAt,
    )));

    const planId = planIds[index];
    const planComplete = completed(index, 97, 31);
    const planStaff = chooseStaff(staff, activeCounts, index * 5 + 2);
    if (!planComplete) activeCounts.set(planStaff.uid, activeCounts.get(planStaff.uid) + 1);
    else completedPlans++;
    const planCreatedAt = new Date(Date.now() + users.length + index);
    const safeContact = safeContactsByUser.get(owner.uid)[0];
    writes.push(documentWrite(`safetyPlans/${owner.uid}`, {
      ownerUid: owner.uid,
      ownerName: owner.fullName,
      staffUid: planComplete ? "" : planStaff.uid,
      staffName: planComplete ? "" : planStaff.fullName,
      adminUid: "",
      adminName: "",
      consultationRequested: !planComplete,
      consultationConversationId: planComplete ? "" : planId,
      ticketNumber: numbers.get(planId),
      safeContactUids: safeContact ? [safeContact.uid] : [],
      safeContactNames: safeContact ? {[safeContact.uid]: safeContact.fullName} : {},
      supportSafeContactUid: "",
      fields: planFields(index),
      updatedAt: planCreatedAt.getTime(),
    }));
    writes.push(documentWrite(`conversations/${planId}`, supportConversation(
        planId, owner, planStaff, "PLAN_CONSULT", numbers.get(planId),
        planComplete, planCreatedAt,
    )));
  });

  staff.forEach(value => writes.push(documentWrite(`safetyStaff/${value.uid}`, {
    available: true,
    activeCount: activeCounts.get(value.uid),
  })));
  await commit(writes);

  // Checks the high-value fixture records before reporting that the stress-test data is ready.
  const verificationPaths = [
    ...supportIds.map(id => `conversations/${id}`),
    ...planIds.map(id => `conversations/${id}`),
    ...users.map(owner => `safetyPlans/${owner.uid}`),
    ...staff.map(member => `safetyStaff/${member.uid}`),
  ];
  const verification = await batchGet(verificationPaths.map(path => firestore.doc(path)));
  const missing = verification.filter(snapshot => !snapshot.exists).map(snapshot => snapshot.ref.path);
  if (missing.length) throw new Error(`Seed verification is missing ${missing.length} records.`);

  console.log(`Created ${users.length} support tickets and ${users.length} plan consultations.`);
  console.log(`Completed ${completedSupport} support tickets and ${completedPlans} plans ` +
    `(${Math.round(completionRatio * 100)}% each).`);
  console.log(`Created ${safeContacts.length * 5} accepted user/safe-contact friendships.`);
  console.log(`Open work per staff: ${staff.map(member =>
    `${member.username}=${activeCounts.get(member.uid)}`).join(", ")}`);
  console.log(`Verified ${verificationPaths.length} core Firestore records.`);
}

seed().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
