// All app roles: maps domains and paths to public and private pages.
import config from "../../site.config.json";
import type { Cover } from "../domain/DomainModels";

export const sites = config.covers;
export type SiteMode = "user" | "download" | "admin";

// Checks whether the site is running on this computer. Local testing keeps every page on one origin.
const isLocalOrigin = (origin: string) => {
  const hostname = new URL(origin).hostname;
  return (
    hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]"
  );
};

// Chooses the user, download, or admin site from the current domain name.
export function siteMode(
  hostname = location.hostname,
  override = import.meta.env.VITE_SITE_MODE,
): SiteMode {
  if (override === "user" || override === "download" || override === "admin")
    return override;
  if (
    hostname === new URL(config.downloadOrigin).hostname ||
    hostname.startsWith("get.")
  )
    return "download";
  if (
    hostname === new URL(config.adminOrigin).hostname ||
    hostname.startsWith("admin.")
  )
    return "admin";
  return "user";
}
// Finds the saved site settings for one cover app.
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
  return (coverForRoute(pathname)?.path ?? pathname) + suffix;
}
// Sends each path to the correct public, download, or admin domain. Local testing stays on the current address.
export function siteDestination(path: string, currentOrigin: string) {
  const canonical = canonicalPath(path);
  if (isLocalOrigin(currentOrigin))
    return new URL(canonical, currentOrigin).href;
  if (/^\/download(?:\/|$)/.test(canonical))
    return new URL("/", config.downloadOrigin).href;
  if (/^\/admin(?:\/|$)/.test(canonical))
    return new URL("/", config.adminOrigin).href;
  return new URL("/", config.publicOrigin || currentOrigin).href;
}
// Builds a private-entry link and keeps its token after the # so it is not sent to the web server.
export function entryLinkUrl(token: string, currentOrigin: string) {
  return `${new URL("/entry", siteDestination("/", currentOrigin)).href}#${token}`;
}
// Builds a full link for a site path using the browser's current address.
export const siteHref = (path: string) =>
  siteDestination(path, location.origin);
