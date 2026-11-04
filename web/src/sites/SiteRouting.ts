// All app roles: maps domains and paths to public and private pages.
import config from "../../site.config.json";
import type { Cover } from "../domain/DomainModels";

export const sites = config.covers;
export type SiteMode = "user" | "download" | "admin";

// Chooses the public, download, or organization screen from the URL path.
export function siteMode(pathname = location.pathname): SiteMode {
  const clean = canonicalPath(pathname).replace(/\/+$/, "") || "/";
  if (clean === config.downloadPath) return "download";
  if (clean === config.adminPath) return "admin";
  return "user";
}
// Finds the website settings for the selected cover app.
export function coverSite(id: Cover) {
  return sites.find((site) => site.id === id)!;
}
// Matches a web path or cover domain to the correct cover app.
export function coverForRoute(path: string, origin = "") {
  const clean = path === "/" ? path : path.replace(/\/+$/, "");
  return sites.find(
    (site) =>
      clean === site.path ||
      clean === `/cover/${site.slug}` ||
      (clean === "/" &&
        site.origin !== "" &&
        new URL(site.origin).origin === origin),
  );
}
// Changes an old or alternate cover path into the main path used by the site.
export function canonicalPath(path: string) {
  const [, pathname, suffix] = path.match(/^([^?#]*)(.*)$/)!;
  const clean = pathname === "/download" ? config.downloadPath : pathname;
  return (coverForRoute(clean)?.path ?? clean) + suffix;
}
// Keeps every website page on the main domain. VoIP is deployed as its own service and is not routed here.
export function siteDestination(path: string, currentOrigin: string) {
  const canonical = canonicalPath(path);
  return new URL(canonical, currentOrigin).href;
}
// Builds a private-entry link and keeps its token after the # so it is not sent to the web server.
export function entryLinkUrl(token: string, currentOrigin: string) {
  return `${new URL("/entry", siteDestination("/", currentOrigin)).href}#${token}`;
}
// Builds a full link for a site path using the browser's current address.
export const siteHref = (path: string) =>
  siteDestination(path, location.origin);
