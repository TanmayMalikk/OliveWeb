// Web safe-app users: validates and hashes discreet entry-link tokens without reading account state.
export const validEntryToken = (token: string) =>
  typeof token === "string" && /^[A-Za-z0-9_-]{43}$/.test(token);

export async function tokenDigest(token: string) {
  return Array.from(
    new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(token)),
    ),
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
}
