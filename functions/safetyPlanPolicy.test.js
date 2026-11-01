// Tests who can edit and share a user's safety plan.
const test = require("node:test");
const assert = require("node:assert/strict");
const {mergeFields, staffEligible, chooseStaff} = require("./src/safetyPlanPolicy");
process.env.FIREBASE_CONFIG = JSON.stringify({projectId:"olive-test",storageBucket:"olive-test.appspot.com"});
const {canEdit} = require("./src/safetyPlanAccess");
// Inputs: the values in this case. Expected: offline concurrent edits retain the server version until explicitly resolved.
test("offline concurrent edits retain the server version until explicitly resolved", () => {
  const fields = {primary_destination: {value:"Staff location", revision:2, mutationId:"staff"}};
  const result = mergeFields(fields, {}, [{key:"primary_destination", value:"Owner location", revision:1, mutationId:"owner"}]);
  assert.deepEqual(result.conflicts, ["primary_destination"]);
  assert.equal(result.fields.primary_destination.value, "Staff location");
});
// Inputs: the values in this case. Expected: retries are idempotent and unrelated sections merge.
test("retries are idempotent and unrelated sections merge", () => {
  const change = {key:"primary_destination", value:"Safe place", revision:0, mutationId:"unique"};
  const first = mergeFields({}, {}, [change]);
  assert.deepEqual(mergeFields(first.fields, {}, [change]), first);
  assert.equal(mergeFields(first.fields, {}, [{key:"backup_contacts", value:"Friend", revision:0, mutationId:"second"}]).fields.backup_contacts.value, "Friend");
});
// Inputs: the values in this case. Expected: malformed and oversized edits cannot enter the store.
test("malformed and oversized edits cannot enter the store", () => {
  for (const patch of [{key:"unknown",value:"x",revision:0,mutationId:"a"}, {key:"primary_destination",value:"x".repeat(4001),revision:0,mutationId:"a"}]) {
    assert.throws(() => mergeFields({}, {}, [patch]));
  }
});

// Inputs: wrong JavaScript types for defaults, changes, revisions, and values. Expected: the safety-plan merge rejects each request.
test("wrong safety plan variable types fail validation", () => {
  for (const defaults of [null, [], "text", 42]) {
    assert.throws(() => mergeFields({}, defaults, []));
  }
  for (const changes of [null, {}, "change", 42]) {
    assert.throws(() => mergeFields({}, {}, changes));
  }
  const base = {key:"primary_destination", value:"Safe place", revision:0, mutationId:"typed"};
  for (const patch of [
    {...base, value: 123},
    {...base, revision: "0"},
    {...base, revision: Number.MAX_SAFE_INTEGER + 1},
    {...base, mutationId: {id:"typed"}},
  ]) assert.throws(() => mergeFields({}, {}, [patch]));
});

// Inputs: SQL-like text inside a valid safety-plan value. Expected: it is stored as inert text because Firebase is not queried as SQL.
test("injection-like safety plan text remains plain encrypted content", () => {
  const injectionText = "'); DROP TABLE safety_plans; --";
  const result = mergeFields({}, {}, [{
    key:"primary_destination",
    value:injectionText,
    revision:0,
    mutationId:"inert-text",
  }]);
  assert.equal(result.fields.primary_destination.value, injectionText);
});
// Inputs: the values in this case. Expected: owners, assigned staff, and admins can edit; safe contacts cannot.
test("owners, assigned staff, and admins can edit; safe contacts cannot", () => {
  const plan = {staffUid:"staff",consultationRequested:true};
  assert.equal(canEdit({uid:"owner",role:"USER"},plan,"owner"),true);
  assert.equal(canEdit({uid:"staff",role:"STAFF"},plan,"owner"),true);
  assert.equal(canEdit({uid:"admin",role:"ADMIN"},plan,"owner"),true);
  for (const actor of [{uid:"other",role:"USER"},{uid:"staff",role:"USER"},{uid:"staff",role:"SAFE_CONTACT"}]) assert.equal(canEdit(actor,plan,"owner"),false);
  assert.equal(canEdit({uid:"staff",role:"STAFF"},{...plan,staffUid:""},"owner"),false);
});
// Inputs: the values in this case. Expected: automatic assignment excludes disabled/unverified staff and balances available staff.
test("automatic assignment excludes disabled/unverified staff and balances available staff", () => {
  const user = {disabled:false,customClaims:{role:"STAFF"}};
  const profile = {fullName:"Person",email:"person@example.ca",dob:"2000-01-01"};
  assert.equal(staffEligible(user,profile),true);
  assert.equal(staffEligible({...user,disabled:true},profile),false);
  assert.equal(staffEligible({...user,customClaims:{}},profile),false);
  assert.equal(staffEligible(user,{}),false);
  assert.equal(chooseStaff([{uid:"a",available:false,activeCount:0},{uid:"b",activeCount:2},{uid:"c",activeCount:1}]).uid,"c");
  assert.equal(chooseStaff([{uid:"a",available:false}]),undefined);
});

// Inputs: the values in this case. Expected: staff assignment randomizes only between equally least-loaded staff.
test("staff assignment randomizes only between equally least-loaded staff", () => {
  const candidates = [
    {uid:"busy",activeCount:4},
    {uid:"first",activeCount:1},
    {uid:"second",activeCount:1},
  ];
  assert.equal(chooseStaff(candidates, () => 0).uid, "first");
  assert.equal(chooseStaff(candidates, () => 0.999).uid, "second");
});

// Inputs: the values in this case. Expected: document and quick-grab sections can be added without replacing legacy notes.
test("document and quick-grab sections can be added without replacing legacy notes", () => {
  const old = {primary_contacts:{value:"Trusted person",revision:3,mutationId:"old"}};
  const result = mergeFields(old,{primary_documents:"ID checklist",primary_grab:"Keys and phone"},[]);
  assert.deepEqual(result.fields.primary_contacts,old.primary_contacts);
  assert.equal(result.fields.primary_documents.value,"ID checklist");
  assert.equal(result.fields.primary_grab.value,"Keys and phone");
});
