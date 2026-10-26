import {before, after, test} from "node:test";
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {initializeTestEnvironment, assertFails} from "@firebase/rules-unit-testing";
import {doc, getDoc, setDoc} from "firebase/firestore";
import {readFile} from "node:fs/promises";
const require = createRequire(new URL("../functions/package.json", import.meta.url));
process.env.FIREBASE_CONFIG = JSON.stringify({projectId:"demo-olive-rules",storageBucket:"demo-olive-rules.appspot.com"});
process.env.FIRESTORE_EMULATOR_HOST = "127.0.0.1:8180";
const {auth, db, bucket} = require("./src/firebase");
const {safetyPlanSync, safetyPlanConsultations, safetyPlanAvailability, safetyPlanShareContacts,
  safetyPlansSharedWithMe} = require("./src/safetyPlans");
const {safetyPlanConsult} = require("./src/safetyConsultations");
const users = new Map([
  ["plan-owner",{uid:"plan-owner",email:"owner@users.olive.invalid",displayName:"Owner"}],
  ["plan-share-owner",{uid:"plan-share-owner",email:"share-owner@users.olive.invalid",displayName:"Sharing owner"}],
  ["plan-other",{uid:"plan-other",email:"other@users.olive.invalid"}],
  ["plan-contact",{uid:"plan-contact",email:"contact@safe.olive.invalid",displayName:"Safe contact"}],
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
after(async () => { auth.getUser=originalGetUser;auth.listUsers=originalListUsers;bucket.file=originalFile;await env.cleanup();await db.terminate(); });
const call=(endpoint,uid,data={}) => endpoint.run({auth:{uid},data});
test("full consultation workflow enforces owner consent, assigned-staff edits, conflicts and revocation", async () => {
  const owner="plan-owner";
  let response=await call(safetyPlanSync,owner,{ownerUid:owner,defaults:{primary_destination:"Initial",backup_destination:"Backup"},changes:[]});
  assert.equal(response.plan.fields.primary_destination.value,"Initial");
  await assert.rejects(call(safetyPlanSync,"plan-staff",{ownerUid:owner}));
  await assert.rejects(call(safetyPlanSync,"plan-other",{ownerUid:owner}));
  await assert.rejects(call(safetyPlanAvailability,"plan-forged",{available:true}));
  response=await call(safetyPlanConsult,owner,{action:"request"});
  const staff=response.plan.staffUid;
  assert.ok(["plan-staff","plan-staff2"].includes(staff));
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
  await call(safetyPlanAvailability,"plan-staff2",{available:true});
  response=await call(safetyPlanConsult,"plan-owner",{action:"request"});
  assert.equal(response.plan.staffUid,"plan-staff2");
  await Promise.all([call(safetyPlanConsult,"plan-owner",{action:"request"}),call(safetyPlanConsult,"plan-owner",{action:"request"})]);
  assert.equal((await db.collection("safetyStaff").doc("plan-staff2").get()).data().activeCount,1);
  await call(safetyPlanConsult,"plan-owner",{action:"stop"});
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
