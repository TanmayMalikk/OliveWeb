import { afterEach, expect, it } from "vitest";
import config from "../site.config.json";
import {
  canonicalPath,
  coverForRoute,
  siteDestination,
  entryLinkUrl,
} from "./sites";

afterEach(() => {
  config.publicOrigin = "";
  config.covers.forEach((site) => {
    site.origin = "";
  });
});
it("serves covers at short local paths while preserving old cover links", () => {
  for (const site of config.covers) {
    expect(coverForRoute(site.path)?.id).toBe(site.id);
    expect(coverForRoute(`/cover/${site.slug}/`)?.id).toBe(site.id);
    expect(
      siteDestination(`/cover/${site.slug}`, "http://localhost:5173"),
    ).toBe(`http://localhost:5173${site.path}`);
  }
  expect(canonicalPath("/cover/olive/?q=a#b")).toBe("/olive?q=a#b");
  expect(coverForRoute("/")).toBeUndefined();
  expect(coverForRoute("/safe")).toBeUndefined();
  expect(coverForRoute("/cover/unknown")).toBeUndefined();
});
it("uses configured cover origins for links and recognizes their root page", () => {
  config.covers[0].origin = "https://food.example.test";
  expect(siteDestination("/olive", "https://main.example.test")).toBe(
    "https://food.example.test/",
  );
  expect(coverForRoute("/", "https://food.example.test")?.id).toBe("FOOD");
  expect(coverForRoute("/", "https://main.example.test")).toBeUndefined();
});
it("keeps public account and private entry links on the configured public origin", () => {
  config.publicOrigin = "https://main.example.test";
  expect(siteDestination("/account/sign-in", "https://food.example.test")).toBe(
    "https://main.example.test/account/sign-in",
  );
  expect(siteDestination("/download", "https://food.example.test")).toBe(
    "https://main.example.test/download",
  );
  expect(entryLinkUrl("a".repeat(43), "https://food.example.test")).toBe(
    `https://main.example.test/entry#${"a".repeat(43)}`,
  );
});
