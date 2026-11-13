// Tests that cloud endpoints for accounts, support, media, and nutrition keep their public names.
const test = require("node:test");
const assert = require("node:assert/strict");
const { createCipheriv, randomBytes } = require("node:crypto");
const { PutObjectCommand, UploadPartCommand } = require("@aws-sdk/client-s3");
const { getSignedUrl } = require("@aws-sdk/s3-request-presigner");
process.env.FIREBASE_CONFIG = JSON.stringify({
  projectId: "olive-test",
  storageBucket: "olive-test.appspot.com",
  databaseURL: "https://olive-test.firebaseio.com",
});
const { _test } = require("./index");
const mediaTest = require("./src/mediaTickets")._test;
const supportGroups = require("./src/supportGroups");

test("document recovery encrypts the key and binds it to one file and owner", () => {
  const key = randomBytes(32).toString("base64");
  const masterKey = randomBytes(32).toString("base64");
  const sealed = mediaTest.sealRecoveryKey(key, "file-a", "owner-a", masterKey);
  assert.notEqual(sealed, key);
  assert.notEqual(sealed, mediaTest.sealRecoveryKey(key, "file-a", "owner-a", masterKey));
  assert.equal(mediaTest.openRecoveryKey(sealed, "file-a", "owner-a", masterKey), key);
  assert.throws(() => mediaTest.openRecoveryKey(sealed, "file-b", "owner-a", masterKey));
  assert.throws(() => mediaTest.openRecoveryKey(sealed, "file-a", "owner-b", masterKey));
});

test("document recovery rejects a key that cannot open the existing Android/web metadata", () => {
  const key = randomBytes(32);
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update('{"name":"test.pdf"}'), cipher.final()]);
  const metadata = Buffer.concat([iv, encrypted, cipher.getAuthTag()]).toString("base64");
  assert.doesNotThrow(() => mediaTest.verifyRecoveryKey(key.toString("base64"), metadata));
  assert.throws(() => mediaTest.verifyRecoveryKey(randomBytes(32).toString("base64"), metadata),
    { code: "invalid-argument" });
});

test("R2 upload tickets do not sign the checksum of an empty body", async () => {
  const client = mediaTest.client({
    accountId: "example",
    accessKeyId: "test",
    secretAccessKey: "test",
  });
  const object = { Bucket: "test-bucket", Key: "test-file" };
  const commands = [
    new PutObjectCommand({ ...object, ContentType: "application/octet-stream" }),
    new UploadPartCommand({ ...object, UploadId: "test-upload", PartNumber: 1 }),
  ];
  for (const command of commands) {
    const { searchParams } = new URL(await getSignedUrl(client, command));
    assert.ok(searchParams.has("X-Amz-Signature"));
    assert.equal(searchParams.has("x-amz-checksum-crc32"), false);
    assert.equal(searchParams.has("x-amz-sdk-checksum-algorithm"), false);
  }
});

// Inputs: the values in this case. Expected: deployment keeps the existing callable endpoint names.
test("deployment keeps the existing callable endpoint names", () => {
  const endpoints = require("./index");
  const names = ["adminListAccounts", "adminCreateAccount", "adminDeleteAccount", "createPublicAccount",
    "completeOrganizationProfile", "resolveOrganizationLogin", "restoreOrganizationLoginAlias",
    "safetyPlanSync", "safetyPlanConsultations", "safetyPlanAvailability", "safetyPlanConsult",
    "safetyPlanShareContacts", "safetyPlanMediaRecipients", "safetyPlansSharedWithMe", "mediaCreateUpload",
    "mediaUploadPartTicket", "mediaCompleteUpload", "mediaAbortUpload",
    "mediaDownloadTicket", "mediaAccountAccessKey", "mediaStoreAccountAccessKey",
    "mediaUpdateAccess", "mediaShareInChat", "mediaUpdateDeviceKeys", "mediaUpdateEvidence", "mediaDelete", "staffSupportRequest",
    "notifyConversationUpdate", "safetySupportContact", "safetySupportAdmin", "conversationAddParticipant",
    "conversationRemoveParticipant", "conversationRequestStaff", "conversationPrepareKeys", "supportTicketsPrepare", "staffSupportClose", "nutritionFoodSearch",
    "syncSafetyPlanInvalidation", "syncMediaInvalidation", "syncConversationInvalidation",
    "syncConversationDeviceKeyInvalidation", "syncAccountInvalidation"];
  assert.deepEqual(Object.keys(endpoints).filter((name) => name !== "_test").sort(), names.sort());
  names.forEach((name) => assert.equal(typeof endpoints[name], "function"));
});

