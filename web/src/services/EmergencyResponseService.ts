// Admins: observes emergency alerts and live locations, opens encrypted report PDFs, and resolves or reopens requests.
import { collection, onSnapshot } from "firebase/firestore";
import { onValue, ref } from "firebase/database";
import { firebase } from "./AccountService";
import { loadBrowserDocumentById, openBrowserDocument, type BrowserDocument } from "./MediaService";
import type { Identity } from "../domain/DomainModels";

export interface EmergencyReport {
  id: string;
  ownerUid: string;
  ownerName: string;
  createdAt: number;
  mediaId: string;
  status: string;
  resolvedAt: number;
  resolvedBy: string;
}

export interface AdminLiveLocation {
  uid: string;
  name: string;
  latitude: number;
  longitude: number;
  accuracy: number | null;
  updatedAt: number;
  active: boolean;
}

function toMillis(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  const candidate = value as { toMillis?: () => number } | null | undefined;
  return typeof candidate?.toMillis === "function" ? candidate.toMillis() : 0;
}

export function parseEmergencyReport(
  id: string,
  data: Record<string, unknown> | undefined | null,
): EmergencyReport | null {
  if (!data) return null;
  const ownerUid = typeof data.ownerUid === "string" ? data.ownerUid.trim() : "";
  if (!ownerUid) return null;
  const ownerName =
    typeof data.ownerName === "string" && data.ownerName.trim()
      ? data.ownerName.trim()
      : ownerUid;
  const mediaId = typeof data.mediaId === "string" ? data.mediaId.trim() : "";
  const status = typeof data.status === "string" ? data.status.trim() : "";
  const resolvedBy = typeof data.resolvedBy === "string" ? data.resolvedBy.trim() : "";
  return {
    id,
    ownerUid,
    ownerName,
    createdAt: toMillis(data.createdAt),
    mediaId,
    status,
    resolvedAt: toMillis(data.resolvedAt),
    resolvedBy,
  };
}

export function parseAdminLiveLocation(
  uid: string,
  raw: unknown,
): AdminLiveLocation | null {
  if (!uid || !raw || typeof raw !== "object" || Array.isArray(raw)) return null;
  const value = raw as Record<string, unknown>;
  const latitude = typeof value.latitude === "number" ? value.latitude : Number.NaN;
  const longitude = typeof value.longitude === "number" ? value.longitude : Number.NaN;
  if (
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    latitude < -90 ||
    latitude > 90 ||
    longitude < -180 ||
    longitude > 180
  ) {
    return null;
  }
  const updatedAt = typeof value.updatedAt === "number" && Number.isFinite(value.updatedAt) ? value.updatedAt : 0;
  if (!updatedAt) return null;
  const name =
    typeof value.name === "string" && value.name.trim() ? value.name.trim() : uid;
  const accuracy =
    typeof value.accuracy === "number" && Number.isFinite(value.accuracy)
      ? value.accuracy
      : null;
  return {
    uid,
    name,
    latitude,
    longitude,
    accuracy,
    updatedAt,
    active: value.active === true,
  };
}

export async function observeEmergencyReports(
  onUpdate: (reports: EmergencyReport[]) => void,
  onError: (message: string) => void,
): Promise<() => void> {
  const { db } = await firebase();
  return onSnapshot(
    collection(db, "emergencyReports"),
    (snapshot) => {
      const reports = snapshot.docs
        .map((doc) => parseEmergencyReport(doc.id, doc.data()))
        .filter((item): item is EmergencyReport => item !== null)
        .sort((a, b) => b.createdAt - a.createdAt);
      onUpdate(reports);
    },
    (error) => {
      onError(error instanceof Error ? error.message : "Unable to load emergency requests.");
    },
  );
}

export async function observeAdminLiveLocations(
  onUpdate: (locations: Record<string, AdminLiveLocation>) => void,
  onError: (message: string) => void,
): Promise<() => void> {
  const { rtdb } = await firebase();
  return onValue(
    ref(rtdb, "adminLiveLocations"),
    (snapshot) => {
      const next: Record<string, AdminLiveLocation> = {};
      const val = snapshot.val() as Record<string, unknown> | null;
      if (val && typeof val === "object") {
        for (const [uid, entry] of Object.entries(val)) {
          const parsed = parseAdminLiveLocation(uid, entry);
          if (parsed) next[uid] = parsed;
        }
      }
      onUpdate(next);
    },
    (error) => {
      onError(error instanceof Error ? error.message : "Unable to observe live emergency locations.");
    },
  );
}

export async function setEmergencyResolved(id: string, resolved: boolean): Promise<void> {
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  await httpsCallable(functions, resolved ? "emergencyResolve" : "emergencyReopen")({ id });
}

export async function openEmergencyReportPdf(
  identity: Identity,
  mediaId: string,
): Promise<{ document: BrowserDocument; url: string }> {
  const document = await loadBrowserDocumentById(identity, mediaId);
  const url = await openBrowserDocument(document);
  return { document, url };
}
