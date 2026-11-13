import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SafetyPlan } from "./SafetyPlanService";

const mocks = vi.hoisted(() => ({
  call: vi.fn(), subscribe: vi.fn(), stop: vi.fn(),
}));
vi.mock("./AccountService", () => ({ firebase: async () => ({ db: {}, functions: {} }) }));
vi.mock("firebase/functions", () => ({ httpsCallable: (_functions: unknown, name: string) =>
  (data: unknown) => mocks.call(name, data) }));
vi.mock("firebase/firestore", () => ({
  doc: (_db: unknown, collection: string, id: string) => `${collection}/${id}`,
  onSnapshot: mocks.subscribe,
}));

import { observePlan, observeSafetyAccess, resolvePlanConflict, saveField } from "./SafetyPlanService";

const plan: SafetyPlan = {
  ownerUid: "owner", ownerName: "Owner", staffUid: "staff", staffName: "",
  consultationRequested: true, safeContactUids: [], safeContactNames: {},
  fields: { primary_documents: { value: "Latest checklist", revision: 4 } },
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.call.mockResolvedValue({ data: { plan, conflicts: [] } });
  mocks.subscribe.mockReturnValue(mocks.stop);
});

describe("safety-plan edits and conflict resolution", () => {
  it("sends the draft's original baseline when a newer checklist has arrived", async () => {
    const result = { plan, conflicts: ["primary_documents"], conflictValues: { primary_documents: "Merged proposal" } };
    mocks.call.mockResolvedValue({ data: result });
    expect(await saveField(plan, "primary_documents", "My edit", 2, "Original checklist")).toEqual(result);
    expect(mocks.call).toHaveBeenCalledWith("safetyPlanSync", {
      ownerUid: "owner", defaults: {}, changes: [{
        key: "primary_documents", value: "My edit", revision: 2,
        baseValue: "Original checklist", mutationId: expect.any(String),
        writerId: expect.any(String), clientSequence: expect.any(Number),
      }],
    });
  });

  it("uses the current field as the baseline for a new edit and keeps an explicit empty baseline", async () => {
    await saveField(plan, "primary_documents", "New edit");
    expect(mocks.call.mock.calls[0][1].changes[0]).toMatchObject({ revision: 4, baseValue: "Latest checklist" });
    await saveField(plan, "primary_documents", "New edit", 0, "");
    expect(mocks.call.mock.calls[1][1].changes[0]).toMatchObject({ revision: 0, baseValue: "" });
  });

  it.each([true, false])("sends the owner's incoming-change choice (%s) with the proposal and revision", async useIncoming => {
    const conflicting = { ...plan, pendingConflicts: { primary_documents: { value: "Staff edit", mutationId: "proposal" } } };
    expect(await resolvePlanConflict(conflicting, "primary_documents", useIncoming)).toEqual(plan);
    expect(mocks.call).toHaveBeenCalledWith("safetyPlanSync", {
      ownerUid: "owner", resolution: { key: "primary_documents", revision: 4, mutationId: "proposal", useIncoming },
    });
  });

  it("rejects an already-resolved proposal without sending a stale choice", async () => {
    await expect(resolvePlanConflict(plan, "primary_documents", true)).rejects.toThrow("already been resolved");
    expect(mocks.call).not.toHaveBeenCalled();
  });
});

describe("live safety-plan listeners", () => {
  it("delivers server updates, ignores cached data, forwards errors, and exposes cleanup", async () => {
    const update = vi.fn(), error = vi.fn();
    const stop = await observePlan("owner", update, error);
    const [path, options, receive, fail] = mocks.subscribe.mock.calls[0];
    expect(path).toBe("safetyPlans/owner");
    expect(options).toEqual({ includeMetadataChanges: true });
    receive({ exists: () => false });
    receive({ exists: () => true, metadata: { fromCache: true } });
    expect(update).not.toHaveBeenCalled();
    receive({ exists: () => true, metadata: { fromCache: false }, data: () => ({
      ...plan, safeContactUids: ["contact", 42], ticketNumber: 516,
    }) });
    expect(update).toHaveBeenCalledWith(expect.objectContaining({ fields: plan.fields, safeContactUids: ["contact"], ticketNumber: 516 }));
    const failure = new Error("Access revoked");
    fail(failure);
    expect(error).toHaveBeenCalledWith(failure);
    stop();
    expect(mocks.stop).toHaveBeenCalledOnce();
  });

  it("refreshes access only when a server-confirmed safety revision changes", async () => {
    const update = vi.fn(), error = vi.fn();
    await observeSafetyAccess("owner", update, error);
    const [path, , receive, fail] = mocks.subscribe.mock.calls[0];
    expect(path).toBe("syncInvalidations/owner");
    receive({ metadata: { fromCache: true } });
    expect(update).not.toHaveBeenCalled();
    for (const revision of [undefined, 0, 1, 1, 2]) {
      receive({ metadata: { fromCache: false }, get: () => revision });
    }
    expect(update).toHaveBeenCalledTimes(3);
    expect(fail).toBe(error);
  });
});
