// All app roles: maps domains and paths to public and private pages.
import config from "../../site.config.json";
export const webCover = { ...config.webCover, id: "GAME" } as const;
export type SiteMode = "user" | "download" | "admin";

// Chooses the public, download, or organization screen from the URL path.
export function siteMode(pathname = location.pathname): SiteMode {
  const clean = canonicalPath(pathname).replace(/\/+$/, "") || "/";
  if (clean === config.downloadPath) return "download";
  if (clean === config.adminPath) return "admin";
  return "user";
}
// Old cover bookmarks open Little Grove.
export function canonicalPath(path: string) {
  const [, pathname, suffix] = path.match(/^([^?#]*)(.*)$/)!;
  const clean = pathname.replace(/\/+$/, "") || "/";
  if (/^\/(?:cover\/)?(?:game|fitness|daily-digest)$/.test(clean)) return "/" + suffix;
  return (clean === "/download" ? config.downloadPath : pathname) + suffix;
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
