// All app roles: defines web app account, cover, contact, and message data.
import { coverSite } from "../sites/SiteRouting";
export type Cover = "FOOD" | "NEWS" | "FITNESS" | "GAME";
export type Role = "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
export type Page =
  | "Home"
  | "Notes"
  | "Messages"
  | "SafetyPlan"
  | "Contacts"
  | "Profile"
  | "Settings"
  | "Activity"
  | "Hotlines"
  | "StaffHelp"
  | "Documents"
  | "Camera"
  | "Location";
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
    id: "FITNESS",
    slug: "fitness",
    name: "Fitness Tracker",
    description: "Move with purpose.",
    caption: "Activity, nutrition, and progress in one place.",
  },
  {
    id: "GAME",
    slug: "game",
    name: "Game",
    description: "A moment to yourself.",
    caption: "A quieter corner. Find a matching pair.",
  },
];
// Cover apps: converts removed or damaged saved cover values to a supported cover.
export function normalizeCover(value: unknown): Cover {
  if (value === "CALCULATOR") return "FITNESS";
  return covers.some((cover) => cover.id === value) ? (value as Cover) : "GAME";
}
// Web domain models: Finds the selected cover details. It normalizes the cover name and finds the matching catalog entry.
export const coverInfo = (id: Cover) =>
  covers.find((cover) => cover.id === normalizeCover(id)) ?? covers[3];
// Web domain models: Builds the web path for the selected cover. It normalizes the cover name and builds its matching site path.
export const coverPath = (id: Cover) => coverSite(normalizeCover(id)).path;
// Account sign-in: Normalizes a username for reliable matching. It removes outside spaces and converts every letter to lowercase.
export const normalizeUsername = (v: string) => v.trim().toLowerCase();
// Account sign-in: Validates a username. It requires 8–24 lowercase letters, numbers, or underscores and at least one letter.
export const validUsername = (v: string) =>
  /^(?=.*[a-z])[a-z0-9_]{8,24}$/.test(normalizeUsername(v));
// Account sign-in: Validates a password. It checks the length and requires a letter, number, and special character.
export const validPassword = (v: string) =>
  v.length >= 8 &&
  v.length <= 24 &&
  /[\p{L}]/u.test(v) &&
  /\d/.test(v) &&
  /[^\p{L}\p{N}\s]/u.test(v);
// Private entry: Validates an app PIN. It accepts only four to six digits.
export const validPin = (v: string) => /^\d{4,6}$/.test(v);
// Web domain models: Converts a username into its sign-in email. It accepts an email as-is or converts a username to the account email format.
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
  coverType: "GAME",
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
// Notes trash: Permanently clears an expired deleted note. It compares the deletion time with the retention limit while keeping newer trash recoverable.
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
// Web domain models: Turns a technical error into a readable message. It checks the error prefix and selects a plain-language explanation.
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
