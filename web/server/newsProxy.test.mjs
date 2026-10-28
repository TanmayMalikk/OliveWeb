// Tests news proxy URL and text checks for news cover users.
import { describe, it, expect } from "vitest";
import { publicIPv4, safeText } from "./newsProxy.mjs";
describe("publisher network boundaries", () => {
  it("rejects private, loopback, link-local and reserved destinations", () => {
    for (const address of [
      "127.0.0.1",
      "10.1.2.3",
      "192.168.1.2",
      "172.16.0.1",
      "169.254.169.254",
      "100.64.0.1",
      "0.0.0.0",
      "224.1.1.1",
      "::1",
      "999.1.1.1",
    ])
      expect(publicIPv4(address)).toBe(false);
    expect(publicIPv4("8.8.8.8")).toBe(true);
  });
  it("does not accept non-HTTPS URLs or URL credentials", async () => {
    await expect(safeText("http://example.com")).rejects.toThrow("HTTPS");
    await expect(safeText("https://user:pass@example.com")).rejects.toThrow(
      "HTTPS",
    );
  });
  it("rejects literal private IPs before the HTTP client can bypass DNS validation", async () => {
    for (const url of [
      "https://127.0.0.1",
      "https://2130706433",
      "https://[::1]",
      "https://169.254.169.254",
    ])
      await expect(safeText(url)).rejects.toThrow("Private network");
  });
});
