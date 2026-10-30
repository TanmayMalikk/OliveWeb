// Tests that cloud endpoints for accounts, support, and media keep their public names.
const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({projectId: "olive-test", storageBucket: "olive-test.appspot.com"});
const { _test } = require("./index");
const mediaTest = require("./src/mediaTickets")._test;

// Inputs: the values in this case. Expected: deployment keeps the existing callable endpoint names.
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

// Inputs: the values in this case. Expected: encrypted media access keeps the owner and exact wrapped-key recipients.
test("encrypted media access keeps the owner and exact wrapped-key recipients", () => {
  assert.deepEqual(
    mediaTest.accessRequest({wrappedKeys:{owner:"owner-key",staff:"staff-key"}}, "owner"),
    {wrappedKeys:{owner:"owner-key",staff:"staff-key"},allowedUsers:["owner","staff"]},
  );
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{staff:"staff-key"}}, "owner"));
  assert.throws(() => mediaTest.accessRequest({wrappedKeys:{}}, "owner"));
});

// Inputs: the values in this case. Expected: role aliases and real organization emails are deterministic.
test("role aliases and real organization emails are deterministic", () => {
  assert.equal(_test.signInEmail("alex_1", "USER"), "alex_1@users.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "SAFE_CONTACT"), "alex_1@safe.olive.invalid");
  assert.equal(_test.signInEmail("alex_1", "STAFF"), "alex_1@staff.olive.invalid");
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
