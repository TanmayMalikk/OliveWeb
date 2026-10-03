const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({projectId: "olive-test", storageBucket: "olive-test.appspot.com"});
const { _test } = require("./index");

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
