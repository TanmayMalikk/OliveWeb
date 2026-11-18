import { beforeEach, describe, expect, it, vi } from "vitest";
import { Accounts } from "./AccountService";
import { emptyProfile, type Entry } from "../domain/DomainModels";
import { database, readLocal } from "./LocalDataStore";

const memory = vi.hoisted(() => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "");
  return new Map<string, unknown>();
});
vi.mock("idb", () => ({ openDB: async () => ({
  get: async (store: string, key: string) => structuredClone(memory.get(`${store}:${key}`)),
  put: async (store: string, value: {id?: string}, key?: string) => memory.set(`${store}:${key ?? value.id}`, structuredClone(value)),
  add: async (store: string, value: {id: string}) => {
    const key = `${store}:${value.id}`;
    if (memory.has(key)) throw new Error("Duplicate key");
    memory.set(key, structuredClone(value));
  },
  delete: async (store: string, key: string) => memory.delete(`${store}:${key}`),
}) }));

const password = "Test-password1!";
const profile = () => emptyProfile("tester", "USER");
const entry = (id: string, body = "Private test note"): Entry => ({
  id, body, title: "", kind: "Note", timestamp: Date.now(), updatedAt: Date.now(), pinned: false, edits: [],
});
beforeEach(() => memory.clear());
async function enrolled() {
  const account = new Accounts();
  await account.authenticate("Tester", password, "USER", true);
  await account.saveProfile(profile());
  await account.enroll(profile(), "246810");
  return account;
}

describe("local account lifecycle with real encryption", () => {
  it("normalizes identities, encrypts stored notes, and restores them after locking", async () => {
    const a = await enrolled();
    await a.saveRecord("private_entries", entry("one"));
    expect(JSON.stringify([...memory.values()])).not.toContain("Private test note");
    a.lock();
    expect(a.identity).toBeNull();
    await expect(a.load()).rejects.toThrow("Unlock your account first");
    const restored = await a.unlock("246810");
    expect(restored.history.private_entries[0].body).toBe("Private test note");
    expect(a.identity?.username).toBe("tester");
  });
  it("keeps phone cover preferences separate from browser enrollment", async () => {
    const a = await enrolled();
    const p = { ...profile(), coverType: "FITNESS" as const, triggerHash: "new-trigger" };
    await a.saveProfile(p);
    const enrollment = await (await database).get("device", "current");
    expect(enrollment.webCover).toBe("GAME");
    expect(enrollment.triggerHash).toBe("new-trigger");
    expect((await a.load()).profile?.coverType).toBe("FITNESS");
  });
  it("isolates roles and accounts and rejects duplicate names and wrong credentials", async () => {
    const a = await enrolled();
    await a.saveRecord("private_entries", entry("one"));
    await expect(new Accounts().authenticate("tester", password, "USER", true)).rejects.toThrow("unavailable");
    await expect(new Accounts().authenticate("tester", "wrong", "USER")).rejects.toThrow("Sign-in failed");
    await expect(new Accounts().authenticate("missing", password, "USER")).rejects.toThrow("Sign-in failed");
    const b = new Accounts();
    const initial = await b.authenticate("tester", password, "SAFE_CONTACT", true);
    expect(initial.history.private_entries).toEqual([]);
    expect(b.identity?.uid).not.toBe(a.identity?.uid);
    await expect(a.saveProfile({...profile(), username: "someone_else"})).rejects.toThrow("Account mismatch");
  });
  it("counts failed PINs, resets after five attempts, and clears failures on success", async () => {
    const a = await enrolled();
    a.lock();
    for (let attempt = 1; attempt <= 5; attempt++) {
      await expect(a.unlock("000000")).rejects.toThrow(attempt === 5 ? "Return to your cover" : `${5 - attempt} attempts remaining`);
      expect(a.identity).toBeNull();
    }
    expect((await (await database).get("device", "current")).failures).toBe(0);
    await a.unlock("246810");
    expect(a.identity?.username).toBe("tester");
  });
  it("rotates PIN and password without losing records or accepting old credentials", async () => {
    const a = await enrolled();
    await a.saveRecord("private_entries", entry("one"));
    await expect(a.changePin("bad", "135790", profile())).rejects.toThrow("Current PIN");
    await a.changePin("246810", "135790", profile());
    await a.changePassword(password, "New-password2!");
    a.lock();
    await expect(a.unlock("246810")).rejects.toThrow("Incorrect PIN");
    expect((await a.unlock("135790")).history.private_entries).toHaveLength(1);
    await expect(new Accounts().authenticate("tester", password, "USER")).rejects.toThrow("Sign-in failed");
    const b = new Accounts();
    expect((await b.authenticate("tester", "New-password2!", "USER")).history.private_entries).toHaveLength(1);
  });
  it("serializes concurrent writes, replaces existing rows, and recovers after a failed save", async () => {
    const a = await enrolled();
    await Promise.all([a.saveRecord("private_entries", entry("one")), a.saveRecord("private_entries", entry("two"))]);
    await a.saveRecord("private_entries", entry("one", "Edited"));
    const notes = (await a.load()).history.private_entries;
    expect(notes).toHaveLength(2);
    expect(notes.find(row => row.id === "one")?.body).toBe("Edited");
    await a.signOut();
    await expect(a.saveRecord("private_entries", entry("blocked"))).rejects.toThrow("Unlock");
    await a.authenticate("tester", password, "USER");
    await a.saveRecord("private_entries", entry("three"));
    expect((await a.load()).history.private_entries).toHaveLength(3);
  });
  it("requires enrollment and completed setup before a browser can unlock", async () => {
    const a = new Accounts();
    await expect(a.unlock("246810")).rejects.toThrow("Incorrect PIN");
    await a.authenticate("tester", password, "USER", true);
    await expect(a.changePin("246810", "135790", profile())).rejects.toThrow("Enroll this browser");
    await a.enroll(profile(), "246810");
    a.lock();
    await expect(a.unlock("246810")).rejects.toThrow("finish setup");
    expect(a.identity).toBeNull();
    expect((await readLocal("absent", crypto.getRandomValues(new Uint8Array(32)))) .history.private_entries).toEqual([]);
  });
});
