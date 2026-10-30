import {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} from "@firebase/rules-unit-testing";
import {
  ref,
  uploadString,
  getBytes,
  deleteObject,
  updateMetadata,
} from "firebase/storage";
import { doc, setDoc } from "firebase/firestore";
import { readFile } from "node:fs/promises";
import { before, after, test } from "node:test";

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-olive-rules",
    storage: {
      host: "127.0.0.1",
      port: 9199,
      rules: await readFile(
        new URL("./storage.rules", import.meta.url),
        "utf8",
      ),
    },
    firestore: {
      host: "127.0.0.1",
      port: 8180,
      rules: await readFile(
        new URL("./firestore.rules", import.meta.url),
        "utf8",
      ),
    },
  });
});

after(async () => {
  await env?.cleanup();
});

test("the published Android installer is public but cannot be changed by clients", async () => {
  const path = "public/android/olive-branch.apk";
  await env.withSecurityRulesDisabled(async (context) => {
    await uploadString(ref(context.storage(), path), "signed apk");
  });
  const anonymous = env.unauthenticatedContext().storage();
  const signedIn = env.authenticatedContext("alice-uid").storage();
  await assertSucceeds(getBytes(ref(anonymous, path)));
  await assertSucceeds(getBytes(ref(signedIn, path)));
  await assertFails(uploadString(ref(anonymous, path), "replacement"));
  await assertFails(uploadString(ref(signedIn, path), "replacement"));
  await assertFails(deleteObject(ref(signedIn, path)));
});

test("only the account owner can read and write its profile object", async () => {
  const alice = env.authenticatedContext("alice-uid").storage();
  const bob = env.authenticatedContext("bob-uid").storage();
  const anonymous = env.unauthenticatedContext().storage();
  const path = "accounts/alice-uid/profile.json";
  const body = JSON.stringify({ username: "alice", fullName: "Test Person" });
  await assertSucceeds(
    uploadString(ref(alice, path), body, "raw", {
      contentType: "application/json",
    }),
  );
  await assertSucceeds(getBytes(ref(alice, path)));
  await assertFails(getBytes(ref(bob, path)));
  await assertFails(
    uploadString(ref(bob, path), body, "raw", {
      contentType: "application/json",
    }),
  );
  await assertFails(getBytes(ref(anonymous, path)));
  await assertSucceeds(deleteObject(ref(alice, path)));
});

test("profile objects enforce their path, type and size", async () => {
  const owner = env.authenticatedContext("alice-uid").storage();
  await assertFails(
    uploadString(ref(owner, "accounts/alice-uid/extra.json"), "{}", "raw", {
      contentType: "application/json",
    }),
  );
  await assertFails(
    uploadString(ref(owner, "accounts/alice-uid/profile.json"), "{}", "raw", {
      contentType: "text/plain",
    }),
  );
  await assertFails(
    uploadString(
      ref(owner, "accounts/alice-uid/profile.json"),
      "x".repeat(64 * 1024 + 1),
      "raw",
      { contentType: "application/json" },
    ),
  );
});

test("activity history belongs to its account and accepts only small JSON events", async () => {
  const owner = env.authenticatedContext("alice-uid").storage();
  const other = env.authenticatedContext("bob-uid").storage();
  const path = "accounts/alice-uid/history/settings/12345678-1234.json";
  await assertSucceeds(
    uploadString(ref(owner, path), '{"action":"saved"}', "raw", {
      contentType: "application/json",
    }),
  );
  await assertSucceeds(getBytes(ref(owner, path)));
  await assertFails(getBytes(ref(other, path)));
  await assertFails(
    uploadString(ref(other, path), "{}", "raw", {
      contentType: "application/json",
    }),
  );
  await assertFails(
    uploadString(
      ref(owner, "accounts/alice-uid/history/other/12345678-1234.json"),
      "{}",
      "raw",
      { contentType: "application/json" },
    ),
  );
  await assertFails(
    uploadString(ref(owner, path), "{}", "raw", { contentType: "text/plain" }),
  );
  await assertFails(
    uploadString(ref(owner, path), "x".repeat(64 * 1024 + 1), "raw", {
      contentType: "application/json",
    }),
  );
});

test("private notes and incident records belong only to their account", async () => {
  const owner = env.authenticatedContext("alice-uid").storage();
  const other = env.authenticatedContext("bob-uid").storage();
  const path = "accounts/alice-uid/history/private_entries/12345678-1234.json";
  const body = JSON.stringify({
    kind: "Incident",
    title: "Private record",
    body: "Sensitive details",
  });
  await assertSucceeds(
    uploadString(ref(owner, path), body, "raw", {
      contentType: "application/json",
    }),
  );
  await assertSucceeds(getBytes(ref(owner, path)));
  await assertSucceeds(
    uploadString(
      ref(owner, path),
      JSON.stringify({ body: "Edited private details" }),
      "raw",
      { contentType: "application/json" },
    ),
  );
  await assertSucceeds(
    uploadString(
      ref(owner, path),
      JSON.stringify({ edits: "x".repeat(70 * 1024) }),
      "raw",
      { contentType: "application/json" },
    ),
  );
  await assertFails(getBytes(ref(other, path)));
  await assertFails(
    uploadString(ref(other, path), body, "raw", {
      contentType: "application/json",
    }),
  );
  await assertSucceeds(deleteObject(ref(owner, path)));
});

test("media files follow protected object sharing metadata", async () => {
  const path = "users/media-alice/media/storage-media-1";
  await env.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), "files/storage-media-1"), {
      owner_id: "media-alice",
      storage_path: path,
      file_name: "Evidence file",
      file_type: "image/jpeg",
      allowed_users: ["media-alice", "media-bob"],
      created_at: new Date(),
    });
  });
  const alice = env.authenticatedContext("media-alice").storage();
  const bob = env.authenticatedContext("media-bob").storage();
  const charlie = env.authenticatedContext("media-charlie").storage();
  const anonymous = env.unauthenticatedContext().storage();
  await assertSucceeds(
    uploadString(ref(alice, path), "private media", "raw", {
      customMetadata: {
        "access_media-alice": "true",
        "access_media-bob": "true",
      },
    }),
  );
  await assertSucceeds(getBytes(ref(alice, path)));
  await assertSucceeds(getBytes(ref(bob, path)));
  await assertFails(getBytes(ref(charlie, path)));
  await assertFails(getBytes(ref(anonymous, path)));
  await assertFails(uploadString(ref(bob, path), "replacement"));
  await assertFails(
    updateMetadata(ref(bob, path), {
      customMetadata: { "access_media-charlie": "true" },
    }),
  );
  await assertFails(deleteObject(ref(bob, path)));
  await assertSucceeds(deleteObject(ref(alice, path)));
});

test("media owners can read their own object before sharing metadata is available", async () => {
  const path = "users/private-owner/media/private-media-1";
  const owner = env.authenticatedContext("private-owner").storage();
  const other = env.authenticatedContext("another-user").storage();
  await assertSucceeds(uploadString(ref(owner, path), "private media"));
  await assertSucceeds(getBytes(ref(owner, path)));
  await assertFails(getBytes(ref(other, path)));
  await assertSucceeds(deleteObject(ref(owner, path)));
});
