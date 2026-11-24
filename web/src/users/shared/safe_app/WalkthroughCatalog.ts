// All safe-app roles: pure TypeScript walkthrough catalog, categories, tour anchors, and navigation helpers.
import type { Page, Role } from "../../../domain/DomainModels";

export type WebWalkthroughCategory =
  | "privacy"
  | "tools"
  | "support"
  | "account";

export type WebWalkthroughAnchor = "bottom-right" | "bottom-center";
export type WebWalkthroughArrow = "down-right" | "up";

export interface WebWalkthroughTopic {
  category: WebWalkthroughCategory;
  id: string;
  title: string;
  summary: string;
  steps: [string, string, string];
  targetPage: Page;
  targetLabel: string;
  tourPage: Page;
  anchor: WebWalkthroughAnchor;
  arrow: WebWalkthroughArrow;
  userOnly?: boolean;
}

export interface WebWalkthroughCategoryMeta {
  id: WebWalkthroughCategory;
  label: string;
}

export const WALKTHROUGH_CATEGORIES: WebWalkthroughCategoryMeta[] = [
  { id: "privacy", label: "Privacy & entry" },
  { id: "tools", label: "Core safety tools" },
  { id: "support", label: "Support & contacts" },
  { id: "account", label: "Account & security" },
];

const ALL_TOPICS: WebWalkthroughTopic[] = [
  {
    category: "privacy",
    id: "cover_entry",
    title: "Cover website & private entry",
    summary:
      "Use the calm public cover site and enter your private workspace using your trigger phrase, personal bookmark link, and optional browser PIN.",
    steps: [
      "Keep a disguise theme active on the cover site and enter from your trigger phrase or personal entry link in Settings.",
      "Open private entry from the cover search trigger, footer entry, or your bookmarked personal link.",
      "Unlock with your account credentials and verify your 4-digit browser PIN when prompted.",
    ],
    targetPage: "Settings",
    targetLabel: "Settings",
    tourPage: "Settings",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    category: "privacy",
    id: "quick_exit",
    title: "Quick Exit & idle protection",
    summary:
      "Leave the private workspace in one click or configure automatic lock timeouts if a browser tab stays unattended.",
    steps: [
      "Click the floating Quick Exit button in the bottom-right corner at any time to lock your workspace and return to your cover.",
      "Avoid browser autocomplete or public shared computers whenever signing in.",
      "Configure your idle auto-lock minutes under Settings so inactivity returns to the cover automatically.",
    ],
    targetPage: "Settings",
    targetLabel: "Settings",
    tourPage: "Home",
    anchor: "bottom-right",
    arrow: "down-right",
    userOnly: true,
  },
  {
    category: "tools",
    id: "safety_plan",
    title: "Safety plan",
    summary:
      "Build primary and backup safety steps, emergency grab-bag checklists, and choose which accepted trusted contacts can view your plan.",
    steps: [
      "Review and edit your primary plan, backup steps, and emergency checklist here, then choose accepted contacts to share with.",
      "Share your plan selectively with accepted trusted contacts, and revoke visibility whenever needed.",
      "Open a Staff help ticket anytime you want an organization advocate to review your safety steps.",
    ],
    targetPage: "SafetyPlan",
    targetLabel: "Safety plan",
    tourPage: "SafetyPlan",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "tools",
    id: "notes_incidents",
    title: "Notes & incident records",
    summary:
      "Record private notes or dated incident logs with location details, attachments, pinning, edit history, and soft-delete restore.",
    steps: [
      "Create Notes or dated Incidents, pin important entries, inspect edit history, or restore deleted records from trash.",
      "Pin critical records to stay at the top and filter or search entries quickly.",
      "Inspect the per-entry edit history drawer, share an entry in chat, or restore items from trash.",
    ],
    targetPage: "Notes",
    targetLabel: "Notes & incidents",
    tourPage: "Notes",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "tools",
    id: "documents",
    title: "Encrypted documents vault",
    summary:
      "Store client-encrypted PDFs and photos in the browser, review scanned metadata, and share selected files with accepted contacts.",
    steps: [
      "Upload PDFs or images here — files are encrypted in your browser before upload and shared only with chosen contacts.",
      "Toggle access per document so only selected accepted trusted contacts can decrypt and open it.",
      "Chat voice recordings stay inside conversations and never clutter your permanent Document vault.",
    ],
    targetPage: "Documents",
    targetLabel: "Documents",
    tourPage: "Documents",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "tools",
    id: "messaging_voice",
    title: "Friends, messages & chat-only voice",
    summary:
      "Connect with trusted people via friend requests or 48-hour invite codes and exchange end-to-end encrypted chats.",
    steps: [
      "Add trusted people via username or 48-hour invite codes and exchange encrypted messages, notes, or chat-only voice.",
      "Send encrypted messages, attachments, notes, safety plans, or short chat-only voice recordings.",
      "Search messages, unsend anything you sent, or open chat details to review shared items.",
    ],
    targetPage: "Messages",
    targetLabel: "Friends & messages",
    tourPage: "Messages",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "tools",
    id: "location",
    title: "Location & sharing",
    summary:
      "Check your current coordinates on the map when needed and view live locations shared with you by trusted connections.",
    steps: [
      "View locations shared with you on the interactive map above or run a one-time position lookup when needed.",
      "Inspect any live locations currently shared with you by trusted friends or family.",
      "Use the Olive Branch Android app whenever you need continuous background live-location sharing.",
    ],
    targetPage: "Location",
    targetLabel: "Location",
    tourPage: "Location",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "support",
    id: "staff_help",
    title: "Staff help & support chats",
    summary:
      "Request one-on-one support from organization advocates in a ticketed conversation or invite a trusted friend into the ticket.",
    steps: [
      "Use Request help from staff here to connect with an available advocate or monitor and cancel an open support ticket.",
      "Cancel a pending request anytime before assignment, or wait for a staff member to claim the ticket.",
      "Collaborate with your assigned staff advocate inside Friends & messages and share plans as needed.",
    ],
    targetPage: "StaffHelp",
    targetLabel: "Staff help",
    tourPage: "StaffHelp",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    category: "support",
    id: "hotlines_contacts",
    title: "Hotlines & trusted contacts",
    summary:
      "Filter Canadian crisis lines, shelters, and legal clinics by province or city, and organize your personal support network.",
    steps: [
      "Filter Canadian crisis hotlines, shelters, and legal aid by region here, and organize Trusted contacts in the sidebar.",
      "Check nearby resources using your saved city or an explicit browser location lookup without saving.",
      "Open Trusted contacts to store relationship notes and mark who is safe to stay with.",
    ],
    targetPage: "Hotlines",
    targetLabel: "Hotlines",
    tourPage: "Hotlines",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    category: "account",
    id: "activity_settings",
    title: "Account activity, passkeys & settings",
    summary:
      "Audit settings changes and trigger history, update privacy controls, and enroll a Passkey Account Recovery Key.",
    steps: [
      "Inspect timestamped settings edits, triggers, and failed PIN attempts here, or enroll a Passkey recovery key in Settings.",
      "Use Settings and My profile to rotate passwords, trigger phrases, email consent, and browser PIN.",
      "Enroll a Passkey Account Recovery Key so encrypted documents and chats restore cleanly on a new browser.",
    ],
    targetPage: "Activity",
    targetLabel: "Account activity",
    tourPage: "Activity",
    anchor: "bottom-center",
    arrow: "up",
  },
];

