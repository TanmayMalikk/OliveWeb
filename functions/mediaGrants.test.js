const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
function prepared(send = async () => ({})) {
  const calls = [];
  const f = fixture({
    "firebase-functions/params": {defineSecret: name => ({value: () => name === "MEDIA_RECOVERY_MASTER_KEY" ? Buffer.alloc(32, 1).toString("base64") : "fixture"})},
    "@aws-sdk/s3-request-presigner": {getSignedUrl: async (_, command, options) => {calls.push({command, options}); return "https://fixture.invalid/signed";}},
    "@aws-sdk/client-s3": {...require("@aws-sdk/client-s3"), S3Client: class {async send(command) {calls.push({command}); return send(command);}}},
    "./emergencyReports": {canReadEmergencyMedia: async () => false},
  });
  f.records.set("files/file", {owner_id: "owner", allowed_users: ["owner"], wrapped_keys: {owner: "owner-key"}, account_wrapped_keys: wrappers("owner"), device_wrapped_keys: {"owner--phone": "owner-device-key"}, object_key: "encrypted/file", encrypted_metadata: "ciphertext-metadata"});
  f.records.set("safetyPlans/owner", {safeContactUids: ["friend"], fields: {primary_documents: {value: JSON.stringify({oliveSafetyChecklist: 1, items: [{id: "row", attachmentIds: ["file"]}]})}}});
  return {...f, calls, api: f.load("mediaTickets")};
}
const request = (uid, data) => ({auth: {uid}, data: {mediaId: "file", ...data}});
const grant = "safetyPlanMediaGrants/owner/files/file";
const wrapper = () => ({keyId: "123e4567-e89b-42d3-a456-426614174000", epoch: 1,
  algorithm: "RSA-OAEP-256", wrappedKey: Buffer.alloc(384, 1).toString("base64")});
