// All safe-app roles: pure TypeScript walkthrough catalog, multi-step tour definitions, and helpers.
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

export interface WebWalkthroughTourStep {
  id: string;
  topicId: string;
  title: string;
  body: string;
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
    id: "quick_exit",
    title: "Home shortcuts & Quick Exit",
    summary:
      "Leave the private workspace in one click with Quick Exit or jump between workspaces via the sidebar.",
    steps: [
      "Click the floating Quick Exit button in the bottom-right corner at any time to lock your workspace and return to your cover.",
      "Avoid browser autocomplete or public shared computers whenever signing in.",
      "Configure your idle auto-lock minutes under Settings so inactivity returns to the cover automatically.",
    ],
    targetPage: "Home",
    targetLabel: "Home",
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
      "Create a Note for personal reminders or an Incident entry for dated events, locations, and attached files.",
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
      "Upload PDFs, images, or important ID scans — files are encrypted locally with your keyset before upload.",
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
    title: "Friends, messages, media & voice",
    summary:
      "Add friends or 48-hour invites, send encrypted DMs/group chats and chat-only voice notes, share media/notes/plans/location, and search or unsend.",
    steps: [
      "Add friends by username or create a 48-hour single-use invite code from Friends & messages.",
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
    title: "Account activity log",
    summary:
      "Audit timestamped settings changes, cover triggers, and failed PIN attempts across devices.",
    steps: [
      "Open Account activity to filter timestamped settings edits, cover trigger uses, and PIN checks.",
      "Use Settings and My profile to rotate passwords, trigger phrases, email consent, and browser PIN.",
      "Enroll a Passkey Account Recovery Key so encrypted documents and chats restore cleanly on a new browser.",
    ],
    targetPage: "Activity",
    targetLabel: "Account activity",
    tourPage: "Activity",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    category: "privacy",
    id: "cover_entry",
    title: "Settings, idle lock & recovery keys",
    summary:
      "Tour all Settings tools: idle auto-lock delay, personal entry link, password/trigger/PIN rotation, and Passkey recovery.",
    steps: [
      "Adjust your idle auto-lock timeout or rotate your personal bookmark entry link and password.",
      "Update your trigger phrase or browser PIN cleanly anytime.",
      "Enroll a Passkey Account Recovery Key to restore encrypted documents and chats on a new browser.",
    ],
    targetPage: "Settings",
    targetLabel: "Settings",
    tourPage: "Settings",
    anchor: "bottom-center",
    arrow: "up",
  },
];

