const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const id = "11111111-1111-4111-8111-111111111111";
const jobId = "22222222-2222-4222-8222-222222222222";
function fixture() {
  const records = new Map();
  const deliveries = [];
  let caller = {uid: "owner", role: "USER", name: "Test user"};
  const users = new Map([
    ["admin", {uid: "admin", customClaims: {role: "ADMIN"}}],
    ["staff", {uid: "staff", customClaims: {role: "STAFF"}}],
    ["disabled", {uid: "disabled", disabled: true, customClaims: {role: "ADMIN"}}],
  ]);
  const snapshot = reference => ({
    id: reference.id, exists: records.has(reference.path),
    get: field => records.get(reference.path)?.[field],
  });
  const collection = prefix => ({
    doc: key => ({
      id: key, path: `${prefix}/${key}`,
      get: async () => snapshot({id: key, path: `${prefix}/${key}`}),
      collection: name => collection(`${prefix}/${key}/${name}`),
    }),
    get: async () => ({docs: [...records.keys()].filter(key => key.startsWith(`${prefix}/`))
      .map(key => snapshot({id: key.split("/").at(-1), path: key}))}),
  });
  const db = {collection, runTransaction: async action => {
    const writes = [];
    const result = await action({
      get: async reference => snapshot(reference),
      create: (ref, value) => writes.push(() => records.set(ref.path, value)),
      set: (ref, value) => writes.push(() => records.set(ref.path, value)),
      update: (ref, value) => writes.push(() => records.set(ref.path, {...records.get(ref.path), ...value})),
    });
    writes.forEach(write => write());
    return result;
  }};
  class HttpsError extends Error { constructor(code, message) { super(message); this.code = code; } }
  const imports = {
    "firebase-functions/v2/https": {onCall: (_, handler) => handler, HttpsError},
    "firebase-functions/v2/firestore": {onDocumentCreated: (_, handler) => handler, onDocumentWritten: (_, handler) => handler},
    "./safetyPlanAccess": {safetyCaller: async () => caller},
    "./firebaseAdminClient": {
      db, FieldValue: {serverTimestamp: () => 100},
      auth: {getUser: async uid => users.get(uid), listUsers: async () => ({users: [...users.values()]})},
      messaging: {sendEachForMulticast: async message => {
        deliveries.push(message);
        return {responses: message.tokens.map(() => ({success: true}))};
      }},
    },
  };
  const api = {};
  new Function("require", "exports", fs.readFileSync(path.join(__dirname, "src/emergencyReports.js"), "utf8"))(
    name => { assert.ok(imports[name], name); return imports[name]; }, api,
  );
  return {api, records, deliveries, users, setCaller: value => { caller = value; }};
}

test("emergency start is owner-idempotent and rejects other users and organization callers", async () => {
  const f = fixture();
  await f.api.emergencyStart({data: {id}});
  await f.api.emergencyStart({data: {id}});
  assert.equal(f.records.size, 1);
  assert.equal(f.records.get(`emergencyReports/${id}`).ownerUid, "owner");
  for (const caller of [{uid: "other", role: "USER"}, {uid: "staff", role: "STAFF"}]) {
    f.setCaller(caller);
    await assert.rejects(f.api.emergencyStart({data: {id}}), {code: "permission-denied"});
  }
  await assert.rejects(f.api.emergencyStart({data: {id: "../bad"}}));
});

for (const uploadFirst of [false, true]) test(`report links securely when upload finishes ${uploadFirst ? "before" : "after"} association`, async () => {
  const f = fixture();
  await f.api.emergencyStart({data: {id}});
  f.records.set("files/pdf", {owner_id: "owner"});
  if (uploadFirst) f.records.set(`mediaUploads/${jobId}`, {ownerUid: "owner", mediaId: "pdf", status: "complete"});
  await f.api.emergencyAttachUpload({data: {id, jobId}});
  if (!uploadFirst) {
    assert.equal(f.records.get(`emergencyReports/${id}`).mediaId, "");
    f.records.set(`mediaUploads/${jobId}`, {ownerUid: "owner", mediaId: "pdf", status: "complete"});
  }
  await f.api.emergencyUploadCompleted({params: {jobId}});
  await f.api.emergencyUploadLinked({params: {jobId}});
  assert.equal(f.records.get(`emergencyReports/${id}`).mediaId, "pdf");
  assert.equal(f.records.get("files/pdf").emergency_id, id);
});

