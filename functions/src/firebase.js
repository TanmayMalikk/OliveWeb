// Initialize Firebase once, before callable modules create their clients.
const {setGlobalOptions} = require("firebase-functions/v2");
const {initializeApp} = require("firebase-admin/app");
const {getAuth} = require("firebase-admin/auth");
const {getFirestore, FieldValue} = require("firebase-admin/firestore");
const {getStorage} = require("firebase-admin/storage");

initializeApp();
setGlobalOptions({region: "northamerica-northeast1", maxInstances: 10});

module.exports = {auth: getAuth(), db: getFirestore(), bucket: getStorage().bucket(), FieldValue};
