// Web app users: creates the Firebase clients used by the web app.
import { initializeApp } from "firebase/app";
import {
  getAuth,
  setPersistence,
  browserSessionPersistence,
  connectAuthEmulator,
} from "firebase/auth";
import {
  initializeFirestore,
  memoryLocalCache,
  connectFirestoreEmulator,
} from "firebase/firestore";
import { getDatabase, connectDatabaseEmulator } from "firebase/database";
import { getStorage, connectStorageEmulator } from "firebase/storage";
import { getFunctions, connectFunctionsEmulator } from "firebase/functions";
import {
  initializeAppCheck,
  ReCaptchaEnterpriseProvider,
  ReCaptchaV3Provider,
} from "firebase/app-check";
const env = import.meta.env;
const databaseURL =
  env.VITE_FIREBASE_DATABASE_URL ||
  (env.VITE_FIREBASE_PROJECT_ID
    ? `https://${env.VITE_FIREBASE_PROJECT_ID}-default-rtdb.firebaseio.com`
    : undefined);
export const app = initializeApp({
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  appId: env.VITE_FIREBASE_APP_ID,
  ...(databaseURL ? { databaseURL } : {}),
});
export const auth = getAuth(app);
export const db = initializeFirestore(app, { localCache: memoryLocalCache() });
export const rtdb = getDatabase(app);
export const storage = getStorage(app);
export const functions = getFunctions(app, "northamerica-northeast1");
const debugToken = !env.PROD ? env.VITE_FIREBASE_APPCHECK_DEBUG_TOKEN?.trim() : "";
if (debugToken) {
  (
    globalThis as { FIREBASE_APPCHECK_DEBUG_TOKEN?: string | boolean }
  ).FIREBASE_APPCHECK_DEBUG_TOKEN = debugToken === "true" ? true : debugToken;
}
if (
  env.VITE_FIREBASE_APPCHECK_SITE_KEY &&
  env.VITE_USE_FIREBASE_EMULATORS !== "true"
) {
  const provider =
    env.VITE_FIREBASE_APPCHECK_PROVIDER === "recaptcha-v3"
      ? new ReCaptchaV3Provider(env.VITE_FIREBASE_APPCHECK_SITE_KEY)
      : new ReCaptchaEnterpriseProvider(env.VITE_FIREBASE_APPCHECK_SITE_KEY);
  initializeAppCheck(app, {
    provider,
    isTokenAutoRefreshEnabled: true,
  });
}
// Connect to local Firebase emulators only when the development setting asks for them.
if (env.VITE_USE_FIREBASE_EMULATORS === "true") {
  if (!env.VITE_FIREBASE_PROJECT_ID?.startsWith("demo-"))
    throw new Error("Emulators require a demo- project ID.");
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  connectDatabaseEmulator(rtdb, "127.0.0.1", 9000);
  connectStorageEmulator(storage, "127.0.0.1", 9199);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
}
export const authReady = setPersistence(auth, browserSessionPersistence);
