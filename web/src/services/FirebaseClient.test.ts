import { afterEach, describe, expect, it, vi } from "vitest";

describe("FirebaseClient initialization", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("initializes Firebase with App Check v3 in standard mode and connects emulators in demo mode", async () => {
    vi.resetModules();
    const initializeApp = vi.fn(() => ({ name: "olive" }));
    const setPersistence = vi.fn(async () => {});
    const connectAuthEmulator = vi.fn();
    const connectFirestoreEmulator = vi.fn();
    const connectDatabaseEmulator = vi.fn();
    const connectStorageEmulator = vi.fn();
    const connectFunctionsEmulator = vi.fn();
    const initializeAppCheck = vi.fn();

    vi.doMock("firebase/app", () => ({ initializeApp }));
    vi.doMock("firebase/auth", () => ({
      getAuth: vi.fn(() => ({})),
      setPersistence,
      browserSessionPersistence: {},
      connectAuthEmulator,
    }));
    vi.doMock("firebase/firestore", () => ({
      initializeFirestore: vi.fn(() => ({})),
      memoryLocalCache: vi.fn(() => ({})),
      connectFirestoreEmulator,
    }));
    vi.doMock("firebase/database", () => ({ getDatabase: vi.fn(() => ({})), connectDatabaseEmulator }));
    vi.doMock("firebase/storage", () => ({ getStorage: vi.fn(() => ({})), connectStorageEmulator }));
    vi.doMock("firebase/functions", () => ({ getFunctions: vi.fn(() => ({})), connectFunctionsEmulator }));
    vi.doMock("firebase/app-check", () => ({
      initializeAppCheck,
      ReCaptchaEnterpriseProvider: class {},
      ReCaptchaV3Provider: class {},
    }));

    vi.stubEnv("VITE_FIREBASE_PROJECT_ID", "demo-olive");
    vi.stubEnv("VITE_FIREBASE_APPCHECK_SITE_KEY", "site-key");
    vi.stubEnv("VITE_FIREBASE_APPCHECK_PROVIDER", "recaptcha-v3");
    vi.stubEnv("VITE_FIREBASE_APPCHECK_DEBUG_TOKEN", "true");
    vi.stubEnv("VITE_USE_FIREBASE_EMULATORS", "false");

    const client = await import("./FirebaseClient");
    expect(client.app).toBeDefined();
    expect(initializeAppCheck).toHaveBeenCalledTimes(1);

    vi.resetModules();
    vi.stubEnv("VITE_USE_FIREBASE_EMULATORS", "true");
    vi.stubEnv("VITE_FIREBASE_PROJECT_ID", "demo-olive");
    await import("./FirebaseClient");
    expect(connectAuthEmulator).toHaveBeenCalled();
    expect(connectFirestoreEmulator).toHaveBeenCalled();
    expect(connectDatabaseEmulator).toHaveBeenCalled();
    expect(connectStorageEmulator).toHaveBeenCalled();
    expect(connectFunctionsEmulator).toHaveBeenCalled();

    vi.resetModules();
    vi.stubEnv("VITE_FIREBASE_PROJECT_ID", "prod-olive");
    await expect(import("./FirebaseClient")).rejects.toThrow("Emulators require a demo- project ID.");
  });
});
