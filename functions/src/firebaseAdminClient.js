// All cloud account roles: shares one Firebase Admin client across callable functions.
const {setGlobalOptions} = require("firebase-functions/v2");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getFirestore, FieldValue} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");
const {getDatabase} = require("firebase-admin/database");
const {getMessaging} = require("firebase-admin/messaging");

initializeApp();
setGlobalOptions({region: "northamerica-northeast1", maxInstances: 10});

module.exports = {
  auth: getAuth(),
  db: getFirestore(),
  bucket: getStorage().bucket(),
  realtime: () => getDatabase(),
  messaging: getMessaging(),
  FieldValue,
};