test("an upload belonging to another account cannot be linked or reassigned", async () => {
  const f = fixture();
  await f.api.emergencyStart({data: {id}});
  f.setCaller({uid: "other", role: "USER"});
  await assert.rejects(f.api.emergencyAttachUpload({data: {id, jobId}}), {code: "permission-denied"});
  f.records.set(`emergencyUploads/${jobId}`, {emergencyId: id, ownerUid: "owner"});
  f.records.set(`mediaUploads/${jobId}`, {ownerUid: "other", mediaId: "pdf", status: "complete"});
  await assert.rejects(f.api.emergencyUploadCompleted({params: {jobId}}), /owner mismatch/);
});

test("only current enabled admins receive independent access to the exact emergency PDF", async () => {
  const f = fixture();
  f.records.set(`emergencyReports/${id}`, {ownerUid: "owner", mediaId: "pdf"});
  const file = {owner_id: "owner", emergency_id: id};
  assert.equal(await f.api.canReadEmergencyMedia(file, "admin", "pdf"), true);
  for (const uid of ["staff", "disabled"]) assert.equal(await f.api.canReadEmergencyMedia(file, uid, "pdf"), false);
  assert.equal(await f.api.canReadEmergencyMedia(file, "admin", "other-file"), false);
  assert.equal(await f.api.canReadEmergencyMedia({...file, owner_id: "other"}, "admin", "pdf"), false);
  f.users.get("admin").customClaims.role = "USER";
  assert.equal(await f.api.canReadEmergencyMedia(file, "admin", "pdf"), false);
});

test("all enabled admins receive private FCM alerts; staff and disabled admins do not", async () => {
  const f = fixture();
  f.users.set("admin2", {uid: "admin2", customClaims: {role: "ADMIN"}});
  for (const uid of f.users.keys()) f.records.set(`notificationTokens/${uid}/devices/phone`, {token: `token-${uid}`});
  await f.api.notifyEmergency({params: {id}});
  assert.deepEqual(f.deliveries.map(message => message.data.recipientId).sort(), ["admin", "admin2"]);
  for (const message of f.deliveries) {
    assert.deepEqual(Object.keys(message.data).sort(), ["emergencyId", "recipientId", "type"]);
    assert.equal(message.android.priority, "high");
  }
});

test("only admins can resolve an emergency; retries retain the first resolver and report", async () => {
  const f = fixture();
  await f.api.emergencyStart({data: {id}});
  for (const role of ["USER", "SAFE_CONTACT", "STAFF"]) {
    f.setCaller({uid: "other", role});
    await assert.rejects(f.api.emergencyResolve({data: {id}}), {code: "permission-denied"});
  }
  f.setCaller({uid: "admin", role: "ADMIN"});
  await assert.rejects(f.api.emergencyResolve({data: {id: "invalid"}}), {code: "invalid-argument"});
  await assert.rejects(f.api.emergencyResolve({data: {id: jobId}}), {code: "not-found"});
  await f.api.emergencyResolve({data: {id}});
  f.setCaller({uid: "admin2", role: "ADMIN"});
  await f.api.emergencyResolve({data: {id}});
  assert.equal(f.records.get(`emergencyReports/${id}`).resolvedBy, "admin");
  assert.equal(f.records.get(`emergencyReports/${id}`).resolvedAt, 100);
  // A PDF completing later must not reactivate a resolved request.
  f.records.set(`emergencyUploads/${jobId}`, {ownerUid: "owner", emergencyId: id});
  f.records.set(`mediaUploads/${jobId}`, {ownerUid: "owner", status: "complete", mediaId: "pdf"});
  f.records.set("files/pdf", {owner_id: "owner"});
  await f.api.emergencyUploadCompleted({params: {jobId}});
  const report = f.records.get(`emergencyReports/${id}`);
  assert.equal(report.resolvedBy, "admin");
  assert.equal(report.resolvedAt, 100);
  assert.equal(report.mediaId, "pdf");
  f.setCaller({uid: "staff", role: "STAFF"});
  await assert.rejects(f.api.emergencyReopen({data: {id}}), {code: "permission-denied"});
  f.setCaller({uid: "admin2", role: "ADMIN"});
  await f.api.emergencyReopen({data: {id}});
  await f.api.emergencyReopen({data: {id}});
  assert.equal(f.records.get(`emergencyReports/${id}`).resolvedAt, null);
  assert.equal(f.records.get(`emergencyReports/${id}`).reopenedBy, "admin2");
  assert.equal(f.records.get(`emergencyReports/${id}`).mediaId, "pdf");
});
