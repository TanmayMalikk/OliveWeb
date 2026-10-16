import config from "../site.config.json";
import type { Cover } from "./domain";

export const sites = config.covers;
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
  const site = coverForRoute(new URL(canonical, currentOrigin).pathname);
  if (site?.origin) return new URL("/", site.origin).href;
  if (
    /^\/(account(?:\/|$)|download(?:\/|$)|entry(?:[#?]|$))/.test(canonical) &&
    config.publicOrigin
  )
    return new URL(canonical, config.publicOrigin).href;
  return new URL(canonical, currentOrigin).href;
}
export function entryLinkUrl(token: string, currentOrigin: string) {
  return siteDestination(`/entry#${token}`, currentOrigin);
}
export const siteHref = (path: string) =>
  siteDestination(path, location.origin);
