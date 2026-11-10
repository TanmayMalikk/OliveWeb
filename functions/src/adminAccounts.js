// Admins: lets admins list and manage organization accounts.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {text, normalizedUsername, normalizedRole, validPassword, signInEmail, isTemporaryEmail, profileComplete, defaultProfile} = require("./accountPolicy");
const {writeProfile, saveOrganizationLogin, requireAdmin, usernameExistsGlobally} = require("./accountStorage");

// Reads a Firestore aggregate so dashboard totals never download the matching documents.
async function count(query) {
  return (await query.count().get()).data().count;
}

// Loads the six small totals shown on the admin dashboard.
async function dashboardCounts() {
  const directory = db.collection("accountDirectory");
  const conversations = db.collection("conversations").where("type", "==", "SUPPORT");
  const [users, safeContacts, staff, admins, allTickets, closedTickets] = await Promise.all([
    count(directory.where("role", "==", "USER")),
    count(directory.where("role", "==", "SAFE_CONTACT")),
    count(directory.where("role", "==", "STAFF")),
    count(directory.where("role", "==", "ADMIN")),
    count(conversations),
    count(conversations.where("ticketStatus", "==", "COMPLETED")),
  ]);
  return {
    counts: {USER: users, SAFE_CONTACT: safeContacts, STAFF: staff, ADMIN: admins},
    tickets: {open: Math.max(0, allTickets - closedTickets), closed: closedTickets},
  };
}

// Builds staff workload totals only when the admin opens Staff management.
async function staffWorkloads(staffUids) {
  const workloads = new Map(staffUids.map(uid => [uid, {
    safetyPlans: 0, completedSafetyPlans: 0, supportChats: 0, closedSupportChats: 0,
  }]));
  const supportSnapshot = await db.collection("conversations").where("type", "==", "SUPPORT").get();
  supportSnapshot.docs.forEach(document => {
    const conversation = document.data();
    const staffUid = text(conversation.staffUid, 128);
    const workload = workloads.get(staffUid);
    if (!workload) return;
    const closed = Boolean(conversation.closedAt) || conversation.ticketStatus === "COMPLETED";
    const plan = conversation.supportOrigin === "PLAN_CONSULT" ||
      conversation.supportTag === "PLAN_CONSULT";
    if (plan) {
      workload.safetyPlans += 1;
      if (closed) workload.completedSafetyPlans += 1;
    } else {
      workload.supportChats += 1;
      if (closed) workload.closedSupportChats += 1;
    }
  });
  return workloads;
}

exports.adminListAccounts = onCall(async (request) => {
  await requireAdmin(request);
  const view = ["SUMMARY", "ACCOUNTS", "STAFF"].includes(request.data?.view) ?
    request.data.view : "ACCOUNTS";
  if (view === "SUMMARY") return {...await dashboardCounts(), accounts: []};

  const directory = db.collection("accountDirectory");
  const snapshot = view === "STAFF" ?
    await directory.where("role", "==", "STAFF").get() : await directory.get();
  const rows = snapshot.docs.map(document => document.data()).filter(value =>
    typeof value.uid === "string" && typeof value.username === "string" && typeof value.role === "string");
  const staffUids = view === "STAFF" ? rows.map(value => value.uid) : [];
  const workloads = view === "STAFF" ? await staffWorkloads(staffUids) : new Map();
  const settings = view === "STAFF" && staffUids.length ?
    await db.getAll(...staffUids.map(uid => db.collection("safetyStaff").doc(uid))) : [];
  const settingsByUid = new Map(settings.map(document => [document.id, document.data()]));
  const accounts = rows.map(value => {
    const role = value.role;
    const workload = workloads.get(value.uid) || {};
    return {
      uid: value.uid,
      username: text(value.username, 24).toLowerCase(),
      role,
      fullName: text(value.fullName, 120),
      email: text(value.contactEmail || value.loginAlias, 254),
      phone: text(value.phone, 40),
      profileComplete: value.profileComplete === true,
      protected: value.uid === request.auth.uid || value.protected === true,
      assignedUsers: role === "STAFF" ? (settingsByUid.get(value.uid)?.activeCount || 0) : 0,
      safetyPlans: role === "STAFF" ? (workload.safetyPlans || 0) : 0,
      completedSafetyPlans: role === "STAFF" ? (workload.completedSafetyPlans || 0) : 0,
      supportChats: role === "STAFF" ? (workload.supportChats || 0) : 0,
      closedSupportChats: role === "STAFF" ? (workload.closedSupportChats || 0) : 0,
    };
  });
  accounts.sort((a, b) => a.role.localeCompare(b.role) || a.username.localeCompare(b.username));
  return {accounts};
});

exports.adminCreateAccount = onCall(async (request) => {
  await requireAdmin(request);
  const input = request.data || {};
  const username = normalizedUsername(input.username);
  const role = normalizedRole(input.role);
  const password = String(input.password || "");
  if (!validPassword(password, username)) throw new HttpsError("invalid-argument", "Temporary password must use 8–24 characters with a letter, number, and special character and must not contain the username.");
  // An admin may choose an organization login alias only while creating the account.
  const requestedAlias = role === "STAFF" || role === "ADMIN" ? text(input.email, 254) : "";
  const authEmail = signInEmail(username, role, requestedAlias);
  const profileEmail = requestedAlias ? authEmail : "";
  const directory = db.collection("accountDirectory").doc(username);
  if (await usernameExistsGlobally(username)) throw new HttpsError("already-exists", "That username is already in use.");
  let created;
  try {
    // Managed aliases are approved by the creating admin, so support assignment can use the account immediately.
    created = await auth.createUser({
      email: authEmail,
      password,
      displayName: username,
      emailVerified: role === "STAFF" || role === "ADMIN" || isTemporaryEmail(authEmail),
    });
    await auth.setCustomUserClaims(created.uid, {role});
    const profile = defaultProfile(username, role, input, profileEmail);
    await writeProfile(created.uid, profile);
    await directory.create({
      uid: created.uid,
      username,
      role,
      fullName: text(profile.fullName, 120),
      contactEmail: text(profile.email, 254),
      phone: text(profile.phone, 40),
      profileComplete: profileComplete(role, profile),
      ...(role === "STAFF" || role === "ADMIN" ? {loginAlias: authEmail} : {}),
      createdAt: FieldValue.serverTimestamp(),
    });
    await saveOrganizationLogin(created.uid, username, role, profile.email, authEmail);
    return {uid: created.uid, username, role, email: authEmail, profileComplete: profileComplete(role, profile)};
  } catch (error) {
    const directoryOwner = await directory.get().catch(() => null);
    if (directoryOwner?.data()?.uid === created?.uid) await directory.delete().catch(() => {});
    if (created?.uid) await bucket.deleteFiles({prefix: `accounts/${created.uid}/`}).catch(() => {});
    if (created?.uid) await auth.deleteUser(created.uid).catch(() => {});
    if (error.code === "auth/email-already-exists" || error.code === 6 || error.code === "already-exists") {
      throw new HttpsError("already-exists", "That username is already in use.");
    }
    throw error;
  }
});
