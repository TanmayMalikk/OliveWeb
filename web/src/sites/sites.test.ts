// Tests domain routing for all app roles.
import { expect, it } from "vitest";
import config from "../../site.config.json";
import {
  canonicalPath,
  coverForRoute,
  siteDestination,
  entryLinkUrl,
  siteMode,
} from "./SiteRouting";
// Inputs: the values in this case. Expected: recognizes legacy cover paths while keeping their canonical cover identity.
it("recognizes legacy cover paths while keeping their canonical cover identity", () => {
  for (const site of config.covers) {
    expect(coverForRoute(site.path)?.id).toBe(site.id);
    expect(coverForRoute(`/cover/${site.slug}/`)?.id).toBe(site.id);
  }
  expect(canonicalPath("/cover/olive/?q=a#b")).toBe("/olive?q=a#b");
  expect(coverForRoute("/")).toBeUndefined();
  expect(coverForRoute("/safe")).toBeUndefined();
});

// Inputs: the values in this case. Expected: keeps local preview routes usable.
it("keeps every page on the same origin", () => {
  expect(siteDestination("/olive", "http://localhost:5173")).toBe(
    "http://localhost:5173/olive",
  );
  expect(siteDestination("/download", "http://127.0.0.1:5173")).toBe(
    "http://127.0.0.1:5173/get",
  );
  expect(siteDestination("/admin", config.publicOrigin)).toBe(
    `${config.publicOrigin}/admin`,
  );
  expect(siteDestination("/get", config.publicOrigin)).toBe(
    `${config.publicOrigin}/get`,
  );
});

// Inputs: the values in this case. Expected: uses a root hash for private entry links.
it("uses a root hash for private entry links", () => {
  const token = "a".repeat(43);
  expect(entryLinkUrl(token, config.publicOrigin)).toBe(
    `${config.publicOrigin}/entry#${token}`,
  );
});

// Inputs: the page path. Expected: selects the correct part of the single website.
it("selects site mode from the page path", () => {
  expect(siteMode("/get")).toBe("download");
  expect(siteMode("/download")).toBe("download");
  expect(siteMode("/admin")).toBe("admin");
  expect(siteMode("/olive")).toBe("user");
});
