import {before, after, test} from "node:test";
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {initializeTestEnvironment, assertFails} from "@firebase/rules-unit-testing";
import {collection, doc, getDoc, getDocFromServer, getDocsFromServer, onSnapshot, query, setDoc, updateDoc, serverTimestamp, where} from "firebase/firestore";
import {readFile} from "node:fs/promises";
const require = createRequire(new URL("../functions/package.json", import.meta.url));
process.env.FIREBASE_CONFIG = JSON.stringify({projectId:"demo-olive-rules",storageBucket:"demo-olive-rules.appspot.com"});
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8180";
const {auth, db, bucket} = require("./src/firebaseAdminClient");
const {safetyPlanSync, safetyPlanConsultations, safetyPlanAvailability, safetyPlanShareContacts,
  safetyPlansSharedWithMe} = require("./src/safetyPlans");
const {safetyPlanConsult} = require("./src/safetyConsultations");
const {safetySupportAdmin, staffSupportClose, conversationAddParticipant, conversationRemoveParticipant} = require("./src/supportGroups");
const {conversationPrepareKeys, supportTicketsPrepare, repairPlanTicket, repairConversation} = require("./src/supportTicketRepair");
const {staffSupportRequest} = require("./src/supportTickets");
const {mediaShareInChat} = require("./src/mediaTickets");
const users = new Map([
  ["plan-owner",{uid:"plan-owner",email:"owner@users.olive.invalid",displayName:"Owner"}],
  ["plan-live-owner",{uid:"plan-live-owner",email:"live-owner@users.olive.invalid",displayName:"Live owner"}],
  ["plan-legacy-owner",{uid:"plan-legacy-owner",email:"legacy@users.olive.invalid",displayName:"Legacy owner"}],
  ["plan-checklist-owner",{uid:"plan-checklist-owner",email:"checklist@users.olive.invalid",displayName:"Checklist owner"}],
  ["support-owner",{uid:"support-owner",email:"support-owner@users.olive.invalid",displayName:"Support owner"}],
  ["plan-share-owner",{uid:"plan-share-owner",email:"share-owner@users.olive.invalid",displayName:"Sharing owner"}],
  ["plan-other",{uid:"plan-other",email:"other@users.olive.invalid"}],
  ["plan-contact",{uid:"plan-contact",email:"contact@safe.olive.invalid",displayName:"Safe contact"}],
  ["plan-live-contact",{uid:"plan-live-contact",email:"live-contact@safe.olive.invalid",displayName:"Live safe contact"}],
  ["plan-staff",{uid:"plan-staff",customClaims:{role:"STAFF"},displayName:"Staff"}],
  ["plan-staff2",{uid:"plan-staff2",customClaims:{role:"STAFF"},displayName:"Staff two"}],
  ["plan-admin",{uid:"plan-admin",customClaims:{role:"ADMIN"},displayName:"Admin"}],
  ["plan-forged",{uid:"plan-forged",email:"forged@users.olive.invalid",displayName:"Forged"}],
]);
let env;
const originalGetUser=auth.getUser, originalListUsers=auth.listUsers, originalFile=bucket.file;
before(async () => {
  env=await initializeTestEnvironment({projectId:"demo-olive-rules",firestore:{host:"127.0.0.1",port:8180,rules:await readFile(new URL("./firestore.rules",import.meta.url),"utf8")}});
  auth.getUser=async (uid) => { const user=users.get(uid);if(!user) throw Error("Unknown account");return user; };
  auth.listUsers=async () => ({users:[...users.values()]});
  bucket.file=() => ({download:async () => [Buffer.from(JSON.stringify({role:"STAFF",fullName:"Verified staff",email:"staff@example.ca",dob:"2000-01-01"}))]});
});

