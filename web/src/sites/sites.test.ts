// Tests domain routing for all app roles.
import { expect, it } from "vitest";
import config from "../../site.config.json";
import {
  canonicalPath,
  webCover,
  siteDestination,
  entryLinkUrl,
  siteMode,
} from "./SiteRouting";
it("routes old cover bookmarks to the game", () => {
  for (const slug of ["game", "daily-digest", "fitness"]) {
    expect(canonicalPath(`/${slug}`)).toBe("/");
    expect(canonicalPath(`/cover/${slug}/?q=a#b`)).toBe("/?q=a#b");
  }
  expect(webCover.id).toBe("GAME");
  expect(canonicalPath("/safe")).toBe("/safe");
});

// Inputs: the values in this case. Expected: keeps local preview routes usable.
it("keeps every page on the same origin", () => {
  expect(siteDestination("/daily-digest", "http://localhost:5173")).toBe(
    "http://localhost:5173/",
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
  expect(siteMode("/daily-digest")).toBe("user");
});