test("ticket assignments notify staff and newly added administrators through the shared FCM payload", () => {
  const {ticketAssignmentForUpdate, fcmTargetsForConversationUpdate} = require("./src/supportTickets")._test;
  const id = "support-user-ticket";
  const ticket = {type: "SUPPORT", ownerUid: "user", staffUid: "staff", participantIds: ["user", "staff"], lastMessageId: ""};
  for (const origin of ["SUPPORT", "PLAN_CONSULT"]) {
    assert.deepEqual(ticketAssignmentForUpdate(undefined, {...ticket, supportOrigin: origin}, id), {
      recipients: ["staff"], data: {type: "safe_update", conversationId: id, eventId: "ticket-assigned"},
    });
  }
  const waiting = {...ticket, staffUid: "", participantIds: ["user"]};
  assert.equal(ticketAssignmentForUpdate(undefined, waiting, id), null);
  assert.deepEqual(ticketAssignmentForUpdate(waiting, ticket, id).recipients, ["staff"]);
  const escalated = {...ticket, adminUid: "admin", participantIds: ["user", "staff", "admin"],
    lastMessageId: "first-message", lastSenderUid: "user"};
  assert.deepEqual(ticketAssignmentForUpdate(ticket, escalated, id).recipients, ["admin"]);
  assert.deepEqual(fcmTargetsForConversationUpdate(ticket, escalated, id).recipients, ["staff", "admin"]);
  assert.equal(ticketAssignmentForUpdate(ticket, {...ticket, participantIds: ["user", "staff", "contact"]}, id), null);
  assert.equal(ticketAssignmentForUpdate(ticket, ticket, id), null);
  assert.equal(ticketAssignmentForUpdate(undefined, {...ticket, closedAt: new Date()}, id), null);
});

// Inputs: a three-person group, its owner, and one other member. Expected: only the owner can
// remove that member, while two-person groups and unrelated accounts are rejected.
test("group member removal keeps an owner and at least two people", () => {
  const group = {type:"GROUP", ownerUid:"owner", participantIds:["owner", "friend", "safe"]};
  assert.deepEqual(supportGroups.validateGroupRemoval(group, "owner", "safe"), ["owner", "friend"]);
  assert.throws(() => supportGroups.validateGroupRemoval(group, "friend", "safe"));
  assert.throws(() => supportGroups.validateGroupRemoval(group, "owner", "missing"));
  assert.throws(() => supportGroups.validateGroupRemoval(
    {...group, participantIds:["owner", "friend"]}, "owner", "friend",
  ));
});

// Inputs: current and older support tickets with staff/admin participants. Expected: an
// organization account in the chat may close it even when an old assignment field is stale.
test("support ticket closing follows trusted role and chat membership", () => {
  const conversation = {
    type: "SUPPORT",
    participantIds: ["user", "staff", "admin"],
    staffUid: "old-staff-field",
    adminUid: "",
  };
  assert.equal(
    supportGroups.canCloseSupportConversation(conversation, {uid: "staff", role: "STAFF"}),
    true,
  );
  assert.equal(
    supportGroups.canCloseSupportConversation(conversation, {uid: "admin", role: "ADMIN"}),
    true,
  );
  assert.equal(
    supportGroups.canCloseSupportConversation(conversation, {uid: "user", role: "USER"}),
    false,
  );
  assert.equal(
    supportGroups.canCloseSupportConversation(conversation, {uid: "other-admin", role: "ADMIN"}),
    false,
  );
});

// Inputs: one device key for the caller and one forged key for another UID. Expected: only caller-owned device IDs pass.
test("encrypted media device keys stay scoped to the signed-in account", () => {
  assert.deepEqual(mediaTest.validateDeviceWrappedKeys({"owner--phone":"wrapped"}, "owner"),
    {"owner--phone":"wrapped"});
  assert.throws(() => mediaTest.validateDeviceWrappedKeys({"other--phone":"wrapped"}, "owner"));
});