test("open user, safe contact and staff listeners receive edits and a completed consultation revokes plan grants", {timeout: 25000}, async (t) => {
  const owner = "plan-live-owner", contact = "plan-live-contact";
  const fileId = "plan-live-attachment";
  const documents = JSON.stringify({oliveSafetyChecklist: 1, items: [
    {id: "documents", text: "Test attachment", checked: false, attachmentIds: [fileId]},
  ]});
  const update = (uid, value, revision, mutationId) => call(safetyPlanSync, uid, {ownerUid: owner,
    changes: [{key: "primary_destination", value, revision, mutationId}]});
  await call(safetyPlanSync, owner, {ownerUid: owner, defaults: {
    primary_destination: "Initial", primary_documents: documents,
  }});
  await db.collection("friendLists").doc(owner).collection("friends").doc("live-contact").set({friendUid: contact});
  await call(safetyPlanShareContacts, owner, {contacts: [{uid: contact, friendshipId: "live-contact"}]});
  await Promise.all(["plan-staff", "plan-staff2"].map(uid => call(safetyPlanAvailability, uid, {available: true})));
  const {plan} = await call(safetyPlanConsult, owner, {action: "request"});
  const staff = plan.staffUid;
  assert.equal(plan.staffName, "");
  const ticket = db.collection("conversations").doc(plan.consultationConversationId);
  assert.equal((await ticket.get()).get("staffUid"), staff);
  const listeners = [
    [owner, "USER"], [contact, "SAFE_CONTACT"], [staff, "STAFF"],
  ].map(([uid, role]) => {
    const client = env.authenticatedContext(uid, {role}).firestore();
    const seen = new Set();
    const unsubscribe = onSnapshot(doc(client, "safetyPlans", owner), snapshot => {
      if (!snapshot.metadata.fromCache) seen.add(snapshot.get("fields.primary_destination.value"));
    }, error => seen.add(error.code));
    t.after(unsubscribe);
    return {client, seen};
  });
  const waitFor = async condition => {
    const end = Date.now() + 7000;
    while (!condition()) {
      assert.ok(Date.now() < end, "Timed out waiting for live Firestore listeners");
      await new Promise(resolve => setTimeout(resolve, 20));
    }
  };
  await waitFor(() => listeners.every(({seen}) => seen.has("Initial")));
  await update(owner, "Owner edit", 1, "owner-live");
  await waitFor(() => listeners.every(({seen}) => seen.has("Owner edit")));
  await update(staff, "Staff edit", 2, "staff-live");
  await waitFor(() => listeners.every(({seen}) => seen.has("Staff edit")));
  const stale = await update(staff, "Staff proposal", 2, "staff-stale");
  assert.deepEqual(stale.conflicts, ["primary_destination"]);
  assert.equal(stale.plan.pendingConflicts.primary_destination.value, "Staff proposal");
  const resolution = {key: "primary_destination", revision: 3, mutationId: "staff-stale", useIncoming: true};
  await assert.rejects(call(safetyPlanSync, staff, {ownerUid: owner, resolution}));
  await call(safetyPlanSync, owner, {ownerUid: owner, resolution});
  await waitFor(() => listeners.every(({seen}) => seen.has("Staff proposal")));
  await update(staff, "Another proposal", 2, "staff-older");
  const kept = await call(safetyPlanSync, owner, {ownerUid: owner, resolution: {
    key: "primary_destination", revision: 4, mutationId: "staff-older", useIncoming: false,
  }});
  assert.equal(kept.plan.fields.primary_destination.value, "Staff proposal");
  assert.deepEqual(kept.plan.pendingConflicts, {});
  const retried = await update(staff, "Another proposal", 2, "staff-older");
  assert.deepEqual(retried.plan.pendingConflicts, {});

  const file = db.collection("files").doc(fileId);
  await file.set({
    owner_id: owner, object_key: `users/${owner}/media/${fileId}`, encrypted_metadata: "metadata",
    allowed_users: [owner], wrapped_keys: {[owner]: "owner-key"}, ciphertext_size: 1024,
    ciphertext_sha256: "0".repeat(64), encryption_version: 2, created_at: new Date(),
  });
  await assert.rejects(call(mediaUpdateAccess, owner, {mediaId: fileId, forPlan: true,
    wrappedKeys: {[owner]: "owner-key", "plan-other": "outsider-key"}}));
  // Independent grants can arrive concurrently from the owner's Android and web clients.
  await Promise.all([contact, staff].map(uid => call(mediaUpdateAccess, owner, {mediaId: fileId, forPlan: true,
    wrappedKeys: {[owner]: "owner-key", [uid]: "reader-key"}, deviceWrappedKeys: {
      [`${uid}--test-device-id-1234`]: "device-key",
    }})));
  for (const {client} of listeners) {
    assert.equal((await getDocFromServer(doc(client, "files", fileId))).exists(), true);
  }
  const before = (await ticket.get()).data();
  await assert.rejects(call(staffSupportClose, owner, {conversationId: ticket.id, planCompleted: true}));
  await call(staffSupportClose, staff, {conversationId: ticket.id, planCompleted: true});
  await call(staffSupportClose, staff, {conversationId: ticket.id, planCompleted: true});
  const after = (await ticket.get()).data();
  assert.equal(after.ticketStatus, "COMPLETED");
  assert.deepEqual(fcmTargetsForConversationUpdate(before, after, ticket.id).recipients, [owner]);
  await assert.rejects(call(mediaAccountAccessKey, staff, {mediaId: fileId}), {code: "permission-denied"});
  await reconcilePlanMediaGrants(owner);
  assert.deepEqual(new Set((await file.get()).get("allowed_users")), new Set([owner, contact]));
  await waitFor(() => listeners[2].seen.has("permission-denied"));
  const completed = (await call(safetyPlanSync, owner, {ownerUid: owner})).plan;
  assert.equal(completed.staffUid, "");
  assert.ok(completed.plansCompletedAt > 0);
  assert.equal((await call(safetyPlanConsultations, staff)).plans.some(row => row.ownerUid === owner), false);
});

