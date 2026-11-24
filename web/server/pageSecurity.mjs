import { randomBytes } from "node:crypto";

const publicPages = new Set([
  "/", "/get", "/download", "/game", "/fitness", "/daily-digest",
  "/cover/game", "/cover/fitness", "/cover/daily-digest",
]);

export function publicPageNonce(pathname) {
  const path = pathname.replace(/\/+$/, "") || "/";
  return publicPages.has(path) ? randomBytes(18).toString("base64") : null;
}

// Private documents keep their existing restrictions. Only public documents trust ad scripts.
export function contentSecurityPolicy(publicNonce) {
  return [
    "default-src 'self'",
    publicNonce
      ? `script-src 'self' 'nonce-${publicNonce}' 'strict-dynamic' 'unsafe-eval' https://www.google.com https://www.gstatic.com`
      : "script-src 'self' 'wasm-unsafe-eval' https://www.google.com https://www.gstatic.com",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' https: data: blob:",
    publicNonce ? "media-src 'self' blob: https:" : "media-src 'self' blob:",
    publicNonce
      ? "connect-src 'self' blob: https: wss://*.firebaseio.com"
      : "connect-src 'self' blob: https://*.googleapis.com https://*.firebaseio.com wss://*.firebaseio.com https://*.cloudfunctions.net https://*.firebasestorage.app https://*.appspot.com https://*.r2.cloudflarestorage.com https://api.mapbox.com https://*.tiles.mapbox.com https://events.mapbox.com",
    publicNonce
      ? "frame-src blob: https:"
      : "frame-src blob: https://*.firebaseapp.com https://www.google.com",
    publicNonce ? "worker-src 'self'" : "worker-src 'self' blob:",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "upgrade-insecure-requests",
  ].join("; ");
}

export function renderPageHtml(template, publicNonce) {
  if (!publicNonce) return template;
  if (!/^[A-Za-z0-9+/]{24}$/.test(publicNonce)) throw new Error("Invalid page nonce");
  return template.replace('data-public-ads="false"', 'data-public-ads="true"')
    .replace(/<script\b/gi, `<script nonce="${publicNonce}"`);
}
