// Contains the index.test code for this part of Olive.
const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({projectId: "olive-test", storageBucket: "olive-test.appspot.com"});
const { _test } = require("./index");
const mediaTest = require("./src/mediaTickets")._test;

test("deployment keeps the existing callable endpoint names", () => {
  const endpoints = require("./index");
  const names = ["adminListAccounts", "adminCreateAccount", "adminDeleteAccount", "createPublicAccount",
    "completeOrganizationProfile", "resolveOrganizationLogin", "restoreOrganizationLoginAlias",
    "safetyPlanSync", "safetyPlanConsultations", "safetyPlanAvailability", "safetyPlanConsult",
    "safetyPlanShareContacts", "safetyPlansSharedWithMe", "mediaCreateUpload",
    "mediaUploadPartTicket", "mediaCompleteUpload", "mediaAbortUpload",
    "mediaDownloadTicket", "mediaUpdateAccess", "mediaDelete", "staffSupportRequest",
    "safetySupportContact", "safetySupportAdmin", "staffSupportClose"];
  assert.deepEqual(Object.keys(endpoints).filter((name) => name !== "_test").sort(), names.sort());
  names.forEach((name) => assert.equal(typeof endpoints[name], "function"));
});

test("encrypted media access keeps the owner and exact wrapped-key recipients", () => {
  assert.deepEqual(
    mediaTest.accessRequest({wrappedKeys:{owner:"owner-key",staff:"staff-key"}}, "owner"),
    {wrappedKeys:{owner:"owner-key",staff:"staff-key"},allowedUsers:["owner","staff"]},
  );
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{staff:"staff-key"}}, "owner"));
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{}}, "owner"));
});

test("role aliases and real organization emails are deterministic", () => {
  assert.equal(_test.signInEmail("alex_1", "USER"), "alex_1@users.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "SAFE_CONTACT"), "alex_1@safe.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "STAFF"), "alex_1@staff.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "ADMIN", "Admin@Example.ca"), "admin@example.ca");
  assert.equal(_test.organizationEmailKey(" Admin@Example.ca "), _test.organizationEmailKey("admin@example.ca"));
});

test("profile completion follows each role's required fields", () => {
  assert.equal(_test.profileComplete("USER", {fullName:"A",dob:"2000-01-01",language:"English",address:{city:"Edmonton"}}), true);
  assert.equal(_test.profileComplete("USER", {fullName:"A",dob:"2000-01-01",language:"English",address:{city:""}}), false);
  assert.equal(_test.profileComplete("STAFF", {fullName:"A",dob:"2000-01-01",email:"a@example.ca"}), true);
  assert.equal(_test.profileComplete("ADMIN", {fullName:"A",dob:"2000-01-01",email:"a@admin.olive.invalid"}), false);
});

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