test("a failed repeat group share preserves earlier grants and publishes no partial share", async () => {
  const owner = "repeat-share-owner", alice = "repeat-share-alice", bob = "repeat-share-bob", carol = "repeat-share-carol";
  const mediaId = "repeat-share-file", conversationId = "repeat-share-group";
  const file = db.collection("files").doc(mediaId);
  const chat = db.collection("conversations").doc(conversationId);
  const grants = db.collection("safetyPlanMediaGrants").doc(owner).collection("files").doc(mediaId);
  const people = [owner, alice, bob, carol];
  await chat.set({type: "GROUP", participantIds: people.slice(0, 2),
    participantUsernames: Object.fromEntries(people.map(uid => [uid, uid]))});
  await file.set({
    owner_id: owner, object_key: `users/${owner}/media/${mediaId}`, encrypted_metadata: "metadata",
    allowed_users: [owner], wrapped_keys: {[owner]: "owner-key"}, device_wrapped_keys: {},
  });
  const first = {mediaId, conversationId, messageId: "first-share", ciphertext: "first-ciphertext", clientSentAt: 1,
    recipientUids: [alice], wrappedKeys: {[owner]: "owner-key", [alice]: "alice-key"},
    deviceWrappedKeys: {[`${alice}--phone`]: "alice-device-key"}};
  await call(mediaShareInChat, owner, first);
  await chat.update({participantIds: people});
  const repeat = {...first, messageId: "repeat-share", ciphertext: "repeat-ciphertext", clientSentAt: 2,
    recipientUids: [alice, bob, carol], wrappedKeys: {[owner]: "owner-key", [alice]: "new-alice-key", [bob]: "bob-key"}};
  const beforeFile = (await file.get()).data();
  const beforeGrants = (await grants.get()).data();

  // Carol's key setup failed after Alice and Bob were prepared.
  await assert.rejects(call(mediaShareInChat, owner, repeat), {code: "invalid-argument"});
  assert.deepEqual((await file.get()).data(), beforeFile);
  assert.deepEqual((await grants.get()).data(), beforeGrants);
  assert.equal((await chat.collection("messages").doc(repeat.messageId).get()).exists, false);
  assert.equal((await chat.get()).get("lastMessageId"), first.messageId);

  const complete = {...repeat, wrappedKeys: {...repeat.wrappedKeys, [carol]: "carol-key"}};
  await Promise.all([call(mediaShareInChat, owner, complete), call(mediaShareInChat, owner, complete)]);
  assert.deepEqual(new Set((await file.get()).get("allowed_users")), new Set(people));
  assert.deepEqual(new Set((await grants.get()).get("independentReaders")), new Set([alice, bob, carol]));
  assert.equal((await chat.collection("messages").get()).size, 2);
});

test("removing a plan attachment preserves an overlapping independent chat share", async () => {
  const owner = "media-overlap-owner", contact = "media-overlap-contact", staff = "media-overlap-staff";
  const fileId = "media-plan-chat-overlap";
  const plan = db.collection("safetyPlans").doc(owner);
  const checklist = items => JSON.stringify({oliveSafetyChecklist: 1, items});
  await plan.set({
    safeContactUids: [contact], consultationRequested: true, staffUid: staff,
    fields: {primary_documents: {value: checklist([{id: "doc", attachmentIds: [fileId]}])}},
  });
  const file = db.collection("files").doc(fileId);
  await file.set({
    owner_id: owner, object_key: `users/${owner}/media/${fileId}`, encrypted_metadata: "metadata",
    allowed_users: [owner], wrapped_keys: {[owner]: "owner-key"}, device_wrapped_keys: {},
    ciphertext_size: 1024, ciphertext_sha256: "0".repeat(64), encryption_version: 2,
  });
  const accountKey = Buffer.alloc(32, 9).toString("base64");
  await db.collection("mediaAccessKeys").doc(fileId).set({owner_id: owner, key: accountKey});

  await call(mediaUpdateAccess, owner, {mediaId: fileId, forPlan: true, wrappedKeys: {
    [owner]: "owner-key", [contact]: "contact-plan-key", [staff]: "staff-plan-key",
  }});
  await call(mediaUpdateAccess, owner, {mediaId: fileId, grantIndependentRecipients: [contact], wrappedKeys: {
    [owner]: "owner-key", [contact]: "contact-chat-key", [staff]: "staff-plan-key",
  }});

  await plan.update("fields.primary_documents.value", checklist([]));
  await reconcilePlanMediaGrants(owner);

  assert.deepEqual(new Set((await file.get()).get("allowed_users")), new Set([owner, contact]));
  assert.equal((await call(mediaAccountAccessKey, contact, {mediaId: fileId})).accountAccessKey, accountKey);
  await assert.rejects(call(mediaAccountAccessKey, staff, {mediaId: fileId}), {code: "permission-denied"});
});
after(async () => { auth.getUser=originalGetUser;auth.listUsers=originalListUsers;bucket.file=originalFile;await env.cleanup();await db.terminate(); });
const call=(endpoint,uid,data={}) => endpoint.run({auth:{uid},data});

test("chat membership changes record one server-authored event and revoke history access after removal", async () => {
  const owner = "plan-owner", friend = "plan-other", contact = "plan-contact";
  const id = "membership-history-test";
  const reference = db.collection("conversations").doc(id);
  await reference.set({
    friendshipId: id,
    participantIds: [owner, friend],
    participantUsernames: {[owner]: "owner", [friend]: "other"},
  });
  await db.collection("friendLists").doc(owner).collection("friends").doc("membership-contact")
    .set({friendUid: contact, friendUsername: "contact"});
  const args = {conversationId: id, participantUid: contact};
  const addedClient = env.authenticatedContext(contact, {email: "contact@safe.olive.invalid"}).firestore();
  const ownerClient = env.authenticatedContext(owner, {email: "owner@users.olive.invalid"}).firestore();
  const eventsFor = (client) => collection(client, "conversations", id, "membershipEvents");
  await assertFails(getDocsFromServer(eventsFor(addedClient)));
  await Promise.all([
    call(conversationAddParticipant, owner, args),
    call(conversationAddParticipant, owner, args),
  ]);
  const added = await getDocsFromServer(eventsFor(addedClient));
  assert.equal(added.size, 1);
  assert.deepEqual(added.docs.map(event => [event.get("action"), event.get("actorName"), event.get("participantName")]),
      [["ADDED", "owner", "contact"]]);
  await assertFails(setDoc(doc(eventsFor(ownerClient), "forged"), {action: "REMOVED"}));
  await assert.rejects(call(conversationRemoveParticipant, friend, args), {code: "permission-denied"});
  await call(conversationRemoveParticipant, owner, args);
  await assertFails(getDocsFromServer(eventsFor(addedClient)));
  const remaining = await getDocsFromServer(eventsFor(ownerClient));
  assert.deepEqual(remaining.docs.map(event => event.get("action")).sort(), ["ADDED", "REMOVED"]);
  assert.equal(remaining.docs.find(event => event.get("action") === "REMOVED").get("participantName"), "contact");
  assert.deepEqual((await reference.get()).get("participantIds"), [owner, friend]);
});

