const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");
function prepared() {
  const f = fixture(), files = new Map(), metadata = new Map();
  const services = f.services["./firebaseAdminClient"];
  services.bucket.file = name => ({
    download: async () => { if (!files.has(name)) throw Object.assign(new Error("Missing"), {code: 404}); return [Buffer.from(files.get(name))]; },
    save: async (value, options) => {files.set(name, value); metadata.set(name, options);},
  });
  services.auth.listUsers = async () => ({users: [...f.users.values()]});
  services.auth.getUserByEmail = async email => {
    const user = [...f.users.values()].find(user => user.email === email);
    if (!user) throw Object.assign(new Error("Missing"), {code: "auth/user-not-found"}); return user;
  };
  return {...f, files, metadata, api: f.load("accountStorage")};
}
test("profile storage round-trips versioned private JSON and distinguishes missing files from corrupt files", async () => {
  const f = prepared();
  assert.equal(await f.api.readProfile("missing"), null);
  const profile = {username: "fixture_user", fullName: "Fixture User"};
  await f.api.writeProfile("owner", profile);
  assert.deepEqual(await f.api.readProfile("owner"), profile);
  assert.equal(f.metadata.get("accounts/owner/profile.json").metadata.metadata.schemaVersion, "3");
  f.files.set("accounts/corrupt/profile.json", "not json");
  await assert.rejects(f.api.readProfile("corrupt"), SyntaxError);
});
test("admin authorization trusts authenticated roles and rejects missing or ordinary callers", async () => {
  const f = prepared();
  await assert.rejects(f.api.requireAdmin({}), {code: "unauthenticated"});
  f.users.set("user", {uid: "user", customClaims: {role: "USER"}});
  f.users.set("admin", {uid: "admin", customClaims: {role: "ADMIN"}});
  await assert.rejects(f.api.requireAdmin({auth: {uid: "user"}}), {code: "permission-denied"});
  assert.equal((await f.api.requireAdmin({auth: {uid: "admin"}})).caller.uid, "admin");
});
test("organization contact aliases cannot be claimed by a different account", async () => {
  const f = prepared();
  await f.api.saveOrganizationLogin("staff", "staff_name", "STAFF", "contact@example.invalid");
  await f.api.saveOrganizationLogin("staff", "staff_name", "STAFF", "contact@example.invalid");
  await assert.rejects(f.api.saveOrganizationLogin("other", "other_name", "STAFF", "contact@example.invalid"), {code: "already-exists"});
  await f.api.deleteOrganizationLogin("contact@example.invalid");
  assert.equal([...f.records.keys()].filter(key => key.startsWith("organizationLoginAliases/")).length, 0);
  await f.api.saveOrganizationLogin("owner", "owner_name", "USER", "contact@example.invalid");
  assert.equal(f.records.size, 0);
});
test("legacy accounts keep their original Auth address and protected aliases stay role-scoped", async () => {
  const f = prepared();
  f.users.set("staff", {uid: "staff", email: "staff_name@yourolivebranch.ca"});
  f.records.set("accountDirectory/staff_name", {uid: "staff", role: "STAFF"});
  const alias = await f.api.lockedOrganizationAlias("staff", "staff_name", "STAFF");
  assert.equal(alias, "staff_name@yourolivebranch.ca");
  assert.equal(f.records.get("accountDirectory/staff_name").loginAlias, alias);
  assert.equal(f.users.get("staff").email, alias);
  assert.equal(await f.api.lockedOrganizationAlias("staff", "staff_name", "STAFF"), alias);
  f.users.get("staff").email = "changed@example.com";
  assert.equal(await f.api.lockedOrganizationAlias("staff", "staff_name", "STAFF"), alias);
  assert.equal(f.users.get("staff").email, alias);
  await assert.rejects(f.api.lockedOrganizationAlias("other", "staff_name", "STAFF"), {code: "not-found"});
  await assert.rejects(f.api.lockedOrganizationAlias("staff", "staff_name", "USER"), {code: "invalid-argument"});
});
test("global username checks include legacy emails, display names and the protected directory", async () => {
  const f = prepared();
  f.records.set("accountDirectory/directory_name", {uid: "directory"});
  f.users.set("name", {uid: "name", displayName: " DISPLAY_NAME "});
  f.users.set("legacy", {uid: "legacy", email: "legacy_name@safe.olive.invalid"});
  for (const name of ["directory_name", "display_name", "legacy_name"]) assert.equal(await f.api.usernameExistsGlobally(name), true);
  assert.equal(await f.api.usernameExistsGlobally("available_name"), false);
});
test("account listing follows pagination without dropping later organization accounts", async () => {
  const f = prepared(); const tokens = [];
  f.services["./firebaseAdminClient"].auth.listUsers = async (size, token) => {
    tokens.push(token); assert.equal(size, 1000);
    return token ? {users: [{uid: "second"}]} : {users: [{uid: "first"}], pageToken: "next-page"};
  };
  assert.deepEqual((await f.api.listEveryUser()).map(user => user.uid), ["first", "second"]);
  assert.deepEqual(tokens, [undefined, "next-page"]);
});
