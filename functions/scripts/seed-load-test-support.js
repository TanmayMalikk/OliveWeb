// Seeds deterministic support work for the fictional load-test accounts.
const path = require("node:path");
const fs = require("node:fs");
const os = require("node:os");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {signInEmail} = require("../src/accountPolicy");
const {accounts} = require("./load-test-account-fixtures");

const projectId = String(process.env.OLIVE_LOAD_TEST_PROJECT_ID || "").trim();
const confirmation = String(process.env.OLIVE_LOAD_TEST_CONFIRM_PROJECT || "").trim();
if (!projectId || confirmation !== projectId) {
  throw new Error("Set OLIVE_LOAD_TEST_PROJECT_ID and OLIVE_LOAD_TEST_CONFIRM_PROJECT to the same project ID.");
}

const cliConfigPaths = [
  path.join(os.homedir(), ".config", "configstore", "firebase-tools.json"),
  process.env.APPDATA && path.join(process.env.APPDATA, "configstore", "firebase-tools.json"),
].filter(Boolean);

function cliToken() {
  const configPath = cliConfigPaths.find(candidate => fs.existsSync(candidate));
  const tokens = configPath && JSON.parse(fs.readFileSync(configPath, "utf8")).tokens;
  if (!tokens?.access_token || Number(tokens.expires_at || 0) <= Date.now() + 60_000) {
    throw new Error("Firebase CLI login is missing or expired. Run: npx firebase-tools projects:list");
  }
  return tokens;
}

const credential = {
  getAccessToken: async () => {
    const tokens = cliToken();
    return {
      access_token: tokens.access_token,
      expires_in: Math.max(60, Math.floor((Number(tokens.expires_at) - Date.now()) / 1000)),
    };
  },
};
const app = initializeApp({credential, projectId}, "load-test-support");
const auth = getAuth(app);

function firestoreValue(value) {
  if (value instanceof Date) return {timestampValue: value.toISOString()};
  if (Array.isArray(value)) return {arrayValue: {values: value.map(firestoreValue)}};
  if (value !== null && typeof value === "object") {
    return {mapValue: {fields: Object.fromEntries(
        Object.entries(value).map(([key, item]) => [key, firestoreValue(item)]),
    )}};
  }
  if (typeof value === "string") return {stringValue: value};
  if (typeof value === "boolean") return {booleanValue: value};
  if (Number.isInteger(value)) return {integerValue: String(value)};
  if (typeof value === "number") return {doubleValue: value};
  if (value === null) return {nullValue: null};
  throw new Error(`Unsupported Firestore value: ${typeof value}`);
}

function documentWrite(collection, documentId, value, mergeFields = []) {
  const update = {
    name: `projects/${projectId}/databases/(default)/documents/${collection}/${documentId}`,
    fields: Object.fromEntries(Object.entries(value).map(([key, item]) => [key, firestoreValue(item)])),
  };
  return mergeFields.length ? {update, updateMask: {fieldPaths: mergeFields}} : {update};
}

async function commit(writes) {
  const response = await fetch(
      `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:batchWrite`,
      {
        method: "POST",
        headers: {Authorization: `Bearer ${cliToken().access_token}`, "Content-Type": "application/json"},
        body: JSON.stringify({writes}),
      },
  );
  if (!response.ok) throw new Error(`Firestore seed failed: ${response.status} ${await response.text()}`);
  const result = await response.json();
  if (result.status?.some(status => status.code && status.code !== 0)) {
    throw new Error(`Firestore seed returned write errors: ${JSON.stringify(result.status)}`);
  }
}

async function batchGet(documentNames) {
  const response = await fetch(
      `https://firestore.googleapis.com/v1/projects/${projectId}/databases/(default)/documents:batchGet`,
      {
        method: "POST",
        headers: {Authorization: `Bearer ${cliToken().access_token}`, "Content-Type": "application/json"},
        body: JSON.stringify({documents: documentNames}),
      },
  );
  if (!response.ok) throw new Error(`Firestore verification failed: ${response.status} ${await response.text()}`);
  return response.json();
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

  const documentRoot = `projects/${projectId}/databases/(default)/documents`;
  const expectedNames = [
    ...users.flatMap(owner => [
      `${documentRoot}/safetyPlans/${owner.uid}`,
      `${documentRoot}/supportRequests/${owner.uid}`,
      `${documentRoot}/conversations/plan-consult-${owner.uid}-load-test`,
      `${documentRoot}/conversations/support-${owner.uid}-load-test`,
    ]),
    ...staff.map(value => `${documentRoot}/safetyStaff/${value.uid}`),
  ];
  const results = [];
  for (let index = 0; index < expectedNames.length; index += 100) {
    results.push(...await batchGet(expectedNames.slice(index, index + 100)));
  }
  const found = new Map(results.filter(value => value.found).map(value => [value.found.name, value.found]));
  const missing = expectedNames.filter(name => !found.has(name));
  if (missing.length) throw new Error(`Seed verification is missing ${missing.length} documents.`);
  const badStaff = staff.filter(value =>
    found.get(`${documentRoot}/safetyStaff/${value.uid}`)?.fields?.activeCount?.integerValue !== "10");
  const badPlans = users.filter(owner => {
    const fields = found.get(`${documentRoot}/safetyPlans/${owner.uid}`)?.fields;
    return fields?.consultationRequested?.booleanValue !== true ||
      fields?.safeContactUids?.arrayValue?.values?.length !== 1;
  });
  if (badStaff.length || badPlans.length) {
    throw new Error(`Seed verification failed for ${badStaff.length} staff records and ${badPlans.length} plans.`);
  }
  console.log(`Seeded ${users.length} plan consultations and ${users.length} support requests.`);
  console.log(`Shared ${users.length} safety plans with staff and rotating safe contacts.`);
  console.log(`Each of ${staff.length} staff accounts has 5 plan consultations and 5 support requests.`);
  console.log(`Verified ${expectedNames.length} persisted Firestore documents.`);
}

seed().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
