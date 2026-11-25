const encoder = new TextEncoder();
const RECOVERY_KEY_BYTES = 32;
const IV_BYTES = 12;

export class AccountRecoveryError extends Error {
  constructor(message: string, cause?: unknown) {
    super(message, { cause });
    this.name = "AccountRecoveryError";
  }
}

export interface AccountKeysetEnvelope {
  version: 1;
  accountId: string;
  keyId: string;
  epoch: number;
  algorithm: "RSA-OAEP-256";
  publicKey: string;
  iv: string;
  ciphertext: string;
}

export interface UnlockedAccountKeyset {
  accountId: string;
  keyId: string;
  epoch: number;
  publicKey: CryptoKey;
  privateKey: CryptoKey;
}

export interface AccountWrappedKey {
  keyId: string;
  epoch: number;
  algorithm: "RSA-OAEP-256";
  wrappedKey: string;
}

interface AccountKeyDirectory {
  ownerUid: string;
  keyId: string;
  epoch: number;
  algorithm: "RSA-OAEP-256";
  publicKey: string;
}

/** Owns the account-keyset wrapper format shared by media and chat payload keys. */
export const accountKeysetWrappers = {
  parse(value: unknown): AccountWrappedKey | null {
    if (!value || typeof value !== "object" || Array.isArray(value)) return null;
    const candidate = value as Partial<AccountWrappedKey>;
    return validKeyId(candidate.keyId ?? "") && Number.isSafeInteger(candidate.epoch) &&
      candidate.epoch! >= 1 && candidate.algorithm === "RSA-OAEP-256" &&
      typeof candidate.wrappedKey === "string" && candidate.wrappedKey.length > 0 &&
      candidate.wrappedKey.length <= 768
      ? candidate as AccountWrappedKey
      : null;
  },

  async wrap(accountId: string, rawKey: Uint8Array, value: unknown): Promise<AccountWrappedKey> {
    validateLength(rawKey, 32, "Invalid content key.");
    const directory = parseAccountKeyDirectory(value);
    if (directory.ownerUid !== accountId) {
      throw new AccountRecoveryError("The account key directory does not match this account.");
    }
    try {
      const publicKey = await crypto.subtle.importKey(
        "spki",
        fromBase64(directory.publicKey) as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["encrypt"],
      );
      const wrappedKey = await crypto.subtle.encrypt(
        { name: "RSA-OAEP" },
        publicKey,
        rawKey as BufferSource,
      );
      return {
        keyId: directory.keyId,
        epoch: directory.epoch,
        algorithm: directory.algorithm,
        wrappedKey: toBase64(new Uint8Array(wrappedKey)),
      };
    } catch (error) {
      if (error instanceof AccountRecoveryError) throw error;
      throw new AccountRecoveryError("Could not wrap the content key for this account.", error);
    }
  },

  async unwrap(
    accountId: string,
    value: unknown,
    keyset: UnlockedAccountKeyset | null,
  ): Promise<Uint8Array | null> {
    const wrapper = this.parse(value);
    if (!wrapper || !keyset || keyset.accountId !== accountId ||
        wrapper.keyId !== keyset.keyId || wrapper.epoch !== keyset.epoch) return null;
    try {
      const raw = new Uint8Array(await crypto.subtle.decrypt(
        { name: "RSA-OAEP" },
        keyset.privateKey,
        fromBase64(wrapper.wrappedKey) as BufferSource,
      ));
      if (raw.byteLength === 32) return raw;
      raw.fill(0);
      return null;
    } catch {
      return null;
    }
  },
};

export async function createAccountKeysetEnvelope(
  accountId: string,
  recoveryKey: Uint8Array,
): Promise<{ envelope: AccountKeysetEnvelope; keyset: UnlockedAccountKeyset }> {
  if (!validAccountId(accountId)) {
    throw new AccountRecoveryError("Invalid account ID.");
  }
  validateLength(recoveryKey, RECOVERY_KEY_BYTES, "Invalid recovery key.");
  const pair = await crypto.subtle.generateKey(
    {
      name: "RSA-OAEP",
      modulusLength: 3072,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["encrypt", "decrypt"],
  );
  const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
  let pkcs8: Uint8Array | undefined;
  try {
    pkcs8 = new Uint8Array(await crypto.subtle.exportKey("pkcs8", pair.privateKey));
    const keyId = crypto.randomUUID();
    const iv = crypto.getRandomValues(new Uint8Array(IV_BYTES));
    const envelopeHeader: AccountKeysetEnvelope = {
      version: 1,
      accountId,
      keyId,
      epoch: 1,
      algorithm: "RSA-OAEP-256",
      publicKey: toBase64(spki),
      iv: toBase64(iv),
      ciphertext: "",
    };
    const aes = await crypto.subtle.importKey(
      "raw",
      recoveryKey as BufferSource,
      "AES-GCM",
      false,
      ["encrypt"],
    );
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      {
        name: "AES-GCM",
        iv: iv as BufferSource,
        additionalData: accountKeysetAad(envelopeHeader),
      },
      aes,
      pkcs8 as BufferSource,
    ));
    const [publicKey, privateKey] = await Promise.all([
      crypto.subtle.importKey(
        "spki",
        spki as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["encrypt"],
      ),
      crypto.subtle.importKey(
        "pkcs8",
        pkcs8 as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["decrypt"],
      ),
    ]);
    return {
      envelope: { ...envelopeHeader, ciphertext: toBase64(ciphertext) },
      keyset: { accountId, keyId, epoch: 1, publicKey, privateKey },
    };
  } finally {
    pkcs8?.fill(0);
  }
}