for (const type of ["GROUP", "SUPPORT"]) {
  test(`removed ${type} members lose the conversation and regain history when re-added`, async () => {
    const owner = "plan-owner", removed = "plan-contact", retained = "plan-other";
    const id = type === "SUPPORT" ? "support-plan-owner-rejoin-test" : "group-original-friend-rejoin";
    const chat = db.collection("conversations").doc(id);
    const others = type === "SUPPORT" ? [owner, "plan-staff"] : [owner, retained];
    await db.collection("friendLists").doc(owner).collection("friends").doc("rejoin-contact")
      .set({friendUid: removed, friendUsername: "contact"});
    if (type === "GROUP") {
      // The original DM participants still share a friendship after the chat becomes a group.
      await db.collection("friendships").doc(id).set({participantIds: [owner, removed]});
    }
    await chat.set({
      type, ownerUid: owner, staffUid: type === "SUPPORT" ? "plan-staff" : "",
      participantIds: others, participantUsernames: Object.fromEntries(others.map(uid => [uid, uid])),
    });
    const args = {conversationId: id, participantUid: removed};
    await call(conversationAddParticipant, owner, args);
    await chat.update({
      roomKeys: {[owner]: "owner-envelope", [removed]: "removed-envelope"},
      participantPublicKeys: {[owner]: "owner-public", [removed]: "removed-public"},
    });
    await chat.collection("messages").doc("history").set({ciphertext: "existing-ciphertext"});
    const deviceId = `${removed}--phone`;
    await chat.collection("deviceKeys").doc(deviceId).set({uid: removed, wrappedKey: "removed-device-envelope"});
    await chat.collection("deviceKeys").doc(`${owner}--phone`).set({uid: owner, wrappedKey: "owner-device-envelope"});
    const client = env.authenticatedContext(removed).firestore();
    const list = query(collection(client, "conversations"), where("participantIds", "array-contains", removed));
    const chatDoc = doc(client, "conversations", id);
    const messageDoc = doc(client, "conversations", id, "messages", "history");
    const deviceDoc = doc(client, "conversations", id, "deviceKeys", deviceId);
    const visible = async () => (await getDocsFromServer(list)).docs.some(row => row.id === id);
    assert.equal(await visible(), true);
    assert.equal((await getDocFromServer(messageDoc)).exists(), true);

    await call(conversationRemoveParticipant, owner, args);
    assert.equal(await visible(), false);
    await assertFails(getDocFromServer(chatDoc));
    await assertFails(getDocFromServer(messageDoc));
    await assertFails(getDocFromServer(deviceDoc));
    const remaining = await chat.get();
    assert.deepEqual(remaining.get("roomKeys"), {[owner]: "owner-envelope"});
    assert.deepEqual(remaining.get("participantPublicKeys"), {[owner]: "owner-public"});
    assert.equal((await chat.collection("deviceKeys").doc(deviceId).get()).exists, false);
    assert.equal((await chat.collection("deviceKeys").doc(`${owner}--phone`).get()).exists, true);

    await call(conversationAddParticipant, owner, args);
    assert.equal(await visible(), true);
    assert.equal((await getDocFromServer(chatDoc)).exists(), true);
    assert.equal((await getDocFromServer(messageDoc)).get("ciphertext"), "existing-ciphertext");
  });
}

test("ticket owners can remove friends they added, while staff, admins, and original members stay", async () => {
  const owner = "plan-owner", friend = "plan-contact", original = "plan-other";
  const staff = "plan-staff", admin = "plan-admin";
  const id = "support-plan-owner-removal-test";
  const ticket = db.collection("conversations").doc(id);
  const originalMembers = [owner, original, staff, admin];
  await db.collection("friendLists").doc(owner).collection("friends").doc("membership-contact")
    .set({friendUid: friend, friendUsername: "contact"});
  await ticket.set({
    type: "SUPPORT", ownerUid: owner, staffUid: staff, adminUid: admin,
    sourceConversationId: id,
    participantIds: originalMembers,
    participantUsernames: Object.fromEntries(originalMembers.map(uid => [uid, uid])),
  });
  const args = participantUid => ({conversationId: id, participantUid});
  for (const protectedUid of [staff, admin, original]) {
    await assert.rejects(call(conversationRemoveParticipant, owner, args(protectedUid)), {code: "permission-denied"});
  }
  await call(conversationAddParticipant, owner, args(friend));
  await assert.rejects(call(conversationRemoveParticipant, staff, args(friend)), {code: "permission-denied"});
  await assert.rejects(call(conversationRemoveParticipant, admin, args(friend)), {code: "permission-denied"});
  const ownerClient = env.authenticatedContext(owner, {email: "owner@users.olive.invalid"}).firestore();
  const friendClient = env.authenticatedContext(friend, {email: "contact@safe.olive.invalid"}).firestore();
  const clientEvents = client => collection(client, "conversations", id, "membershipEvents");
  assert.equal((await getDocsFromServer(clientEvents(friendClient))).size, 1);
  await ticket.update({roomKeys: {[owner]: "owner-envelope", [friend]: "friend-envelope"},
    participantPublicKeys: {[owner]: "owner-key", [friend]: "friend-key"}});
  await Promise.all([
    call(conversationRemoveParticipant, owner, args(friend)),
    call(safetySupportAdmin, staff, {conversationId: id}),
  ]);
  const remaining = await ticket.get();
  assert.deepEqual(remaining.get("participantIds"), originalMembers);
  assert.deepEqual(remaining.get("roomKeys"), {[owner]: "owner-envelope"});
  assert.deepEqual(remaining.get("participantPublicKeys"), {[owner]: "owner-key"});
  await assertFails(getDocFromServer(doc(friendClient, "conversations", id)));
  await assertFails(getDocsFromServer(clientEvents(friendClient)));
  assert.deepEqual((await getDocsFromServer(clientEvents(ownerClient))).docs.map(event => event.get("action")).sort(),
      ["ADDED", "REMOVED"]);
});