export function walkthroughTopicsForRole(role: Role | undefined): WebWalkthroughTopic[] {
  if (role === "SAFE_CONTACT") {
    return ALL_TOPICS.filter((topic) => !topic.userOnly);
  }
  return ALL_TOPICS;
}

export function walkthroughTopicGroups(
  topics: WebWalkthroughTopic[],
): { category: WebWalkthroughCategoryMeta; topics: WebWalkthroughTopic[] }[] {
  return WALKTHROUGH_CATEGORIES.map((category) => ({
    category,
    topics: topics.filter((topic) => topic.category === category.id),
  })).filter((group) => group.topics.length > 0);
}

export function clampWalkthroughStep(stepIndex: number, totalSteps: number): number {
  if (totalSteps <= 1) return 0;
  return Math.max(0, Math.min(totalSteps - 1, stepIndex));
}

export function nextWalkthroughTopicIndex(
  currentIndex: number,
  totalTopics: number,
  delta: number,
): number {
  if (totalTopics <= 1) return 0;
  return Math.max(0, Math.min(totalTopics - 1, currentIndex + delta));
}

export function nextWalkthroughTourTopicId(
  topics: WebWalkthroughTopic[],
  currentId: string,
  delta: number,
): string | null {
  const idx = topics.findIndex((topic) => topic.id === currentId);
  if (idx < 0) return null;
  const next = idx + delta;
  return next >= 0 && next < topics.length ? topics[next].id : null;
}
