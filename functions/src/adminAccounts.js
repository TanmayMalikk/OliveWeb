// Admins: lets admins list and manage organization accounts.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {auth, db, bucket, FieldValue} = require("./firebaseAdminClient");
const {text, normalizedUsername, normalizedRole, validPassword, signInEmail, roleFromUser, isTemporaryEmail, profileComplete, defaultProfile, protectedAccount} = require("./accountPolicy");
const {readProfile, writeProfile, saveOrganizationLogin, requireAdmin, listEveryUser, usernameExistsGlobally} = require("./accountStorage");

exports.adminListAccounts = onCall(async (request) => {
  await requireAdmin(request);
  const users = await listEveryUser();
  const profiles = new Map((await Promise.all(users.map(async user => [user.uid, await readProfile(user.uid)]))));
  const roles = new Map(users.map(user => [user.uid, roleFromUser(user, profiles.get(user.uid))]));
  const staffUids = new Set([...roles].filter(([, role]) => role === "STAFF").map(([uid]) => uid));
  const [supportSnapshot, safetyPlanSnapshot] = await Promise.all([
    db.collection("conversations").where("type", "==", "SUPPORT").get(),
    db.collection("safetyPlans").where("consultationRequested", "==", true).get(),
  ]);
  const workloadByStaff = new Map();
  const countedPlanConversations = new Set();
  let openTickets = 0;
  let closedTickets = 0;
  supportSnapshot.docs.forEach((document) => {
    const conversation = document.data();
    const closed = Boolean(conversation.closedAt);
    if (closed) closedTickets += 1;
    else openTickets += 1;
    const participantIds = Array.isArray(conversation.participantIds) ? conversation.participantIds : [];
    const staffUid = text(conversation.staffUid, 128) || participantIds.find(uid => staffUids.has(uid)) || "";
    if (!staffUid) return;
    const workload = workloadByStaff.get(staffUid) || {
      safetyPlans: 0, completedSafetyPlans: 0, supportChats: 0, closedSupportChats: 0,
    };
    const planConsult = conversation.supportOrigin === "PLAN_CONSULT" || conversation.supportTag === "PLAN_CONSULT";
    if (planConsult) {
      workload.safetyPlans += 1;
      if (closed) workload.completedSafetyPlans += 1;
      countedPlanConversations.add(document.id);
    } else {
      workload.supportChats += 1;
      if (closed) workload.closedSupportChats += 1;
    }
    workloadByStaff.set(staffUid, workload);
  });
  // Includes a newly assigned consultation even if its conversation write has not finished yet.
  safetyPlanSnapshot.docs.forEach(document => {
    const plan = document.data();
    const staffUid = text(plan.staffUid, 128);
    const conversationId = text(plan.consultationConversationId, 512);
    if (!staffUid || (conversationId && countedPlanConversations.has(conversationId))) return;
    const workload = workloadByStaff.get(staffUid) || {
      safetyPlans: 0, completedSafetyPlans: 0, supportChats: 0, closedSupportChats: 0,
    };
    workload.safetyPlans += 1;
    workloadByStaff.set(staffUid, workload);
  });
  const accounts = await Promise.all(users.map(async (user) => {
    const profile = profiles.get(user.uid);
    const role = roles.get(user.uid);
    const username = text(profile?.username || user.displayName || user.email?.split("@")[0], 24).toLowerCase();
    const staffSettings = role === "STAFF" ? (await db.collection("safetyStaff").doc(user.uid).get()).data() : null;
    const workload = workloadByStaff.get(user.uid) || {};
    return {
      uid: user.uid, username, role, fullName: text(profile?.fullName, 120), email: user.email || "",
      phone: text(profile?.phone || user.phoneNumber, 40), profileComplete: profileComplete(role, profile),
      protected: user.uid === request.auth.uid || protectedAccount(user, username),
      assignedUsers: role === "STAFF" ? (staffSettings?.activeCount || 0) : 0,
      safetyPlans: role === "STAFF" ? (workload.safetyPlans || 0) : 0,
      completedSafetyPlans: role === "STAFF" ? (workload.completedSafetyPlans || 0) : 0,
      supportChats: role === "STAFF" ? (workload.supportChats || 0) : 0,
      closedSupportChats: role === "STAFF" ? (workload.closedSupportChats || 0) : 0,
    };
  }));
  const counts = {USER: 0, SAFE_CONTACT: 0, STAFF: 0, ADMIN: 0};
  accounts.forEach((item) => { counts[item.role] += 1; });
  accounts.sort((a, b) => a.role.localeCompare(b.role) || a.username.localeCompare(b.username));
  return {counts, tickets: {open: openTickets, closed: closedTickets}, accounts};
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