test("only competing checklist properties create owner proposals and later independent edits survive resolution", async () => {
  const owner = "plan-checklist-owner", key = "primary_documents";
  const reference = db.collection("safetyPlans").doc(owner);
  const rows = [{id:"a",text:"Original",checked:false},{id:"b",text:"Second",checked:false}];
  const encode = items => JSON.stringify({oliveSafetyChecklist:1,items});
  const base = encode(rows);
  await reference.set({ownerUid:owner,staffUid:"plan-staff",consultationRequested:true,
    safeContactUids:[],fields:{[key]:{value:base,revision:1,mutationId:"initial"}}});
  const edit = (uid,value,revision,mutationId,baseValue) =>
    call(safetyPlanSync,uid,{ownerUid:owner,changes:[{key,value,revision,mutationId,baseValue}]});
  await edit(owner,encode([{...rows[0],text:"Owner"},rows[1]]),1,"owner-change",base);
  const independent=await edit("plan-staff",encode([rows[0],{...rows[1],checked:true}]),1,"staff-other",base);
  assert.deepEqual(independent.conflicts,[]);
  assert.deepEqual(independent.plan.pendingConflicts,{});
  const current=independent.plan.fields[key];
  const conflict=await edit("plan-staff",encode([{...rows[0],text:"Staff"},rows[1]]),1,"staff-conflict",base);
  assert.deepEqual(conflict.conflicts,[key]);
  const laterRows=JSON.parse(current.value).items.map(item=>item.id==="b"?{...item,text:"Later independent edit"}:item);
  const later=await edit(owner,encode(laterRows),current.revision,"owner-later",current.value);
  const resolved=await call(safetyPlanSync,owner,{ownerUid:owner,resolution:{
    key,revision:later.plan.fields[key].revision,mutationId:"staff-conflict",useIncoming:true,
  }});
  const finalRows=JSON.parse(resolved.plan.fields[key].value).items;
  assert.equal(finalRows[0].text,"Staff");
  assert.equal(finalRows[1].text,"Later independent edit");
  assert.equal(finalRows[1].checked,true);
  await reference.delete();
});

test("a legacy support ticket with mixed-case usernames can initialize encryption after repair", async () => {
  const id = "support-plan-owner-key-test";
  const reference = db.collection("conversations").doc(id);
  await reference.set({
    type: "SUPPORT", ownerUid: "plan-owner", staffUid: "plan-staff", adminUid: "",
    supportOrigin: "SUPPORT", supportTag: "SUPPORT", ticketNumber: 42,
    friendshipId: id, participantIds: ["plan-owner", "plan-staff"],
    participantUsernames: {"plan-owner": "PLAN_OWNER", "plan-staff": "Plan_Staff"},
    lastMessage: "", lastMessageId: "", lastSenderUid: "",
    createdAt: new Date(), updatedAt: new Date(),
  });
  await db.collection("messageDirectory").doc("plan_owner").set({
    uid: "plan-owner", username: "plan_owner", publicKey: "owner-key",
  });
  await db.collection("messageDirectory").doc("plan_staff").set({
    uid: "plan-staff", username: "plan_staff", publicKey: "staff-key",
  });
  const owner = env.authenticatedContext("plan-owner", {role: "USER"}).firestore();
  const keys = {
    roomKeys: {"plan-owner": "wrapped-owner", "plan-staff": "wrapped-staff"},
    participantPublicKeys: {"plan-owner": "owner-key", "plan-staff": "staff-key"},
    updatedAt: serverTimestamp(),
  };
  await assertFails(updateDoc(doc(owner, "conversations", id), keys));
  await assert.rejects(call(conversationPrepareKeys, "plan-other", {conversationId: id}));
  const prepared = await call(conversationPrepareKeys, "plan-owner", {conversationId: id});
  assert.deepEqual(prepared.participantUsernames, {"plan-owner": "plan_owner", "plan-staff": "plan_staff"});
  await updateDoc(doc(owner, "conversations", id), keys);
  await call(conversationPrepareKeys, "plan-staff", {conversationId: id});
  assert.deepEqual((await reference.get()).get("roomKeys"), keys.roomKeys);
});

