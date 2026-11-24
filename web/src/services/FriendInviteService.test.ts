import { beforeEach, expect, it, vi } from "vitest";
import { accounts } from "./AccountService";
import { claimFriendInvite, createFriendInvite, subscribeFriendInvite } from "./FriendInviteService";

const mocks = vi.hoisted(() => ({ callable: vi.fn(), listen: vi.fn(), register: vi.fn() }));
vi.mock("./AccountService", () => ({ accounts: { identity: null }, firebase: async () => ({ db: "db", functions: "functions" }) }));
vi.mock("./MessagingService", () => ({ register: mocks.register }));
vi.mock("firebase/functions", () => ({
  httpsCallable: (_functions: unknown, name: string) => (input: unknown) => mocks.callable(name, input),
}));
vi.mock("firebase/firestore", () => ({
  doc: (...path: string[]) => path.join("/"),
  onSnapshot: mocks.listen,
}));

const owner = { uid: "owner", username: "synthetic_owner", role: "USER" } as const;
const session = accounts as { identity: typeof owner | { uid: string } | null };

beforeEach(() => {
  vi.resetAllMocks();
  session.identity = owner;
});

it("creates and refreshes invites for only the active account", async () => {
  const initial = { code: "Fixture1", link: "https://example.test/get/?invite=Fixture1", expiresAt: 1_800_000_000_000 };
  const refreshed = { ...initial, code: "Fixture2", link: "https://example.test/get/?invite=Fixture2" };
  mocks.callable.mockResolvedValueOnce({ data: initial }).mockResolvedValueOnce({ data: refreshed });
  await expect(createFriendInvite()).resolves.toEqual(initial);
  await expect(createFriendInvite(true)).resolves.toEqual(refreshed);
  expect(mocks.callable.mock.calls).toEqual([["friendInviteCreate", { refresh: false }], ["friendInviteCreate", { refresh: true }]]);
  mocks.callable.mockImplementationOnce(async () => {
    session.identity = { uid: "different" };
    return { data: initial };
  });
  await expect(createFriendInvite()).rejects.toThrow("Your account changed");
  session.identity = null;
  await expect(createFriendInvite()).rejects.toThrow("Sign in first");
  await expect(claimFriendInvite("Fixture1")).rejects.toThrow("Sign in first");
  expect(mocks.callable).toHaveBeenCalledTimes(3);
});

it("registers encryption before claiming and ignores a success delivered after account switching", async () => {
  const claimed = { friendshipId: "friendship", username: "inviter" };
  mocks.callable.mockResolvedValueOnce({ data: claimed });
  await expect(claimFriendInvite("  Fixture1\n")).resolves.toEqual(claimed);
  expect(mocks.register).toHaveBeenCalledWith(owner);
  expect(mocks.callable).toHaveBeenCalledWith("friendInviteClaim", { code: "Fixture1" });
  expect(mocks.register.mock.invocationCallOrder[0]).toBeLessThan(mocks.callable.mock.invocationCallOrder[0]);
  mocks.callable.mockImplementationOnce(async () => {
    session.identity = null;
    return { data: claimed };
  });
  await expect(claimFriendInvite("Fixture2")).rejects.toThrow("Reopen Messages");
});

it("observes only the requested owner and returns the snapshot unsubscribe", async () => {
  const stop = vi.fn();
  mocks.listen.mockReturnValue(stop);
  const update = vi.fn();
  expect(await subscribeFriendInvite(owner.uid, update)).toBe(stop);
  const [path, receive, reject] = mocks.listen.mock.calls[0];
  expect(path).toBe("db/friendInviteOwners/owner");
  receive({ get: () => undefined });
  receive({ get: (field: string) => field === "code" ? "Fixture1" : "friend" });
  reject(new Error("Offline"));
  expect(update.mock.calls).toEqual([[{ code: "", claimedBy: "" }], [{ code: "Fixture1", claimedBy: "friend" }]]);
});