// Inputs: a valid 32-byte Base64 key plus malformed and wrong-length values. Expected: only AES-256 material passes.
test("account document recovery accepts only a 256-bit key", () => {
  const valid = Buffer.alloc(32, 7).toString("base64");
  assert.equal(mediaTest.accountAccessKey(valid, true), valid);
  assert.equal(mediaTest.accountAccessKey(undefined), "");
  assert.throws(() => mediaTest.accountAccessKey("not base64", true));
  assert.throws(() => mediaTest.accountAccessKey(Buffer.alloc(16).toString("base64"), true));
});

// Inputs: the values in this case. Expected: encrypted media access keeps the owner and exact wrapped-key recipients.
test("encrypted media access keeps the owner and exact wrapped-key recipients", () => {
  assert.deepEqual(
    mediaTest.accessRequest({
      wrappedKeys:{owner:"owner-key",staff:"staff-key"},
      deviceWrappedKeys:{"owner--phone":"owner-device","staff--phone":"staff-device"},
    }, "owner"),
    {
      wrappedKeys:{owner:"owner-key",staff:"staff-key"},
      allowedUsers:["owner","staff"],
      deviceWrappedKeys:{"owner--phone":"owner-device","staff--phone":"staff-device"},
    },
  );
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{staff:"staff-key"}}, "owner"));
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{}}, "owner"));
});

// Inputs: exact authorized device wrappers plus a stale revoked wrapper. Expected: only wrappers
// belonging to the replacement allowed-user set can be persisted.
test("encrypted media access rejects device wrappers for revoked users", () => {
  assert.deepEqual(
    mediaTest.validateAccessDeviceWrappedKeys(
      {"owner--phone":"owner-device", "staff--browser":"staff-device"},
      ["owner", "staff"],
    ),
    {"owner--phone":"owner-device", "staff--browser":"staff-device"},
  );
  assert.deepEqual(mediaTest.validateAccessDeviceWrappedKeys({}, ["owner"]), {});
  assert.throws(() => mediaTest.validateAccessDeviceWrappedKeys(
    {"revoked--phone":"stale-device"}, ["owner"],
  ));
});

// Inputs: 25 accounts across Android, browser, tablet, and repeated replacement cycles.
// Expected: maximum valid access sets remain stable and oversized device maps are rejected.
test("encrypted media access validates repeated multi-device stress sets", () => {
  const users = Array.from({length:25}, (_, index) => `user-${index}`);
  for (let iteration = 0; iteration < 100; iteration += 1) {
    const devices = Object.fromEntries(users.flatMap((uid) => [
      [`${uid}--android-${iteration}`, "android-key"],
      [`${uid}--browser-${iteration}`, "browser-key"],
      [`${uid}--tablet-${iteration}`, "tablet-key"],
    ]));
    assert.equal(Object.keys(
      mediaTest.validateAccessDeviceWrappedKeys(devices, users),
    ).length, 75);
  }
  const oversized = Object.fromEntries(Array.from(
    {length:251}, (_, index) => [`owner--device-${index}`, "wrapped"],
  ));
  assert.throws(() => mediaTest.validateAccessDeviceWrappedKeys(oversized, ["owner"]));
});

// Inputs: boolean evidence values plus common wrong types. Expected: only true and false are accepted.
test("evidence labels reject text, numbers, missing values, and objects", () => {
  assert.equal(mediaTest.evidenceRequest({evidence:true}), true);
  assert.equal(mediaTest.evidenceRequest({evidence:false}), false);
  for (const evidence of ["true", 1, 0, null, undefined, {value:true}, [true]]) {
    assert.throws(() => mediaTest.evidenceRequest({evidence}));
  }
});