test("legacy safety plan and its existing chat share one durable ticket number", async () => {
  const owner = "plan-legacy-owner";
  const id = "support-" + owner;
  const plan = db.collection("safetyPlans").doc(owner);
  const chat = db.collection("conversations").doc(id);
  await plan.set({ownerUid: owner, ownerName: "Legacy", fields: {}, consultationRequested: true,
    staffUid: "plan-staff", staffName: "", adminUid: "", safeContactUids: []});
  await chat.set({type: "SUPPORT", ownerUid: owner, staffUid: "plan-staff", adminUid: "",
    friendshipId: id, participantIds: [owner, "plan-staff"],
    participantUsernames: {[owner]: "legacy_owner", "plan-staff": "plan_staff"},
    lastMessage: "", lastMessageId: "", lastSenderUid: ""});
  const messages = chat.collection("messages").doc("existing");
  await messages.set({ciphertext: "historical-ciphertext"});
  await Promise.all([repairPlanTicket(owner), repairPlanTicket(owner)]);
  const savedPlan = (await plan.get()).data();
  const savedChat = (await chat.get()).data();
  assert.equal(savedPlan.consultationConversationId, id);
  assert.ok(Number.isSafeInteger(savedPlan.ticketNumber));
  assert.equal(savedPlan.ticketNumber, savedChat.ticketNumber);
  assert.equal(savedChat.supportOrigin, "PLAN_CONSULT");
  assert.equal(savedChat.supportTag, "PLAN_CONSULT");
  const assigned = await call(safetyPlanConsultations, "plan-staff");
  const row = assigned.plans.find(item => item.ownerUid === owner);
  assert.equal(row.conversationId, id);
  assert.equal(row.ticketNumber, savedPlan.ticketNumber);
  await call(supportTicketsPrepare, "plan-staff");
  assert.equal((await chat.get()).get("ticketNumber"), savedPlan.ticketNumber);
  assert.equal((await messages.get()).get("ciphertext"), "historical-ciphertext");
  await call(safetyPlanConsult, owner, {action: "stop"});
});
test("full consultation workflow enforces owner consent, assigned-staff edits, conflicts and revocation", async () => {
  const owner="plan-owner";
  let response=await call(safetyPlanSync,owner,{ownerUid:owner,defaults:{primary_destination:"Initial",backup_destination:"Backup"},changes:[]});
  assert.equal(response.plan.fields.primary_destination.value,"Initial");
  await assert.rejects(call(safetyPlanSync,"plan-staff",{ownerUid:owner}));
  await assert.rejects(call(safetyPlanSync,"plan-other",{ownerUid:owner}));
  await assert.rejects(call(safetyPlanAvailability,"plan-forged",{available:true}));
  response=await call(safetyPlanConsult,owner,{action:"request"});
  let staff=response.plan.staffUid;
  assert.ok(["plan-staff","plan-staff2"].includes(staff));
  const consultationId=response.plan.consultationConversationId;
  assert.match(consultationId,new RegExp(`^plan-consult-${owner}-`));
  const originalRoomKeys={[owner]:"owner-wrap",[staff]:"staff-wrap"};
  await db.collection("conversations").doc(consultationId).update({
    roomKeys:originalRoomKeys,participantPublicKeys:{[owner]:"owner-key",[staff]:"staff-key"},
  });
  const escalation=await call(safetySupportAdmin,staff,{conversationId:consultationId});
  assert.equal(escalation.adminUid,"plan-admin");
  await call(safetySupportAdmin,staff,{conversationId:consultationId});
  const otherStaff=staff==="plan-staff" ? "plan-staff2" : "plan-staff";
  await assert.rejects(call(safetySupportAdmin,otherStaff,{conversationId:consultationId}));
  const supportConversation=await db.collection("conversations").doc(consultationId).get();
  const events = await supportConversation.ref.collection("membershipEvents").get();
  assert.deepEqual(events.docs.map(event => event.get("action")), ["ADMIN_HELP_REQUESTED"]);
  assert.deepEqual(new Set(supportConversation.data().participantIds),new Set([owner,staff,"plan-admin"]));
  assert.deepEqual(supportConversation.data().roomKeys,originalRoomKeys);
  assert.equal(supportConversation.data().supportOrigin,"PLAN_CONSULT");
  assert.equal(supportConversation.data().supportTag,"NEEDS_ADMIN");
  await assert.rejects(call(staffSupportClose,owner,{conversationId:consultationId}));
  await assert.rejects(call(staffSupportClose,otherStaff,{conversationId:consultationId}));
  // The assigned admin can close an escalated ticket for every participant.
  await call(staffSupportClose,"plan-admin",{conversationId:consultationId});
  const completedConsultation = await db.collection("conversations").doc(consultationId).get();
  assert.ok(completedConsultation.get("closedAt"));
  assert.ok(completedConsultation.get("completedAt"));
  assert.equal(completedConsultation.get("ticketStatus"), "COMPLETED");
  response=await call(safetyPlanConsult,owner,{action:"request"});
  const secondConsultationId=response.plan.consultationConversationId;
  staff=response.plan.staffUid;
  assert.notEqual(secondConsultationId,consultationId);
  assert.ok((await db.collection("conversations").doc(consultationId).get()).get("closedAt"));
  assert.equal((await db.collection("conversations").doc(secondConsultationId).get()).get("closedAt"),undefined);
  assert.equal((await call(safetyPlanConsultations,staff)).plans[0].ownerUid,owner);
  await call(safetyPlanSync,staff,{ownerUid:owner,changes:[{key:"primary_destination",value:"Staff edit",revision:1,mutationId:"staff-1"}]});
  response=await call(safetyPlanSync,owner,{ownerUid:owner,changes:[{key:"primary_destination",value:"Offline owner edit",revision:1,mutationId:"owner-1"}]});
  assert.deepEqual(response.conflicts,["primary_destination"]);
  assert.equal(response.plan.fields.primary_destination.value,"Staff edit");
  response=await call(safetyPlanSync,owner,{ownerUid:owner,changes:[{key:"primary_destination",value:"Resolved owner edit",revision:2,mutationId:"owner-2"}]});
  assert.equal(response.plan.fields.primary_destination.value,"Resolved owner edit");
  response=await call(safetyPlanSync,"plan-admin",{ownerUid:owner,changes:[{key:"primary_destination",value:"Admin edit",revision:3,mutationId:"admin-1"}]});
  assert.equal(response.plan.fields.primary_destination.value,"Admin edit");
  assert.ok((await call(safetyPlanConsultations,"plan-admin")).plans.some((plan) => plan.ownerUid===owner));
  await call(safetyPlanConsult,owner,{action:"stop"});
  assert.ok((await db.collection("conversations").doc(secondConsultationId).get()).get("closedAt"));
  await assert.rejects(call(safetyPlanSync,staff,{ownerUid:owner}));
  assert.equal((await call(safetyPlanConsultations,staff)).plans.length,0);
  // All content uses verified callables: clients cannot bypass sharing or revisions with raw Firestore writes.
  const client=env.authenticatedContext(owner).firestore();
  await assertFails(getDoc(doc(client,"safetyPlans",owner)));
  await assertFails(setDoc(doc(client,"safetyPlans",owner),{staffUid:staff}));
});
test("no available staff leaves a durable waiting request and repeated requests do not double-assign", async () => {
  await call(safetyPlanAvailability,"plan-staff",{available:false});
  await call(safetyPlanAvailability,"plan-staff2",{available:false});
  let response=await call(safetyPlanConsult,"plan-owner",{action:"request"});
  assert.equal(response.plan.staffUid,"");assert.equal(response.plan.consultationRequested,true);
  const pendingId = response.plan.consultationConversationId;
  const pendingNumber = response.plan.ticketNumber;
  const waiting = await db.collection("conversations").doc(pendingId).get();
  assert.equal(waiting.exists, true);
  assert.equal(waiting.get("supportOrigin"), "PLAN_CONSULT");
  assert.equal(waiting.get("ticketNumber"), pendingNumber);
  assert.deepEqual(waiting.get("participantIds"), ["plan-owner"]);
  const client = env.authenticatedContext("plan-owner", {role: "USER"}).firestore();
  assert.equal((await getDocFromServer(doc(client, "conversations", pendingId))).exists(), true);
  await call(safetyPlanAvailability,"plan-staff2",{available:true});
  response=await call(safetyPlanConsult,"plan-owner",{action:"request"});
  assert.equal(response.plan.staffUid,"plan-staff2");
  assert.equal(response.plan.consultationConversationId, pendingId);
  assert.equal(response.plan.ticketNumber, pendingNumber);
  assert.equal((await db.collection("conversations").doc(pendingId).get()).get("staffUid"), "plan-staff2");
  await Promise.all([call(safetyPlanConsult,"plan-owner",{action:"request"}),call(safetyPlanConsult,"plan-owner",{action:"request"})]);
  assert.equal((await db.collection("safetyStaff").doc("plan-staff2").get()).data().activeCount,1);
  await call(safetyPlanConsult,"plan-owner",{action:"stop"});
});
test("support tickets create an anonymous chat without sharing the safety plan", async () => {
  const owner="support-owner";
  const response=await call(staffSupportRequest,owner,{action:"request"});
  assert.equal(response.ticket.requested,true);
  assert.ok(response.ticket.staffUid);
  assert.equal(Number.isSafeInteger(response.ticket.ticketNumber),true);
  assert.equal(response.ticket.ticketNumber >= 0,true);
  assert.equal((await db.collection("safetyPlans").doc(owner).get()).exists,false);
  await assert.rejects(call(safetyPlanSync,response.ticket.staffUid,{ownerUid:owner,changes:[]}));
  const firstConversationId=response.ticket.conversationId;
  assert.match(firstConversationId,new RegExp(`^support-${owner}-`));
  const supportConversation=await db.collection("conversations").doc(firstConversationId).get();
  assert.equal(supportConversation.exists,true);
  assert.equal(supportConversation.data().supportOrigin,"SUPPORT");
  assert.equal(supportConversation.data().supportTag,"SUPPORT");
  await call(staffSupportClose,response.ticket.staffUid,{conversationId:firstConversationId});
  assert.ok((await db.collection("conversations").doc(firstConversationId).get()).get("closedAt"));
  const ownerClient = env.authenticatedContext(owner, {email: "support_owner@users.olive.invalid"}).firestore();
  const archived = doc(ownerClient, "conversations", firstConversationId);
  assert.equal((await getDocFromServer(archived)).get("ticketStatus"), "COMPLETED");
  await assertFails(setDoc(doc(archived, "messages", "after-close"), {
    senderUid: owner, senderUsername: supportConversation.get("participantUsernames")[owner],
    ciphertext: "encrypted", deleted: false, sentAt: serverTimestamp(), clientSentAt: Date.now(),
  }));
  const secondResponse=await call(staffSupportRequest,owner,{action:"request"});
  const secondConversationId=secondResponse.ticket.conversationId;
  assert.equal(secondResponse.ticket.ticketNumber,response.ticket.ticketNumber+1);
  assert.notEqual(secondConversationId,firstConversationId);
  assert.ok((await db.collection("conversations").doc(firstConversationId).get()).get("closedAt"));
  assert.equal((await db.collection("conversations").doc(secondConversationId).get()).get("closedAt"),undefined);
  assert.equal((await call(safetyPlanConsultations,response.ticket.staffUid)).plans.some((plan) => plan.ownerUid===owner),false);
  const staffRef = db.collection("safetyStaff").doc(secondResponse.ticket.staffUid);
  const activeCount = (await staffRef.get()).get("activeCount");
  await Promise.all([
    call(staffSupportRequest,owner,{action:"stop",conversationId:secondConversationId}),
    call(staffSupportClose,secondResponse.ticket.staffUid,{conversationId:secondConversationId}),
  ]);
  assert.equal((await staffRef.get()).get("activeCount"),activeCount - 1);
  assert.ok((await db.collection("conversations").doc(secondConversationId).get()).get("closedAt"));
});
test("owners can share a read-only plan with an accepted safe contact and revoke it", async () => {
  const owner="plan-share-owner",contact="plan-contact",friendship="owner-contact-friendship";
  await call(safetyPlanSync,owner,{ownerUid:owner,defaults:{primary_destination:"Safe place"},changes:[]});
  await db.collection("friendLists").doc(owner).collection("friends").doc(friendship).set({friendUid:contact,friendUsername:"safe_contact"});
  await call(safetyPlanShareContacts,owner,{contacts:[{uid:contact,friendshipId:friendship,name:"Safe contact"}]});
  assert.equal((await call(safetyPlansSharedWithMe,contact)).plans[0].ownerUid,owner);
  const shared=await call(safetyPlanSync,contact,{ownerUid:owner,changes:[]});
  assert.equal(shared.plan.fields.primary_destination.value,"Safe place");
  await assert.rejects(call(safetyPlanSync,contact,{ownerUid:owner,changes:[{
    key:"primary_destination",value:"Unauthorized edit",revision:0,mutationId:"bad-edit"}]}));
  await call(safetyPlanShareContacts,owner,{contacts:[]});
  await assert.rejects(call(safetyPlanSync,contact,{ownerUid:owner,changes:[]}));
  assert.equal((await call(safetyPlansSharedWithMe,contact)).plans.length,0);
});

