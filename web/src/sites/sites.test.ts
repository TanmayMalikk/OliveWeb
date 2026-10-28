// Tests the test behavior.
import { expect, it } from "vitest";
import config from "../../site.config.json";
import {
  canonicalPath,
  coverForRoute,
  siteDestination,
  entryLinkUrl,
  siteMode,
} from ".";
it("recognizes legacy cover paths while keeping their canonical cover identity", () => {
  for (const site of config.covers) {
    expect(coverForRoute(site.path)?.id).toBe(site.id);
    expect(coverForRoute(`/cover/${site.slug}/`)?.id).toBe(site.id);
  }
  expect(canonicalPath("/cover/olive/?q=a#b")).toBe("/olive?q=a#b");
  expect(coverForRoute("/")).toBeUndefined();
  expect(coverForRoute("/safe")).toBeUndefined();
});

it("keeps local preview routes usable", () => {
  expect(siteDestination("/olive", "http://localhost:5173")).toBe(
    "http://localhost:5173/olive",
  );
  expect(siteDestination("/download", "http://127.0.0.1:5173")).toBe(
    "http://127.0.0.1:5173/download",
  );
});

it("maps production traffic to three pathless sites", () => {
  expect(siteDestination("/olive", config.publicOrigin)).toBe(
    `${config.publicOrigin}/`,
  );
  expect(siteDestination("/account/sign-in", config.publicOrigin)).toBe(
    `${config.publicOrigin}/`,
  );
  expect(siteDestination("/download", config.publicOrigin)).toBe(
    `${config.downloadOrigin}/`,
  );
  expect(siteDestination("/admin", config.publicOrigin)).toBe(
    `${config.adminOrigin}/`,
  );
});

it("uses a root hash for private entry links", () => {
  const token = "a".repeat(43);
  expect(entryLinkUrl(token, config.publicOrigin)).toBe(
    `${config.publicOrigin}/entry#${token}`,
  );
});

it("selects site mode by build override or hostname", () => {
  expect(siteMode("anything.test", "download")).toBe("download");
  expect(siteMode("admin.yourolivebranch.ca", "")).toBe("admin");
  expect(siteMode("get.yourolivebranch.ca", "")).toBe("download");
  expect(siteMode("yourolivebranch.ca", "")).toBe("user");
});
