// Tests that cloud endpoints for accounts, support, and media keep their public names.
const test = require("node:test");
const assert = require("node:assert/strict");
process.env.FIREBASE_CONFIG = JSON.stringify({projectId: "olive-test", storageBucket: "olive-test.appspot.com"});
const { _test } = require("./index");
const mediaTest = require("./src/mediaTickets")._test;
const supportGroups = require("./src/supportGroups");

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

// Inputs: protected names, casing, owner claims, and similar-looking names. Expected: only exact protected accounts are undeletable.
test("protected account detection is exact and claim aware", () => {
  assert.equal(_test.protectedAccount({}, "DemoAdmin"), true);
  assert.equal(_test.protectedAccount({}, " demoStaff "), true);
  assert.equal(_test.protectedAccount({customClaims:{owner:true}}, "ordinary_user"), true);
  assert.equal(_test.protectedAccount({customClaims:{protected:true}}, "ordinary_user"), true);
  assert.equal(_test.protectedAccount({}, "demoadmin_copy"), false);
  assert.equal(_test.protectedAccount({}, "staff"), false);
});

// Inputs: conflicting role claim, profile role, and email aliases. Expected: trusted claims win, then profile, then legacy email.
test("role resolution follows claim profile and legacy email precedence", () => {
  assert.equal(_test.roleFromUser({customClaims:{role:"ADMIN"},email:"x@users.olive.invalid"},{role:"USER"}),"ADMIN");
  assert.equal(_test.roleFromUser({customClaims:{},email:"x@users.olive.invalid"},{role:"STAFF"}),"STAFF");
  assert.equal(_test.roleFromUser({customClaims:{},email:"x@safe.olive.invalid"},{}),"SAFE_CONTACT");
  assert.equal(_test.roleFromUser({customClaims:{role:"ROOT"},email:"x@admin.olive.invalid"},{role:"ROOT"}),"ADMIN");
  assert.equal(_test.roleFromUser({customClaims:{},email:"person@example.ca"},{}),"USER");
});

// Inputs: organization email variants and malformed addresses. Expected: real addresses normalize and bad ones are rejected.
test("organization sign-in emails reject malformed addresses", () => {
  assert.equal(_test.signInEmail("staff_name","STAFF"," Staff@Example.CA "),"staff@example.ca");
  for (const email of ["missing-at.example.ca","missing-domain@","@missing-user.ca","two words@example.ca"]) {
    assert.throws(() => _test.signInEmail("staff_name","STAFF",email));
  }
  assert.equal(_test.signInEmail("staff_name","STAFF",""),"staff_name@staff.olive.invalid");
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
