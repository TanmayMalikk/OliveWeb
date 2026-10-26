// Deployment entry point: keep callable names stable while implementations stay feature-scoped.
require("./src/firebase");
Object.assign(exports, require("./src/adminAccounts"));
const publicAccounts = require("./src/publicAccounts");
exports.createPublicAccount = publicAccounts.createPublicAccount;
Object.assign(exports, require("./src/organizationAccounts"));
Object.assign(exports, require("./src/accountDeletion"));
Object.assign(exports, require("./src/safetyPlans"));
Object.assign(exports, require("./src/safetyConsultations"));
exports.safetySupportContact = require("./src/supportGroups").safetySupportContact;
Object.assign(exports, require("./src/mediaTickets"));

const {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, profileComplete, validPassword} = require("./src/accountPolicy");
exports._test = {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, profileComplete, validPassword};
