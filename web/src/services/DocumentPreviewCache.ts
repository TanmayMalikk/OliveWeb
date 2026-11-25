// In-memory, session-scoped cache of 50%-blurred live document previews for Web Safe App and Staff/Admin portals.
import type { Identity } from "../domain/DomainModels";
import {
  loadBrowserDocumentById,
  openBrowserDocument,
  type BrowserDocument,
} from "./MediaService";

export type BlurredPreviewKind = "image" | "video" | "pdf" | "text" | "other";

export type CachedDocumentPreview = {
  kind: BlurredPreviewKind;
  document?: BrowserDocument;
  url?: string;
  text?: string;
};

const MAX_CACHE_ENTRIES = 48;
const MAX_PREVIEW_SIDE = 360;
const cache = new Map<string, CachedDocumentPreview>();
const pending = new Map<string, Promise<CachedDocumentPreview | null>>();
let activeGeneration = 0;
let activeJobs = 0;
const waiters: Array<() => void> = [];

function previewKey(uid: string, mediaId: string) {
  return `${uid}::${mediaId}`;
}

function revokePreviewUrls(entry: CachedDocumentPreview | undefined) {
  if (entry?.url && typeof URL !== "undefined" && typeof URL.revokeObjectURL === "function") {
    URL.revokeObjectURL(entry.url);
  }
}

function rememberPreview(key: string, entry: CachedDocumentPreview) {
  if (cache.has(key)) {
    const existing = cache.get(key);
    if (existing !== entry) revokePreviewUrls(existing);
    cache.delete(key);
  }
  cache.set(key, entry);
  while (cache.size > MAX_CACHE_ENTRIES) {
    const oldestKey = cache.keys().next().value;
    if (!oldestKey) break;
    revokePreviewUrls(cache.get(oldestKey));
    cache.delete(oldestKey);
  }
}

async function withConcurrencyLimit<T>(work: () => Promise<T>): Promise<T> {
  if (activeJobs >= 3) {
    await new Promise<void>((resolve) => waiters.push(resolve));
  }
  activeJobs++;
  try {
    return await work();
  } finally {
    activeJobs--;
    const next = waiters.shift();
    if (next) next();
  }
}

function classifyMime(mimeType: string, name: string): BlurredPreviewKind {
  const clean = mimeType.toLowerCase().split(";", 1)[0].trim();
  if (clean.startsWith("image/")) return "image";
  if (clean.startsWith("video/")) return "video";
  if (clean === "application/pdf" || name.toLowerCase().endsWith(".pdf")) return "pdf";
  if (clean.startsWith("text/") || name.toLowerCase().endsWith(".txt")) return "text";
  return "other";
}

async function downscaleImageBlob(sourceUrl: string): Promise<string> {
  if (typeof window === "undefined" || typeof Image === "undefined" || typeof document === "undefined") {
    return sourceUrl;
  }
  return new Promise<string>((resolve) => {
    const img = new Image();
    img.onload = () => {
      try {
        const longest = Math.max(img.naturalWidth || 1, img.naturalHeight || 1);
        const scale = Math.min(1, MAX_PREVIEW_SIDE / longest);
        const width = Math.max(1, Math.round((img.naturalWidth || 1) * scale));
        const height = Math.max(1, Math.round((img.naturalHeight || 1) * scale));
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const ctx = canvas.getContext("2d");
        if (!ctx || typeof canvas.toBlob !== "function") {
          resolve(sourceUrl);
          return;
        }
        ctx.drawImage(img, 0, 0, width, height);
        canvas.toBlob(
          (blob) => {
            if (blob) {
              URL.revokeObjectURL(sourceUrl);
              resolve(URL.createObjectURL(blob));
            } else {
              resolve(sourceUrl);
            }
          },
          "image/jpeg",
          0.72,
        );
      } catch {
        resolve(sourceUrl);
      }
    };
    img.onerror = () => resolve(sourceUrl);
    img.src = sourceUrl;
  });
}

