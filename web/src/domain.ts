import { coverSite } from "./sites";
export type Cover = "FOOD" | "NEWS" | "CALCULATOR" | "GAME";
export type Role = "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
export type Page =
  | "Home"
  | "Notes"
  | "Messages"
  | "Contacts"
  | "Profile"
  | "Settings"
  | "Activity";
export interface Address {
  line1: string;
  line2: string;
  city: string;
  province: string;
  postalCode: string;
  country: string;
}
export interface Contact {
  id: string;
  name: string;
  details: string;
}
export interface Profile {
  username: string;
  fullName: string;
  affectedNames: string;
  abuserName: string;
  dob: string;
  language: string;
  situation: string;
  region: string;
  triggerHash: string;
  contacts: Contact[];
  address: Address;
  coverType: Cover;
  role: Role;
  email: string;
  phone: string;
}
export interface Entry {
  id: string;
  timestamp: number;
  updatedAt: number;
  kind: "Note" | "Incident" | null;
  title: string;
  body: string;
  pinned: boolean;
  edits: {
    id: string;
    timestamp: number;
    changes: { field: string; before: string; after: string }[];
  }[];
  deletedAt?: number;
  purgedAt?: number;
}
export type Category =
  "settings" | "triggers" | "checkouts" | "private_entries";
export interface Event {
  id: string;
  timestamp: number;
  area?: string;
  action?: string;
  source?: string;
  pinAttempts?: number;
  lastResult?: string;
  changes?: { field: string; before: string; after: string }[];
  foods?: { food: string; quantity: number }[];
  totalCents?: number;
  error?: string;
}
export interface History {
  settings: Event[];
  triggers: Event[];
  checkouts: Event[];
  private_entries: Entry[];
}
export interface Identity {
  uid: string;
  username: string;
  role: Role;
}
export interface Envelope {
  iv: string;
  data: string;
}
export interface WrappedKey {
  salt: string;
  box: Envelope;
}
export interface Enrollment {
  uid: string;
  username: string;
  cover: Cover;
  role: Role;
  triggerHash: string;
  pinKey: WrappedKey;
  failures: number;
}
export interface NewsArticle {
  id: string;
  title: string;
  summary: string;
  link: string;
  source: string;
  published: string;
  topic: string;
  imageUrl?: string;
  isPaywalled?: boolean;
}
export const covers: {
  id: Cover;
  slug: string;
  name: string;
  description: string;
  caption: string;
}[] = [
  {
    id: "FOOD",
    slug: "olive",
    name: "Olive",
    description: "Good food. Good mood.",
    caption: "A fresh take on your everyday menu.",
  },
  {
    id: "NEWS",
    slug: "daily-digest",
    name: "Daily Digest",
    description: "A little perspective.",
    caption: "Your world, thoughtfully put together.",
  },
  {
    id: "CALCULATOR",
    slug: "calculator",
    name: "Better Calculator",
    description: "Make room for clarity.",
    caption: "Everyday numbers. A few more possibilities.",
  },
  {
    id: "GAME",
    slug: "game",
    name: "Game",
    description: "A moment to yourself.",
    caption: "A quieter corner. Find a matching pair.",
  },
];
export const coverInfo = (id: Cover) => covers.find((c) => c.id === id)!;
export const coverPath = (id: Cover) => coverSite(id).path;
export const normalizeUsername = (v: string) => v.trim().toLowerCase();
export const validUsername = (v: string) =>
  /^[a-z0-9_]{8,24}$/.test(normalizeUsername(v));
export const validPassword = (v: string) =>
  v.length >= 8 &&
  v.length <= 24 &&
  /[\p{L}]/u.test(v) &&
  /\d/.test(v) &&
  /[^\p{L}\p{N}\s]/u.test(v);
export const validPin = (v: string) => /^\d{4,6}$/.test(v);
export function signInEmail(username: string, role: Role) {
  if ((role === "STAFF" || role === "ADMIN") && username.includes("@"))
    return username.trim().toLowerCase();
  return `${normalizeUsername(username)}@${{ USER: "users", SAFE_CONTACT: "safe", STAFF: "staff", ADMIN: "admin" }[role]}.olive.invalid`;
}
export const emptyHistory = (): History => ({
  settings: [],
  triggers: [],
  checkouts: [],
  private_entries: [],
});
export const emptyProfile = (
  username: string,
  role: Role = "USER",
): Profile => ({
  username,
  role,
  fullName: "",
  affectedNames: "",
  abuserName: "",
  dob: "",
  language: "English",
  situation: "",
  region: "",
  triggerHash: "",
  contacts: [],
  address: {
    line1: "",
    line2: "",
    city: "",
    province: "",
    postalCode: "",
    country: "Canada",
  },
  coverType: "FOOD",
  email: "",
  phone: "",
});
export const newEvent = (action: string, area = "Account"): Event => ({
  id: crypto.randomUUID(),
  timestamp: Date.now(),
  area,
  action,
  changes: [],
});
export const TRASH_RETENTION_MS = 10_000; // Matches the mobile project's current ten-second test retention.
export function purgeEntry(entry: Entry, now = Date.now()): Entry {
  return entry.deletedAt && now - entry.deletedAt >= TRASH_RETENTION_MS
    ? {
        ...entry,
        body: "",
        title: "Deleted entry",
        edits: [],
        purgedAt: entry.purgedAt ?? now,
      }
    : entry;
}
export function friendlyError(error: unknown): string {
  const code = (error as { code?: string })?.code;
  if (code?.startsWith("auth/"))
    return (
      (
        {
          "auth/email-already-in-use": "That username is unavailable.",
          "auth/invalid-credential":
            "Sign-in failed. Check your account type, username, and password.",
          "auth/too-many-requests": "Too many requests. Try again later.",
          "auth/network-request-failed":
            "Unable to connect. Check your connection.",
        } as Record<string, string>
      )[code] ?? "Unable to sign in. Check your details and try again."
    );
  return error instanceof Error
    ? error.message
    : "Something went wrong. Please try again.";
}
