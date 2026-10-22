import config from "../site.config.json";
import type { Cover } from "./domain";

export const sites = config.covers;
export type SiteMode = "user" | "download" | "admin";

const isLocalOrigin = (origin: string) => {
  const hostname = new URL(origin).hostname;
  return (
    hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]"
  );
};

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
export function coverSite(id: Cover) {
  return sites.find((site) => site.id === id)!;
}
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
export function canonicalPath(path: string) {
  const [, pathname, suffix] = path.match(/^([^?#]*)(.*)$/)!;
  return (coverForRoute(pathname)?.path ?? pathname) + suffix;
}
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
export function entryLinkUrl(token: string, currentOrigin: string) {
  return `${new URL("/entry", siteDestination("/", currentOrigin)).href}#${token}`;
}
export const siteHref = (path: string) =>
  siteDestination(path, location.origin);