// Inputs: the values in this case. Expected: role aliases and real organization emails are deterministic.
test("role aliases and real organization emails are deterministic", () => {
  assert.equal(_test.signInEmail("alex_1", "USER"), "alex_1@users.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "SAFE_CONTACT"), "alex_1@safe.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "STAFF"), "alex_1@yourolivebranch.ca");
  assert.equal(_test.signInEmail("alex_1", "ADMIN", "Admin@Example.ca"), "admin@example.ca");
  assert.equal(_test.organizationEmailKey(" Admin@Example.ca "), _test.organizationEmailKey("admin@example.ca"));
});

// Inputs: the values in this case. Expected: profile completion follows each role's required fields.
test("profile completion follows each role's required fields", () => {
  assert.equal(_test.profileComplete("USER", {fullName:"A",dob:"2000-01-01",language:"English",address:{city:"Edmonton"}}), true);
  assert.equal(_test.profileComplete("USER", {fullName:"A",dob:"2000-01-01",language:"English",address:{city:""}}), false);
  assert.equal(_test.profileComplete("STAFF", {fullName:"A",dob:"2000-01-01",email:"a@example.ca"}), true);
  assert.equal(_test.profileComplete("ADMIN", {fullName:"A",dob:"2000-01-01",email:"a@admin.olive.invalid"}), false);
});

// Inputs: the values in this case. Expected: new usernames require at least eight allowed characters.
test("new usernames require at least eight allowed characters", () => {
  assert.equal(_test.normalizedUsername(" Valid_12 "), "valid_12");
  assert.throws(() => _test.normalizedUsername("short_1"));
  assert.equal(_test.normalizedExistingUsername("old_1"), "old_1");
  assert.throws(() => _test.normalizedUsername("invalid-name"));
  assert.throws(() => _test.normalizedUsername("12345678"));
  assert.throws(() => _test.normalizedUsername("________"));
  assert.equal(_test.normalizedUsername("lettersonly"), "lettersonly");
  assert.equal(_test.normalizedExistingUsername("12345678"), "12345678");
  assert.equal(_test.validPassword("Abcd123!"), true);
  assert.equal(_test.validPassword("Abcd1234"), false);
  assert.equal(_test.validPassword("1234567!"), false);
  assert.equal(_test.validPassword("abcdefgh!"), false);
  assert.equal(_test.validPassword("Valid_12123!", "valid_12"), false);
  assert.equal(_test.validPassword("Other123!", "valid_12"), true);
});

// Inputs: objects, arrays, numbers, null, and SQL-like strings. Expected: account validation rejects every wrong type or hostile value.
test("account fields reject wrong types and injection-like values", () => {
  for (const value of [null, 12345678, ["valid_name"], {name: "valid_name"}, "' OR 1=1; --"]) {
    assert.throws(() => _test.normalizedUsername(value));
  }
  for (const role of [null, 7, ["ADMIN"], {role: "ADMIN"}, "ADMIN' OR '1'='1"]) {
    assert.throws(() => _test.normalizedRole(role));
  }
  assert.equal(_test.validPassword({password: "Abcd123!"}), false);
  assert.equal(_test.profileComplete("USER", {fullName: [], dob: {}, language: 9, address: {city: []}}), false);
});

// Inputs: strings beyond every account field limit. Expected: helpers cap stored text and reject oversized usernames and passwords.
test("account input lengths cannot overflow field limits", () => {
  assert.equal(_test.text("x".repeat(5000), 120).length, 120);
  assert.throws(() => _test.normalizedUsername("a".repeat(100000)));
  assert.equal(_test.validPassword(`A1!${"x".repeat(100000)}`), false);
});

// Inputs: protected names, casing, owner claims, and similar-looking names. Expected: only exact protected accounts are undeletable.
test("protected account detection is exact and claim aware", () => {
  assert.equal(_test.protectedAccount({}, "DemoAdmin"), true);
  assert.equal(_test.protectedAccount({}, " demoStaff "), true);
  assert.equal(_test.protectedAccount({customClaims:{owner:true}}, "ordinary_user"), true);
  assert.equal(_test.protectedAccount({customClaims:{protected:true}}, "ordinary_user"), true);
  assert.equal(_test.protectedAccount({}, "demoadmin_copy"), false);
  assert.equal(_test.protectedAccount({}, "staff"), false);
});

