import { describe, expect, it } from "vitest";
import {
  buildAssertionRequest,
  buildRegistrationRequest,
  toBase64Url,
} from "./PasskeyWebAuthn";

const options = {
  challenge: "Y2hhbGxlbmdl",
  rp: { id: "yourolivebranch.ca", name: "Olive Branch" },
  user: { id: "dXNlci1h", name: "user-a", displayName: "" },
  pubKeyCredParams: [{ type: "public-key" as const, alg: -7 }],
};

const bytes = (value: BufferSource) => value instanceof ArrayBuffer
  ? new Uint8Array(value)
  : new Uint8Array(value.buffer as ArrayBuffer, value.byteOffset, value.byteLength);

describe("passkey WebAuthn conversion", () => {
  it("adds a binary PRF input to registration without changing the relying-party options", () => {
    const request = buildRegistrationRequest(options, new Uint8Array(32).fill(2));

    expect(request.rp.id).toBe("yourolivebranch.ca");
    expect(bytes(request.challenge)).toEqual(new TextEncoder().encode("challenge"));
    expect(bytes(request.user.id)).toEqual(new TextEncoder().encode("user-a"));
    expect(bytes(request.extensions?.prf?.eval?.first as BufferSource)).toEqual(new Uint8Array(32).fill(2));
  });

  it("binds each assertion candidate to its own binary PRF input", () => {
    const request = buildAssertionRequest({
      challenge: "Y2hhbGxlbmdl",
      rpId: "yourolivebranch.ca",
      allowCredentials: [
        { id: "Y3JlZGVudGlhbC1h", type: "public-key" as const, transports: ["internal"] },
      ],
    }, {
      "credential-a": toBase64Url(new Uint8Array(32).fill(4)),
    });

    expect(bytes(request.challenge)).toEqual(new TextEncoder().encode("challenge"));
    expect(bytes(request.allowCredentials?.[0].id ?? new ArrayBuffer(0))).toEqual(
      new TextEncoder().encode("credential-a"),
    );
    expect(bytes(request.extensions?.prf?.evalByCredential?.["credential-a"]?.first as BufferSource)).toEqual(
      new Uint8Array(32).fill(4),
    );
  });

  it("rejects malformed or empty binary inputs", () => {
    expect(() => buildRegistrationRequest(options, new Uint8Array(31))).toThrow();
    expect(() => buildAssertionRequest({ challenge: "!", rpId: "yourolivebranch.ca" }, {})).toThrow();
    expect(() => buildAssertionRequest({ challenge: "YQ", rpId: "yourolivebranch.ca" }, {
      "credential-a": "!",
    })).toThrow();
  });
});
