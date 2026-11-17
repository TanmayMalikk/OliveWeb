// A transaction-aware in-memory adapter. No Firebase project or network is used.
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
class HttpsError extends Error { constructor(code, message) { super(message); this.code = code; } }
module.exports = function fixture(overrides = {}) {
  const records = new Map(), users = new Map(), profiles = new Map();
  let caller = {uid: "owner", role: "USER", name: "Owner"}, sequence = 0;
  const deleted = Symbol("delete");
  function apply(ref, value, merge) {
    const next = merge ? {...records.get(ref.path), ...value} : {...value};
    for (const [key, field] of Object.entries(next)) if (field === deleted) delete next[key];
    records.set(ref.path, next);
  }
  const snapshot = ref => ({id: ref.id, ref, exists: records.has(ref.path), data: () => records.get(ref.path), get: key => records.get(ref.path)?.[key]});
  function reference(location, filters = [], maximum = Infinity) {
    return {
      path: location, id: location.split("/").at(-1),
      get parent() { return reference(location.split("/").slice(0, -1).join("/")); },
      doc: id => reference(`${location}/${id ?? `auto-${++sequence}`}`),
      collection: name => reference(`${location}/${name}`),
      where: (field, op, value) => reference(location, [...filters, [field, op, value]], maximum),
      limit: count => reference(location, filters, count),
      listDocuments: async () => [...new Set([...records.keys()].filter(key => key.startsWith(`${location}/`)).map(key => key.slice(location.length + 1).split("/")[0]))].map(id => reference(`${location}/${id}`)),
      count: function() { return {get: async () => { const count = (await this.get()).size; return {data: () => ({count})}; }}; },
      get: async function() {
        if (location.split("/").length % 2 === 0) return snapshot(this);
        const docs = [...records.keys()].filter(key => key.startsWith(`${location}/`) && key.split("/").length === location.split("/").length + 1)
          .map(key => snapshot(reference(key))).filter(doc => filters.every(([key, op, value]) => op === "array-contains" ? doc.get(key)?.includes(value) : doc.get(key) === value)).slice(0, maximum);
        return {docs, empty: !docs.length, size: docs.length};
      },
      create: async function(value) { if (records.has(this.path)) throw new Error("Already exists"); apply(this, value); },
      set: async function(value, options) { apply(this, value, options?.merge); },
      update: async function(value) { apply(this, value, true); },
      delete: async function() { records.delete(this.path); },
    };
  }
  function writer() {
    const operations = [];
    return {
      get: ref => ref.get(),
      set: function(ref, value, options) { operations.push(() => apply(ref, value, options?.merge)); return this; },
      update: function(ref, value) { operations.push(() => apply(ref, value, true)); return this; },
      create: function(ref, value) { operations.push(() => {if (records.has(ref.path)) throw new Error("Already exists"); apply(ref, value);}); return this; },
      delete: function(ref) { operations.push(() => records.delete(ref.path)); return this; },
      commit: async () => operations.forEach(action => action()),
    };
  }
  const db = {recursiveDelete: async ref => {for (const key of records.keys()) if (key === ref.path || key.startsWith(ref.path + "/")) records.delete(key);}, collection: name => reference(name), batch: writer,
    getAll: (...refs) => Promise.all(refs.map(ref => ref.get())),
    runTransaction: async action => {const tx = writer(); const result = await action(tx); await tx.commit(); return result;},
  };
  const cache = new Map();
  const imports = {
    "firebase-functions/v2/firestore": {onDocumentWritten: (...args) => args.at(-1), onDocumentCreated: (...args) => args.at(-1)},
    "firebase-functions/v2/https": {onCall: (...args) => args.at(-1), HttpsError},
    "./firebaseAdminClient": {db, auth: {getUser: async uid => {
      if (!users.has(uid)) throw Object.assign(new Error("No user"), {code: "auth/user-not-found"});
      return users.get(uid);
    },
      createUser: async value => {
        if ([...users.values()].some(user => user.email === value.email)) throw Object.assign(new Error("Duplicate"), {code: "auth/email-already-exists"});
        const user = {uid: `created-${++sequence}`, ...value}; users.set(user.uid, user); return user;
      },
      setCustomUserClaims: async (uid, claims) => {users.get(uid).customClaims = claims;},
      updateUser: async (uid, fields) => Object.assign(users.get(uid), fields),
      deleteUser: async uid => users.delete(uid),
    }, bucket: {deleteFiles: async () => {}}, FieldValue: {serverTimestamp: () => ({toMillis: () => 1000 + sequence}), delete: () => deleted}},
    "./accountStorage": {
      listEveryUser: async () => [...users.values()], readProfile: async uid => profiles.get(uid),
      writeProfile: async (uid, profile) => profiles.set(uid, profile),
      saveOrganizationLogin: async () => {}, deleteOrganizationLogin: async () => {},
      lockedOrganizationAlias: async (_uid, username, role) => `${username}@${role.toLowerCase()}.olive.invalid`,
      usernameExistsGlobally: async username => records.has(`accountDirectory/${username}`),
      requireAdmin: async () => { if (caller.role !== "ADMIN") throw new HttpsError("permission-denied", "Admin only"); return {caller}; },
    },
    "./safetyPlanAccess": {
      safetyCaller: async () => caller,
      planReference: uid => load("safetyPlanAccess").planReference(uid),
      canEdit: (...args) => load("safetyPlanAccess").canEdit(...args),
      canRead: (...args) => load("safetyPlanAccess").canRead(...args),
    },
    "./supportTicketRepair": {repairPlanTicket: async () => {}},
  };
  function load(name) {
    if (cache.has(name)) return cache.get(name);
    const filename = path.resolve(__dirname, "../src", `${name}.js`);
    const result = {exports: {}}; cache.set(name, result.exports);
    vm.compileFunction(fs.readFileSync(filename, "utf8"), ["require", "exports", "module"], {filename})(
      id => {
        if (overrides[id]) return overrides[id];
        if (imports[id]) return imports[id];
        if (id.startsWith("./")) return load(id.slice(2));
        return require(id);
      }, result.exports, result,
    );
    cache.set(name, result.exports); return result.exports;
  }
  return {records, users, profiles, db, load, services: imports, setCaller: value => {caller = value;}};
};