// Inputs: conflicting claims, profiles, and aliases. Expected: only trusted claims grant staff or admin authority.
test("role resolution grants privileged roles only from trusted claims", () => {
  assert.equal(_test.roleFromUser({customClaims:{role:"ADMIN"},email:"x@users.olive.invalid"},{role:"USER"}),"ADMIN");
  assert.equal(_test.roleFromUser({customClaims:{},email:"x@users.olive.invalid"},{role:"STAFF"}),"USER");
  assert.equal(_test.roleFromUser({customClaims:{},email:"x@safe.olive.invalid"},{}),"SAFE_CONTACT");
  assert.equal(_test.roleFromUser({customClaims:{role:"ROOT"},email:"x@admin.olive.invalid"},{role:"ADMIN"}),"USER");
  assert.equal(_test.roleFromUser({customClaims:{},email:"x@staff.olive.invalid"},{role:"STAFF"}),"USER");
  assert.equal(_test.roleFromUser({customClaims:{},email:"person@example.ca"},{}),"USER");
});

// Inputs: organization email variants and malformed addresses. Expected: real addresses normalize and bad ones are rejected.
test("organization sign-in emails reject malformed addresses", () => {
  assert.equal(_test.signInEmail("staff_name","STAFF"," Staff@Example.CA "),"staff@example.ca");
  for (const email of ["missing-at.example.ca","missing-domain@","@missing-user.ca","two words@example.ca"]) {
    assert.throws(() => _test.signInEmail("staff_name","STAFF",email));
  }
  assert.equal(_test.signInEmail("staff_name","STAFF",""),"staff_name@yourolivebranch.ca");
});

// Inputs: exact and over-limit password lengths with Unicode. Expected: policy accepts only complete bounded credentials.
test("password policy enforces Unicode requirements and exact length bounds", () => {
  assert.equal(_test.validPassword("Étoile12!"),true);
  assert.equal(_test.validPassword("橄榄枝1234!"),true);
  assert.equal(_test.validPassword("A1!" + "x".repeat(21)),true);
  assert.equal(_test.validPassword("A1!" + "x".repeat(22)),false);
  assert.equal(_test.validPassword("A1!x"),false);
});

// Inputs: user, safe-contact, staff, and admin drafts. Expected: private user fields never leak into other role profiles.
test("default profiles keep role-specific fields separated", () => {
  const input={fullName:" Person ",dob:"2000-01-01",language:"French",affectedNames:"Child",
    abuserName:"Private",situation:"Support",phone:"555",address:{city:"Edmonton"}};
  const user=_test.defaultProfile("olive_user","USER",input,"olive_user@users.olive.invalid");
  assert.equal(user.affectedNames,"Child");
  assert.equal(user.abuserName,"Private");
  assert.equal(user.situation,"Support");
  const safe=_test.defaultProfile("olive_safe","SAFE_CONTACT",input,"olive_safe@safe.olive.invalid");
  assert.equal(safe.affectedNames,"");
  assert.equal(safe.abuserName,"");
  assert.equal(safe.situation,"");
  const staff=_test.defaultProfile("olive_staff","STAFF",input,"staff@example.ca");
  assert.equal(staff.language,"English");
  assert.equal(staff.email,"staff@example.ca");
});

// Inputs: oversized profile strings and a non-object address. Expected: every stored field is bounded and defaults remain valid.
test("default profile construction bounds untrusted fields", () => {
  const huge="x".repeat(10000);
  const profile=_test.defaultProfile("olive_user","USER",{fullName:huge,dob:huge,language:huge,
    situation:huge,region:huge,phone:huge,address:"wrong"},"");
  assert.equal(profile.fullName.length,120);
  assert.equal(profile.dob.length,10);
  assert.equal(profile.language.length,60);
  assert.equal(profile.situation.length,2000);
  assert.equal(profile.region.length,200);
  assert.equal(profile.phone.length,40);
  assert.equal(profile.address.country,"Canada");
});

