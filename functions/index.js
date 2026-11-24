// Deployment entry point: keep callable names stable while implementations stay feature-scoped.
require("./src/firebaseAdminClient");
const friendInvites = require("./src/friendInvites");
exports.friendInviteCurrent = friendInvites.friendInviteCurrent;
exports.friendInviteCreate = friendInvites.friendInviteCreate;
exports.friendInviteClaim = friendInvites.friendInviteClaim;
Object.assign(exports, require("./src/messagingPeerAccess"));
Object.assign(exports, require("./src/adminAccounts"));
const publicAccounts = require("./src/publicAccounts");
exports.createPublicAccount = publicAccounts.createPublicAccount;
Object.assign(exports, require("./src/organizationAccounts"));
Object.assign(exports, require("./src/accountDeletion"));
Object.assign(exports, require("./src/opaqueRecovery"));
Object.assign(exports, require("./src/safetyPlans"));
Object.assign(exports, require("./src/safetyConsultations"));
Object.assign(exports, require("./src/supportTickets"));
const supportGroups = require("./src/supportGroups");
exports.safetySupportContact = supportGroups.safetySupportContact;
exports.safetySupportAdmin = supportGroups.safetySupportAdmin;
exports.conversationAddParticipant = supportGroups.conversationAddParticipant;
exports.conversationRemoveParticipant = supportGroups.conversationRemoveParticipant;
exports.conversationRequestStaff = supportGroups.conversationRequestStaff;
exports.staffSupportClose = supportGroups.staffSupportClose;
const supportTicketRepair = require("./src/supportTicketRepair");
exports.conversationPrepareKeys = supportTicketRepair.conversationPrepareKeys;
exports.supportTicketsPrepare = supportTicketRepair.supportTicketsPrepare;
Object.assign(exports, require("./src/mediaTickets"));
const emergencies = require("./src/emergencyReports");
for (const [name, handler] of Object.entries(emergencies)) {
  if (name !== "canReadEmergencyMedia") exports[name] = handler;
}
Object.assign(exports, require("./src/nutritionFoods"));
const fitnessAi = require("./src/fitnessAi");
exports.fitnessAiBilling = fitnessAi.fitnessAiBilling;
exports.fitnessAiCoach = fitnessAi.fitnessAiCoach;
const syncInvalidations = require("./src/syncInvalidations");
Object.entries(syncInvalidations).filter(([name]) => name !== "_test")
    .forEach(([name, value]) => { exports[name] = value; });
const emailNotifications = require("./src/emailNotifications");
Object.entries(emailNotifications).filter(([name]) => name !== "_test")
    .forEach(([name, value]) => { exports[name] = value; });

const {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, validEmail, profileComplete, validPassword, text,
  protectedAccount, roleFromUser, defaultProfile} = require("./src/accountPolicy");
exports._test = {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, validEmail, profileComplete, validPassword, text,
  protectedAccount, roleFromUser, defaultProfile, syncInvalidations: syncInvalidations._test};
