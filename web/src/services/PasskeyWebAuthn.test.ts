import { beforeEach, describe, expect, it, vi } from "vitest";

const ceremonies = vi.hoisted(() => ({
  register: vi.fn(),
  authenticate: vi.fn(),
}));

vi.mock("@simplewebauthn/browser", () => ({
  startRegistration: ceremonies.register,
  startAuthentication: ceremonies.authenticate,
}));

import { browserPasskeys } from "./PasskeyWebAuthn";

const credentialId = "credential-a";
const serverResponse = (first?: Uint8Array) => ({
  id: credentialId,
  rawId: "Y3JlZGVudGlhbC1h",
  response: {
    authenticatorData: "YXV0aA",
    clientDataJSON: "Y2xpZW50",
    signature: "c2ln",
  },
  type: "public-key" as const,
  clientExtensionResults: first
    ? { prf: { enabled: true, results: { first } } }
    : { prf: { enabled: true } },
});

describe("passkey WebAuthn adapter", () => {
  beforeEach(() => vi.clearAllMocks());

  it("registers using only the server options", async () => {
    const options = { challenge: "Y2hhbGxlbmdl" };
    ceremonies.register.mockResolvedValue(serverResponse());

    await expect(browserPasskeys.register(options)).resolves.toEqual({
      response: serverResponse(),
    });
    expect(ceremonies.register).toHaveBeenCalledWith({ optionsJSON: options });
  });

  it("keeps the PRF result local and strips it from the server response", async () => {
    const prfOutput = new Uint8Array(32).fill(4);
    ceremonies.authenticate.mockResolvedValue(serverResponse(prfOutput));

    const result = await browserPasskeys.authenticate(
      { challenge: "Y2hhbGxlbmdl", rpId: "yourolivebranch.ca" },
      { [credentialId]: "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=" },
    );

    expect(result.prfOutput).toEqual(prfOutput);
    expect(result.prfOutput).not.toBe(prfOutput);
    expect(result.response.clientExtensionResults).toEqual({ prf: { enabled: true } });
    expect(JSON.stringify(result.response)).not.toContain("results");
    const options = ceremonies.authenticate.mock.calls[0][0].optionsJSON;
    expect(options.extensions.prf.evalByCredential[credentialId].first).toEqual(
      new Uint8Array(32).fill(5),
    );
  });

  it("fails securely when the provider omits or changes the PRF result size", async () => {
    ceremonies.authenticate.mockResolvedValueOnce(serverResponse());
    await expect(browserPasskeys.authenticate(
      { challenge: "Y2hhbGxlbmdl", rpId: "yourolivebranch.ca" },
      { [credentialId]: "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=" },
    )).rejects.toThrow("32-byte PRF result");

    ceremonies.authenticate.mockResolvedValueOnce(serverResponse(new Uint8Array(31)));
    await expect(browserPasskeys.authenticate(
      { challenge: "Y2hhbGxlbmdl", rpId: "yourolivebranch.ca" },
      { [credentialId]: "BQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQU=" },
    )).rejects.toThrow("32-byte PRF result");
  });
});