const wrappers = (...uids) => Object.fromEntries(uids.map(uid => [uid, wrapper()]));
const access = {wrappedKeys: {owner: "owner-key", friend: "friend-key"}, accountWrappedKeys: wrappers("owner", "friend")};
test("an independent share survives plan attachment removal and reconciliation", async () => {
  const f = prepared();
  await f.api.mediaUpdateAccess(request("owner", {...access, forPlan: true}));
  assert.deepEqual(f.records.get(grant).readers, ["friend"]);
  await f.api.mediaUpdateAccess(request("owner", {...access, grantIndependentRecipients: ["friend"]}));
  f.records.get("safetyPlans/owner").fields = {};
  assert.equal((await f.api.mediaDownloadTicket(request("friend", {}))).url, "https://fixture.invalid/signed");
  await f.api.reconcilePlanMediaGrants("owner");
  assert.ok(f.records.get("files/file").allowed_users.includes("friend"));
  assert.equal(f.records.get("files/file").wrapped_keys.friend, "friend-key");
  assert.deepEqual(f.records.get(grant).independentReaders, ["friend"]);
});
test("plan-only revocation denies downloads immediately and removes keys during reconciliation", async () => {
  const f = prepared();
  await f.api.mediaUpdateAccess(request("owner", {...access, forPlan: true, deviceWrappedKeys: {"friend--phone": "friend-device-key"}}));
  f.records.get("safetyPlans/owner").fields = {};
  await assert.rejects(f.api.mediaDownloadTicket(request("friend", {})), {code: "permission-denied"});
  await f.api.reconcilePlanMediaGrants("owner");
  const file = f.records.get("files/file");
  assert.deepEqual(file.allowed_users, ["owner"]); assert.equal(file.device_wrapped_keys["friend--phone"], undefined);
  assert.equal(file.wrapped_keys.owner, "owner-key"); assert.equal(f.records.has(grant), false);
});
test("removing an independent share preserves a still-active plan grant", async () => {
  const f = prepared();
  await f.api.mediaUpdateAccess(request("owner", {...access, forPlan: true}));
  await f.api.mediaUpdateAccess(request("owner", {...access, grantIndependentRecipients: ["friend"]}));
  await f.api.mediaUpdateAccess(request("owner", {wrappedKeys: {owner: "owner-key"}, accountWrappedKeys: wrappers("owner"), revokeIndependentRecipients: ["friend"]}));
  assert.equal(f.records.get("files/file").wrapped_keys.friend, "friend-key");
  assert.deepEqual(f.records.get(grant).independentReaders, []);
  await f.api.mediaDownloadTicket(request("friend", {}));
});
test("plan grants cannot authorize unshared readers or change independent grants", async () => {
  const f = prepared();
  await assert.rejects(f.api.mediaUpdateAccess(request("owner", {...access, forPlan: true, grantIndependentRecipients: ["friend"]})), {code: "invalid-argument"});
  await assert.rejects(f.api.mediaUpdateAccess(request("owner", {wrappedKeys: {owner: "key", stranger: "key"}, accountWrappedKeys: wrappers("owner", "stranger"), forPlan: true})), {code: "permission-denied"});
  await assert.rejects(f.api.mediaUpdateAccess(request("other", {wrappedKeys: {other: "key"}, accountWrappedKeys: wrappers("other")})), {code: "permission-denied"});
  await assert.rejects(f.api.mediaDownloadTicket({data: {mediaId: "file"}}), {code: "unauthenticated"});
  await assert.rejects(f.api.mediaDownloadTicket(request("stranger", {})), {code: "permission-denied"});
});
test("new-device wrappers are account-scoped and retain existing authorized device keys", async () => {
  const f = prepared();
  await f.api.mediaUpdateDeviceKeys(request("owner", {deviceWrappedKeys: {"owner--browser": "new-key"}}));
  assert.equal(f.records.get("files/file").device_wrapped_keys["owner--phone"], "owner-device-key");
  await assert.rejects(f.api.mediaUpdateDeviceKeys(request("owner", {deviceWrappedKeys: {"friend--phone": "stolen-key"}})), {code: "invalid-argument"});
  await assert.rejects(f.api.mediaUpdateDeviceKeys(request("stranger", {deviceWrappedKeys: {"stranger--phone": "key"}})), {code: "permission-denied"});
});
test("evidence changes are owner-only boolean updates and do not replace ciphertext or sharing", async () => {
  const f = prepared();
  await f.api.mediaUpdateEvidence(request("owner", {evidence: true}));
  assert.equal(f.records.get("files/file").evidence, true);
  assert.equal(f.records.get("files/file").object_key, "encrypted/file");
  await assert.rejects(f.api.mediaUpdateEvidence(request("friend", {evidence: false})), {code: "permission-denied"});
  await assert.rejects(f.api.mediaUpdateEvidence(request("owner", {evidence: "true"})), {code: "invalid-argument"});
});
test("owner deletion removes the object and recovery record and is safe to retry", async () => {
  const f = prepared();
  await assert.rejects(f.api.mediaDelete(request("friend", {})), {code: "permission-denied"});
  await f.api.mediaDelete(request("owner", {}));
  assert.equal(f.calls[0].command.input.Key, "encrypted/file");
  assert.equal(f.records.has("files/file"), false);
  await f.api.mediaDelete(request("owner", {}));
  assert.equal(f.calls.length, 1);
});
const jobId = "11111111-1111-4111-8111-111111111111";
const upload = (size = 100) => ({jobId, ciphertextSize: size, ciphertextSha256: "a".repeat(64), encryptedMetadata: "encrypted-metadata", allowedUsers: ["owner"], wrappedKeys: {owner: "owner-key"}, accountWrappedKeys: wrappers("owner"), encryptionVersion: 1});
test("single uploads resume idempotently, sign only the assigned object and publish after size verification", async () => {
  const f = prepared(async command => command.constructor.name === "HeadObjectCommand" ? {ContentLength: 100} : {});
  const session = await f.api.mediaCreateUpload(request("owner", upload()));
  const retried = await f.api.mediaCreateUpload(request("owner", upload()));
  assert.equal(session.mediaId, retried.mediaId);
  await f.api.mediaUploadPartTicket(request("owner", {jobId, partNumber: 1, objectKey: "attacker/override"}));
  assert.equal(f.calls[0].command.input.Key, `users/owner/media/${session.mediaId}`);
  assert.equal(f.calls[0].options.expiresIn, 600);
  const result = await f.api.mediaCompleteUpload(request("owner", {jobId}));
  assert.equal(result.mediaId, session.mediaId); assert.equal(result.ciphertextSize, 100);
  assert.deepEqual(result.allowedUsers, ["owner"]);
  assert.equal(f.records.get(`mediaUploads/${jobId}`).status, "complete");
  assert.equal((await f.api.mediaCompleteUpload(request("owner", {jobId}))).mediaId, session.mediaId);
});
test("an upload cannot be taken over, resized, completed short or signed with an invalid part", async () => {
  const f = prepared(async () => ({ContentLength: 99}));
  await f.api.mediaCreateUpload(request("owner", upload()));
  await assert.rejects(f.api.mediaCreateUpload(request("owner", upload(101))), {code: "already-exists"});
  await assert.rejects(f.api.mediaUploadPartTicket(request("other", {jobId, partNumber: 1})), {code: "permission-denied"});
  await assert.rejects(f.api.mediaUploadPartTicket(request("owner", {jobId, partNumber: 2})), {code: "invalid-argument"});
  await assert.rejects(f.api.mediaCompleteUpload(request("owner", {jobId})), {code: "failed-precondition"});
  assert.equal([...f.records.keys()].filter(key => key.startsWith("files/")).length, 1);
  await f.api.mediaAbortUpload(request("owner", {jobId}));
  assert.equal(f.records.has(`mediaUploads/${jobId}`), false);
});
test("multipart tickets validate part count and order and abort the assigned storage session", async () => {
  const size = 17 * 1024 * 1024;
  const f = prepared(async command => command.constructor.name === "CreateMultipartUploadCommand" ? {UploadId: "upload-id"} : {ContentLength: size});
  await f.api.mediaCreateUpload(request("owner", upload(size)));
  await f.api.mediaUploadPartTicket(request("owner", {jobId, partNumber: 3}));
  assert.equal(f.calls[1].command.input.UploadId, "upload-id");
  await assert.rejects(f.api.mediaCompleteUpload(request("owner", {jobId, parts: []})), {code: "failed-precondition"});
  await assert.rejects(f.api.mediaCompleteUpload(request("owner", {jobId, parts: [1, 1, 3].map(partNumber => ({partNumber, etag: "etag"}))})), {code: "invalid-argument"});
  const result = await f.api.mediaCompleteUpload(request("owner", {jobId, parts: [3, 1, 2].map(partNumber => ({partNumber, etag: `etag-${partNumber}`}))}));
  assert.equal(result.ciphertextSize, size);
  const g = prepared(async () => ({UploadId: "abort-id"}));
  await g.api.mediaCreateUpload(request("owner", upload(size)));
  await g.api.mediaAbortUpload(request("owner", {jobId}));
  assert.equal(g.calls.at(-1).command.input.UploadId, "abort-id");
});
for (const invalid of [{ciphertextSize: 0}, {ciphertextSize: 251 * 1024 * 1024}, {ciphertextSha256: "invalid"}, {allowedUsers: []}, {wrappedKeys: {}}, {encryptionVersion: 0}, {jobId: "invalid"}]) {
  test(`upload rejects malformed capability input ${JSON.stringify(invalid)}`, async () => {
    const f = prepared();
    await assert.rejects(f.api.mediaCreateUpload(request("owner", {...upload(), ...invalid})), {code: "invalid-argument"});
    assert.equal(f.calls.length, 0);
  });
}

