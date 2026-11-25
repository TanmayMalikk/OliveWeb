import { describe, expect, it, vi } from "vitest";

vi.mock("../services/LocalDataStore", () => ({
  database: Promise.resolve({}),
}));
vi.mock("../services/AccountService", () => ({
  accounts: {},
}));

import { extendIdleTimeoutOptions } from "./useSessionSecurity";

describe("extendIdleTimeoutOptions", () => {
  it("offers ascending larger durations and returns empty at max", () => {
    expect(extendIdleTimeoutOptions(60)).toEqual([120, 300, 450]);
    expect(extendIdleTimeoutOptions(120)).toEqual([180, 300, 450]);
    expect(extendIdleTimeoutOptions(300)).toEqual([360, 450]);
    expect(extendIdleTimeoutOptions(420)).toEqual([450]);
    expect(extendIdleTimeoutOptions(450)).toEqual([]);
  });
});
