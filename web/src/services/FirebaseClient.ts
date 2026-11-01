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
import { getStorage, connectStorageEmulator } from "firebase/storage";
import { getFunctions, connectFunctionsEmulator } from "firebase/functions";
import {
  initializeAppCheck,
  ReCaptchaEnterpriseProvider,
} from "firebase/app-check";
const env = import.meta.env;
export const app = initializeApp({
  apiKey: env.VITE_FIREBASE_API_KEY,
  authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: env.VITE_FIREBASE_PROJECT_ID,
  storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
  appId: env.VITE_FIREBASE_APP_ID,
});
export const auth = getAuth(app);
export const db = initializeFirestore(app, { localCache: memoryLocalCache() });
export const storage = getStorage(app);
export const functions = getFunctions(app, "northamerica-northeast1");
if (
  env.PROD &&
  env.VITE_FIREBASE_APPCHECK_SITE_KEY &&
  env.VITE_USE_FIREBASE_EMULATORS !== "true"
) {
  initializeAppCheck(app, {
    provider: new ReCaptchaEnterpriseProvider(
      env.VITE_FIREBASE_APPCHECK_SITE_KEY,
    ),
    isTokenAutoRefreshEnabled: true,
  });
}
// env vite use firebase emulators is true is handled here by functions.
if (env.VITE_USE_FIREBASE_EMULATORS === "true") {
  if (!env.VITE_FIREBASE_PROJECT_ID?.startsWith("demo-"))
    throw new Error("Emulators require a demo- project ID.");
  connectAuthEmulator(auth, "http://127.0.0.1:9099", { disableWarnings: true });
  connectFirestoreEmulator(db, "127.0.0.1", 8080);
  connectStorageEmulator(storage, "127.0.0.1", 9199);
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
}
export const authReady = setPersistence(auth, browserSessionPersistence);