const share = {conversationId: "chat", messageId: "message", ciphertext: "encrypted-chat-payload", clientSentAt: 100, recipientUids: ["friend"], ...access};
function direct(f) {f.records.set("conversations/chat", {participantIds: ["owner", "friend"], participantUsernames: {owner: "owner_name", friend: "friend_name"}});}
test("chat attachment grant and encrypted message are atomic, idempotent and independent from plan access", async () => {
  const f = prepared(); direct(f);
  await f.api.mediaShareInChat(request("owner", share));
  await f.api.mediaShareInChat(request("owner", share));
  assert.equal(f.records.get("conversations/chat/messages/message").ciphertext, share.ciphertext);
  assert.equal(f.records.get("conversations/chat").lastMessage, "Secure message");
  assert.deepEqual(f.records.get(grant).independentReaders, ["friend"]);
  await assert.rejects(f.api.mediaShareInChat(request("owner", {...share, ciphertext: "different-message"})), {code: "already-exists"});
  assert.equal(f.records.get("conversations/chat/messages/message").ciphertext, share.ciphertext);
});
test("failed chat sharing cannot leave a message or partial recipient grants", async () => {
  const f = prepared(); direct(f);
  await assert.rejects(f.api.mediaShareInChat(request("owner", {...share, wrappedKeys: {owner: "owner-key"}})), {code: "invalid-argument"});
  assert.equal(f.records.has("conversations/chat/messages/message"), false);
  assert.equal(f.records.has(grant), false);
  assert.deepEqual(f.records.get("files/file").allowed_users, ["owner"]);
  f.records.get("conversations/chat").participantIds.push("new-friend");
  await assert.rejects(f.api.mediaShareInChat(request("owner", share)), {code: "failed-precondition"});
  assert.equal(f.records.has("conversations/chat/messages/message"), false);
});
test("closed chats and callers who do not own the media cannot create attachment shares", async () => {
  const f = prepared(); direct(f);
  f.records.get("conversations/chat").closedAt = 1;
  await assert.rejects(f.api.mediaShareInChat(request("owner", share)), {code: "failed-precondition"});
  await assert.rejects(f.api.mediaShareInChat(request("friend", {...share, recipientUids: ["owner"]})), {code: "permission-denied"});
  await assert.rejects(f.api.mediaShareInChat(request("owner", {...share, recipientUids: [], clientSentAt: -1})), {code: "invalid-argument"});
});
test("legacy chat media validates the owner storage path before granting access", async () => {
  const f = prepared(); direct(f);
  f.records.set("files/file", {owner_id: "owner", storage_path: "someone-else/path", allowed_users: ["owner"]});
  await assert.rejects(f.api.mediaShareInChat(request("owner", share)), {code: "failed-precondition"});
  f.records.get("files/file").storage_path = "users/owner/media/file";
  await f.api.mediaShareInChat(request("owner", share));
  assert.deepEqual(f.records.get("files/file").allowed_users, ["owner", "friend"]);
});
test("chat and direct sharing allow recipients without an enrolled account keyset wrapper", async () => {
  const f = prepared(); direct(f);
  const ownerOnlyWrappers = {wrappedKeys: {owner: "owner-key", friend: "friend-key"}, accountWrappedKeys: wrappers("owner")};
  await f.api.mediaShareInChat(request("owner", {...share, ...ownerOnlyWrappers}));
  assert.deepEqual(f.records.get("files/file").allowed_users, ["owner", "friend"]);
  assert.deepEqual(Object.keys(f.records.get("files/file").account_wrapped_keys), ["owner"]);
  await f.api.mediaUpdateAccess(request("owner", {...ownerOnlyWrappers, grantIndependentRecipients: ["friend"]}));
  assert.deepEqual(f.records.get("files/file").allowed_users, ["owner", "friend"]);
});