export async function unlockAccountKeyset(
  accountId: string,
  recoveryKey: Uint8Array,
  envelope: AccountKeysetEnvelope,
): Promise<UnlockedAccountKeyset> {
  validateLength(recoveryKey, RECOVERY_KEY_BYTES, "Invalid recovery key.");
  if (
    envelope.version !== 1 ||
    envelope.accountId !== accountId ||
    !validAccountId(accountId) ||
    !validKeyId(envelope.keyId) ||
    !Number.isSafeInteger(envelope.epoch) ||
    envelope.epoch < 1 ||
    envelope.algorithm !== "RSA-OAEP-256"
  ) {
    throw new AccountRecoveryError("The account keyset does not match this account.");
  }
  let plaintext: Uint8Array | undefined;
  try {
    const iv = fromBase64(envelope.iv);
    const ciphertext = fromBase64(envelope.ciphertext);
    const publicKeyBytes = fromBase64(envelope.publicKey);
    validateLength(iv, IV_BYTES, "Invalid account keyset IV.");
    if (ciphertext.byteLength <= 16 || publicKeyBytes.byteLength === 0) {
      throw new AccountRecoveryError("Invalid encrypted account keyset.");
    }
    const aes = await crypto.subtle.importKey(
      "raw",
      recoveryKey as BufferSource,
      "AES-GCM",
      false,
      ["decrypt"],
    );
    plaintext = new Uint8Array(await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: iv as BufferSource,
        additionalData: accountKeysetAad(envelope),
      },
      aes,
      ciphertext as BufferSource,
    ));
    const [publicKey, privateKey] = await Promise.all([
      crypto.subtle.importKey(
        "spki",
        publicKeyBytes as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["encrypt"],
      ),
      crypto.subtle.importKey(
        "pkcs8",
        plaintext as BufferSource,
        { name: "RSA-OAEP", hash: "SHA-256" },
        false,
        ["decrypt"],
      ),
    ]);
    return {
      accountId,
      keyId: envelope.keyId,
      epoch: envelope.epoch,
      publicKey,
      privateKey,
    };
  } catch (error) {
    if (error instanceof AccountRecoveryError) throw error;
    throw new AccountRecoveryError("This recovery key cannot open the account keyset.", error);
  } finally {
    plaintext?.fill(0);
  }
}

export function validateLength(value: Uint8Array, expected: number, message: string) {
  if (value.byteLength !== expected) throw new AccountRecoveryError(message);
}

export function validAccountId(value: string): boolean {
  return /^[A-Za-z0-9_-]{1,128}$/u.test(value);
}

function validKeyId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u.test(value);
}

function accountKeysetAad(envelope: AccountKeysetEnvelope): ArrayBuffer {
  return encoder.encode([
    envelope.version,
    envelope.accountId,
    envelope.keyId,
    envelope.epoch,
    envelope.algorithm,
  ].join("|")).buffer as ArrayBuffer;
}

function parseAccountKeyDirectory(value: unknown): AccountKeyDirectory {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new AccountRecoveryError("Invalid account key directory.");
  }
  const candidate = value as Partial<AccountKeyDirectory>;
  if (!validAccountId(candidate.ownerUid ?? "") || !validKeyId(candidate.keyId ?? "") ||
      !Number.isSafeInteger(candidate.epoch) || candidate.epoch! < 1 ||
      candidate.algorithm !== "RSA-OAEP-256" ||
      typeof candidate.publicKey !== "string" || !candidate.publicKey) {
    throw new AccountRecoveryError("Invalid account key directory.");
  }
  return candidate as AccountKeyDirectory;
}

export function toBase64(value: Uint8Array): string {
  const chunks: string[] = [];
  for (let offset = 0; offset < value.byteLength; offset += 0x8000) {
    chunks.push(String.fromCharCode(...value.subarray(offset, offset + 0x8000)));
  }
  return btoa(chunks.join(""));
}

export function fromBase64(value: string): Uint8Array {
  try {
    return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
  } catch (error) {
    throw new AccountRecoveryError("Invalid encoded recovery record.", error);
  }
}
