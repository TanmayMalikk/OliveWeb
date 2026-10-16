// Deployment entry point: keep callable names stable while implementations stay feature-scoped.
require("./src/firebase");
Object.assign(exports, require("./src/adminAccounts"));
Object.assign(exports, require("./src/organizationAccounts"));
Object.assign(exports, require("./src/accountDeletion"));

const {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, profileComplete, validPassword} = require("./src/accountPolicy");
exports._test = {normalizedUsername, normalizedExistingUsername, normalizedRole, signInEmail,
  organizationEmailKey, isTemporaryEmail, profileComplete, validPassword};