// Inputs: plan-consult and support ticket creation. Expected: IDs use correct prefixes and never reuse a prior room.
test("each support request receives a unique origin-specific conversation id", () => {
  const supportOne=supportGroups.supportConversationId("owner-1",supportGroups.SUPPORT);
  const supportTwo=supportGroups.supportConversationId("owner-1",supportGroups.SUPPORT);
  const consultation=supportGroups.supportConversationId("owner-1",supportGroups.PLAN_CONSULT);
  assert.match(supportOne,/^support-owner-1-[A-Za-z0-9-]+$/);
  assert.match(consultation,/^plan-consult-owner-1-[A-Za-z0-9-]+$/);
  assert.notEqual(supportOne,supportTwo);
  assert.notEqual(supportOne,consultation);
});

// Inputs: missing, scalar, array, temporary-email, and complete profiles. Expected: completion never trusts wrong runtime types.
test("profile completion rejects malformed and temporary organization profiles", () => {
  for (const profile of [null,"profile",[],42]) assert.equal(_test.profileComplete("USER",profile),false);
  assert.equal(_test.profileComplete("USER",{fullName:"A",dob:"2000-1-1",language:"English",address:{city:"Edmonton"}}),false);
  assert.equal(_test.profileComplete("STAFF",{fullName:"A",dob:"2000-01-01",email:"staff@staff.olive.invalid"}),false);
  assert.equal(_test.profileComplete("STAFF",{fullName:"A",dob:"2000-01-01",email:"staff@example.ca"}),true);
});

const {
  TOPIC_APP_UPDATES,
  buildUpdateNotification,
  parseManifestConfig,
  pruneOldReleases,
} = require("./scripts/publish-android-release");

test("release cleanup keeps the active APK and the immediately preceding build", async () => {
  const apk = (version, label = `v1.0.0.${version}`) => `public/android/releases/${version}/OliveBranch${label}.apk`;
  const active = apk(101);
  const legacy = "public/android/releases/97/olive-branch-v1.0.0.97.apk";
  const names = [apk(98), apk(100), active, apk(99), apk(102), legacy, "public/android/releases/97/readme.txt", apk(101, "v0.0.0.1")];
  const removed = [];
  const bucket = {getFiles: async ({prefix}) => {
    assert.equal(prefix, "public/android/releases/");
    return [names.map(name => ({name, metadata: {generation: "123"}, delete: async options => {
      assert.equal(options.ifGenerationMatch, "123");
      removed.push(name);
    }}))];
  }};
  const manifest = {versionCode: 101, apkUrl: `https://example.com/o/${encodeURIComponent(active)}?alt=media`};
  assert.equal(await pruneOldReleases(bucket, manifest), 4);
  assert.deepEqual(new Set(removed), new Set([apk(98), apk(99), legacy, apk(101, "v0.0.0.1")]));
  removed.length = 0;
  await assert.rejects(pruneOldReleases(bucket, {...manifest, versionCode: 103}), /does not point/);
  assert.deepEqual(removed, []);
});

// Inputs: valid release details. Expected: FCM app update payload targets high priority and the dedicated topic.
test("fcm app update notifications target the app_updates topic with high priority", () => {
  const payload = buildUpdateNotification({ versionCode: 10001, versionName: "1.0.0" });
  assert.equal(payload.topic, TOPIC_APP_UPDATES);
  assert.equal(payload.data.type, "app_update");
  assert.equal(payload.data.versionCode, "10001");
  assert.equal(payload.data.versionName, "1.0.0");
  assert.equal(payload.android.priority, "high");
});

// Inputs: missing and valid release configuration. Expected: validation strictly enforces project and version fields.
test("release manifest parser enforces matching projects and valid version codes", () => {
  assert.throws(() => parseManifestConfig({}), /OLIVE_RELEASE_PROJECT_ID/);
  const config = parseManifestConfig({
    OLIVE_RELEASE_PROJECT_ID: "proj-1",
    OLIVE_RELEASE_CONFIRM_PROJECT: "proj-1",
    OLIVE_RELEASE_APK: "test.apk",
    OLIVE_VERSION_CODE: "10002",
    OLIVE_VERSION_NAME: "v1.0.1",
    OLIVE_MINIMUM_VERSION_CODE: "1",
    OLIVE_RELEASE_NOTES: "Feature 1\nFeature 2",
  });
  assert.equal(config.versionCode, 10002);
  assert.equal(config.versionName, "v1.0.1");
  assert.deepEqual(config.notes, ["Feature 1", "Feature 2"]);
});