test("encrypted chat media sharing is atomic, authorized and idempotent", async () => {
  const owner="plan-owner", recipient="plan-contact", mediaId="media-chat-share";
  const conversationId="conversation-chat-share", messageId="message-chat-share";
  await db.collection("files").doc(mediaId).set({
    owner_id:owner,
    object_key:`users/${owner}/media/${mediaId}`,
    encrypted_metadata:"encrypted-metadata",
    wrapped_keys:{[owner]:"owner-wrapped-key"},
    device_wrapped_keys:{[`${owner}--phone`]:"owner-device-key"},
    allowed_users:[owner],
  });
  await db.collection("conversations").doc(conversationId).set({
    participantIds:[owner,recipient],
    participantUsernames:{[owner]:"owner",[recipient]:"safe-contact"},
  });
  const request={
    mediaId,conversationId,messageId,ciphertext:"encrypted-message",clientSentAt:1234,
    recipientUids:[recipient],
    wrappedKeys:{[owner]:"owner-wrapped-key",[recipient]:"recipient-wrapped-key"},
    deviceWrappedKeys:{[`${recipient}--phone`]:"recipient-device-key"},
  };

  await call(mediaShareInChat,owner,request);
  await call(mediaShareInChat,owner,request);

  const sharedFile=await db.collection("files").doc(mediaId).get();
  assert.deepEqual(new Set(sharedFile.get("allowed_users")),new Set([owner,recipient]));
  assert.equal(sharedFile.get("wrapped_keys")[recipient],"recipient-wrapped-key");
  assert.equal(sharedFile.get("device_wrapped_keys")[`${recipient}--phone`],"recipient-device-key");
  const message=await db.collection("conversations").doc(conversationId)
      .collection("messages").doc(messageId).get();
  assert.equal(message.get("ciphertext"),"encrypted-message");

  const rejectedMessageId="message-rejected-share";
  await assert.rejects(call(mediaShareInChat,"plan-other",{
    ...request,messageId:rejectedMessageId,ciphertext:"other-encrypted-message",
  }));
  assert.equal((await db.collection("conversations").doc(conversationId)
      .collection("messages").doc(rejectedMessageId).get()).exists,false);

  const incompleteMessageId="message-incomplete-share";
  await assert.rejects(call(mediaShareInChat,owner,{
    ...request,messageId:incompleteMessageId,ciphertext:"incomplete-encrypted-message",
    wrappedKeys:{[owner]:"owner-wrapped-key"},deviceWrappedKeys:{},
  }));
  assert.equal((await db.collection("conversations").doc(conversationId)
      .collection("messages").doc(incompleteMessageId).get()).exists,false);
});
