// Uses the Firebase Admin SDK's supported Application Default Credentials flow.
const {applicationDefault, initializeApp} = require("firebase-admin/app");

function initializeOliveAdminApp(options, name) {
  return initializeApp({...options, credential: applicationDefault()}, name);
}

module.exports = {initializeOliveAdminApp};