async function captureVideoFrameBlob(sourceUrl: string): Promise<string | undefined> {
  if (typeof window === "undefined" || typeof document === "undefined") return undefined;
  return new Promise<string | undefined>((resolve) => {
    const video = document.createElement("video");
    video.muted = true;
    video.playsInline = true;
    video.preload = "metadata";
    let settled = false;
    const finish = (url?: string) => {
      if (settled) return;
      settled = true;
      window.clearTimeout(timeout);
      URL.revokeObjectURL(sourceUrl);
      video.removeAttribute("src");
      video.load();
      resolve(url);
    };
    const timeout = window.setTimeout(() => finish(undefined), 8000);
    video.onloadeddata = () => {
      try {
        const vw = video.videoWidth || 320;
        const vh = video.videoHeight || 240;
        const scale = Math.min(1, MAX_PREVIEW_SIDE / Math.max(vw, vh));
        const canvas = document.createElement("canvas");
        canvas.width = Math.max(1, Math.round(vw * scale));
        canvas.height = Math.max(1, Math.round(vh * scale));
        const ctx = canvas.getContext("2d");
        if (!ctx || typeof canvas.toBlob !== "function") {
          finish(undefined);
          return;
        }
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        canvas.toBlob((blob) => finish(blob ? URL.createObjectURL(blob) : undefined), "image/jpeg", 0.72);
      } catch {
        finish(undefined);
      }
    };
    video.onerror = () => finish(undefined);
    video.src = sourceUrl;
  });
}

async function renderBrowserPreview(browserDoc: BrowserDocument): Promise<CachedDocumentPreview> {
  const kind = classifyMime(browserDoc.mimeType, browserDoc.name);
  if (!browserDoc.approved || !browserDoc.key || kind === "other") {
    return { kind, document: browserDoc };
  }
  const objectUrl = await openBrowserDocument(browserDoc);
  if (kind === "image") {
    return { kind, document: browserDoc, url: await downscaleImageBlob(objectUrl) };
  }
  if (kind === "video") {
    const thumb = await captureVideoFrameBlob(objectUrl);
    return { kind, document: browserDoc, url: thumb };
  }
  if (kind === "pdf") {
    return { kind, document: browserDoc, url: objectUrl };
  }
  try {
    const text = (await (await fetch(objectUrl)).text()).slice(0, 480);
    return { kind: "text", document: browserDoc, text };
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

export function getCachedDocumentPreview(uid: string, mediaId: string): CachedDocumentPreview | undefined {
  return cache.get(previewKey(uid, mediaId));
}

export async function loadDocumentPreview(
  identity: Identity,
  browserDoc: BrowserDocument,
): Promise<CachedDocumentPreview | null> {
  const key = previewKey(identity.uid, browserDoc.id);
  const existing = cache.get(key);
  if (existing && (existing.url || existing.text || !browserDoc.approved)) {
    return existing;
  }
  const inFlight = pending.get(key);
  if (inFlight) return inFlight;
  const generation = activeGeneration;
  const task = withConcurrencyLimit(async () => {
    try {
      const rendered = await renderBrowserPreview(browserDoc);
      if (generation !== activeGeneration) {
        revokePreviewUrls(rendered);
        return null;
      }
      rememberPreview(key, rendered);
      return rendered;
    } catch {
      return null;
    } finally {
      pending.delete(key);
    }
  });
  pending.set(key, task);
  return task;
}

export async function loadDocumentPreviewById(
  identity: Identity,
  mediaId: string,
): Promise<CachedDocumentPreview | null> {
  const key = previewKey(identity.uid, mediaId);
  const existing = cache.get(key);
  if (existing) return existing;
  const inFlight = pending.get(key);
  if (inFlight) return inFlight;
  const generation = activeGeneration;
  const task = withConcurrencyLimit(async () => {
    try {
      const doc = await loadBrowserDocumentById(identity, mediaId);
      if (generation !== activeGeneration) return null;
      const rendered = await renderBrowserPreview(doc);
      if (generation !== activeGeneration) {
        revokePreviewUrls(rendered);
        return null;
      }
      rememberPreview(key, rendered);
      return rendered;
    } catch {
      return null;
    } finally {
      pending.delete(key);
    }
  });
  pending.set(key, task);
  return task;
}

export function clearDocumentPreviewCache() {
  activeGeneration++;
  for (const entry of cache.values()) revokePreviewUrls(entry);
  cache.clear();
  pending.clear();
}