const ALL_TOUR_STEPS: WebWalkthroughTourStep[] = [
  {
    id: "quick_exit_fab",
    topicId: "quick_exit",
    title: "Quick Exit button",
    body: "Click Quick Exit in the bottom-right corner anytime to immediately lock your workspace and return to your cover site.",
    tourPage: "Home",
    anchor: "bottom-right",
    arrow: "down-right",
    userOnly: true,
  },
  {
    id: "home_sidebar_nav",
    topicId: "quick_exit",
    title: "Workspace navigation & My profile",
    body: "Use the sidebar links or dashboard cards to switch between your private tools, My profile, and Account activity.",
    tourPage: "Home",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    id: "plan_checklists",
    topicId: "safety_plan",
    title: "Primary, backup & emergency checklists",
    body: "Review and customize your primary safety plan, backup steps, and emergency grab-bag checklist here.",
    tourPage: "SafetyPlan",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "plan_share_consult",
    topicId: "safety_plan",
    title: "Trusted contact sharing & consultations",
    body: "Choose which accepted trusted contacts can view your safety plan (revoke anytime) or request staff consultation.",
    tourPage: "SafetyPlan",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "notes_create",
    topicId: "notes_incidents",
    title: "Notes & dated Incident records",
    body: "Log private reminders as Notes or dated/timed Incidents with locations and encrypted file attachments.",
    tourPage: "Notes",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "notes_pin_search",
    topicId: "notes_incidents",
    title: "Pin, filter & search entries",
    body: "Pin key records to the top, switch between Notes and Incidents, or search keywords in real time.",
    tourPage: "Notes",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "notes_history_trash",
    topicId: "notes_incidents",
    title: "Edit history & trash restore",
    body: "Inspect per-entry edit history logs, share entries in chat, or restore deleted records from trash.",
    tourPage: "Notes",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "docs_vault",
    topicId: "documents",
    title: "Client-encrypted Document vault",
    body: "Upload PDFs, images, or files here; every file is encrypted in your browser before reaching cloud storage.",
    tourPage: "Documents",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "docs_share",
    topicId: "documents",
    title: "Contact sharing & chat-only audio isolation",
    body: "Grant or revoke access for accepted contacts per document; chat voice recordings stay isolated inside conversations.",
    tourPage: "Documents",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "chat_add_friends",
    topicId: "messaging_voice",
    title: "Friends & 48-hour invite codes",
    body: "Search trusted people by username or generate a 48-hour single-use invite code or link to connect safely.",
    tourPage: "Messages",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "chat_voice_groups",
    topicId: "messaging_voice",
    title: "Direct chats, groups & chat-only voice",
    body: "Exchange end-to-end encrypted text in DMs or group chats and send voice notes stored strictly within chats.",
    tourPage: "Messages",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "chat_share_items",
    topicId: "messaging_voice",
    title: "Share media, notes, plans & location",
    body: "Use chat attachment controls to send encrypted Documents, Notes/Incidents, Safety plans, or location cards.",
    tourPage: "Messages",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "chat_search_unsend_info",
    topicId: "messaging_voice",
    title: "Search messages, unsend & Chat Info",
    body: "Search across messages, unsend anything you sent, or open Chat details to inspect participants and shared files.",
    tourPage: "Messages",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "location_map",
    topicId: "location",
    title: "Interactive map & shared pins",
    body: "Inspect locations shared with you by trusted connections or run an explicit one-time position check here.",
    tourPage: "Location",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "location_mobile_note",
    topicId: "location",
    title: "Continuous live sharing on Android",
    body: "Use the Olive Branch Android app whenever you need continuous background live sharing or turn-by-turn routes.",
    tourPage: "Location",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "staff_request",
    topicId: "staff_help",
    title: "Request organization staff help",
    body: "Open a private support ticket here; you can cancel a pending request anytime before a staff advocate claims it.",
    tourPage: "StaffHelp",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    id: "staff_ticket_chat",
    topicId: "staff_help",
    title: "Collaborate in your support ticket",
    body: "Once assigned, continue in Friends & messages, share plans or notes, and optionally invite a friend into the ticket.",
    tourPage: "StaffHelp",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    id: "hotlines_directory",
    topicId: "hotlines_contacts",
    title: "Canadian crisis lines & shelters",
    body: "Filter 24/7 crisis lines, shelters, and legal aid across Canadian provinces and cities using your saved or current city.",
    tourPage: "Hotlines",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    id: "trusted_contacts",
    topicId: "hotlines_contacts",
    title: "Trusted safe-contact directory",
    body: "Store trusted friends' details, note who is safe to stay with in an emergency, and manage personal contacts.",
    tourPage: "Contacts",
    anchor: "bottom-center",
    arrow: "up",
    userOnly: true,
  },
  {
    id: "activity_audit",
    topicId: "activity_settings",
    title: "Account activity security log",
    body: "Review timestamped Settings edits, cover trigger uses, and PIN attempts to verify account security.",
    tourPage: "Activity",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "settings_idle_entry",
    topicId: "cover_entry",
    title: "Settings · Idle lock & personal entry link",
    body: "Choose how long before inactivity returns to your cover, copy or rotate your personal bookmark link, and update passwords.",
    tourPage: "Settings",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "settings_trigger_pin",
    topicId: "cover_entry",
    title: "Settings · Trigger phrase, cover & browser PIN",
    body: "Rotate your secret trigger phrase, cover preference, or 4-digit browser unlock PIN safely from Settings.",
    tourPage: "Settings",
    anchor: "bottom-center",
    arrow: "up",
  },
  {
    id: "settings_passkey",
    topicId: "cover_entry",
    title: "Settings · Passkey Account Recovery Key",
    body: "Enroll a Passkey Account Recovery Key so encrypted documents and chats restore cleanly if you sign in on another browser.",
    tourPage: "Settings",
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

export function walkthroughTourStepsForRole(role: Role | undefined): WebWalkthroughTourStep[] {
  if (role === "SAFE_CONTACT") {
    return ALL_TOUR_STEPS.filter((step) => !step.userOnly);
  }
  return ALL_TOUR_STEPS;
}

export function firstWalkthroughStepForTopic(
  role: Role | undefined,
  topicId: string,
): WebWalkthroughTourStep | undefined {
  return walkthroughTourStepsForRole(role).find((step) => step.topicId === topicId);
}

export function walkthroughStepCountForTopic(role: Role | undefined, topicId: string): number {
  return walkthroughTourStepsForRole(role).filter((step) => step.topicId === topicId).length;
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

export function nextWalkthroughTourStepId(
  steps: WebWalkthroughTourStep[],
  currentStepId: string,
  delta: number,
): string | null {
  const idx = steps.findIndex((step) => step.id === currentStepId);
  if (idx < 0) return null;
  const next = idx + delta;
  return next >= 0 && next < steps.length ? steps[next].id : null;
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
