// All safe-app roles: pure TypeScript walkthrough catalog, multi-step tour targets, and non-overlapping placement.
import type { Page, Role } from "../../../domain/DomainModels";

export type WebWalkthroughCategory =
  | "privacy"
  | "tools"
  | "support"
  | "account";

export interface WebWalkthroughTopic {
  category: WebWalkthroughCategory;
  id: string;
  title: string;
  summary: string;
  steps: [string, string, string];
  targetPage: Page;
  targetLabel: string;
  tourPage: Page;
  userOnly?: boolean;
}

export interface WebWalkthroughTourStep {
  id: string;
  topicId: string;
  title: string;
  body: string;
  tourPage: Page;
  targetSelector?: string;
  action?: string;
  opensPopup?: boolean;
  userOnly?: boolean;
}

export interface WebCoachmarkRect {
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
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
  },
];

const ALL_TOUR_STEPS: WebWalkthroughTourStep[] = [
  {
    id: "home_sidebar_nav",
    topicId: "quick_exit",
    title: "Workspace sidebar navigation",
    body: "Use the sidebar links to switch between Safety plan, Notes, Documents, Messages, Location, Support, and Account tools.",
    tourPage: "Home",
    targetSelector: "#safe-navigation",
    userOnly: true,
  },
  {
    id: "home_dashboard_cards",
    topicId: "quick_exit",
    title: "Home shortcut cards",
    body: "Jump directly to your Safety plan, core tools, or support options from these Home dashboard shortcuts.",
    tourPage: "Home",
    targetSelector: ".safe-dashboard",
    userOnly: true,
  },
  {
    id: "quick_exit_fab",
    topicId: "quick_exit",
    title: "Quick Exit button",
    body: "Click Quick Exit in the bottom-right corner anytime to immediately lock your workspace and return to your cover site.",
    tourPage: "Home",
    targetSelector: ".floating-quick-exit",
    userOnly: true,
  },
  {
    id: "plan_primary_tab",
    topicId: "safety_plan",
    title: "Safety plan · Primary tab",
    body: "Review and customize your primary safety plan steps, emergency contacts, and checklists here.",
    tourPage: "SafetyPlan",
    targetSelector: "#primary-plan-tab, #main .panel",
    action: "plan_primary",
  },
  {
    id: "plan_section_jumps",
    topicId: "safety_plan",
    title: "Safety plan · Checklist jumps",
    body: "Use these checklist navigation links to jump straight to People, Contacts, Places, Documents, or Quick-grab bags.",
    tourPage: "SafetyPlan",
    targetSelector: ".safety-section-links, #main .panel",
    action: "plan_primary",
    userOnly: true,
  },
  {
    id: "plan_backup_tab",
    topicId: "safety_plan",
    title: "Safety plan · Backup tab",
    body: "We switched to Backup plan: build an alternate safety plan and grab-bag steps in case your primary plan changes.",
    tourPage: "SafetyPlan",
    targetSelector: "#backup-plan-tab, #main .panel",
    action: "plan_backup",
  },
  {
    id: "plan_consultation",
    topicId: "safety_plan",
    title: "Safety plan · Staff consultation",
    body: "Request a private staff consultation ticket here so an organization advocate can review your plan.",
    tourPage: "SafetyPlan",
    targetSelector: ".safety-plan-summary",
    userOnly: true,
  },
  {
    id: "plan_share_contacts",
    topicId: "safety_plan",
    title: "Safety plan · Share with safe contacts",
    body: "Choose which accepted trusted contacts can view your safety plan and update or revoke access anytime.",
    tourPage: "SafetyPlan",
    targetSelector: "[data-walkthrough-id='safety-share-contacts'], .safety-plan-summary",
    userOnly: true,
  },
  {
    id: "notes_toolbar_filter",
    topicId: "notes_incidents",
    title: "Notes · Search, filter & pin",
    body: "Search across entries, switch between All, Notes, and Incidents tabs, or pin important records to the top.",
    tourPage: "Notes",
    targetSelector: ".page-toolbar",
  },
  {
    id: "notes_editor_modal",
    topicId: "notes_incidents",
    title: "Incident editor · Date, time, map & attachments",
    body: "We opened an Incident editor popup: log dated events, choose an incident location on the map, and attach encrypted files.",
    tourPage: "Notes",
    targetSelector: "[data-walkthrough-id='active-modal']",
    action: "notes_open_incident_editor",
    opensPopup: true,
  },
  {
    id: "notes_trash_view",
    topicId: "notes_incidents",
    title: "Notes · Trash & edit history restore",
    body: "We opened Trash view: restore recently deleted entries before expiration, or inspect per-entry edit history on cards.",
    tourPage: "Notes",
    targetSelector: "[data-walkthrough-id='notes-trash-toggle']",
    action: "notes_open_trash",
  },
  {
    id: "docs_upload_controls",
    topicId: "documents",
    title: "Encrypted Document vault · Upload",
    body: "Upload PDFs, images, or files here; every file is encrypted in your browser before reaching cloud storage.",
    tourPage: "Documents",
    targetSelector: "[data-walkthrough-id='documents-upload'], #main .panel",
  },
  {
    id: "docs_vault_sharing",
    topicId: "documents",
    title: "Documents · Contact sharing & audio isolation",
    body: "Review uploaded documents below, mark Incident evidence, grant contact access, and keep chat voice notes chat-only.",
    tourPage: "Documents",
    targetSelector: "#main .safe-feature-page > .document-grid, #main .safe-feature-page > .empty",
  },
  {
    id: "chat_tabs_dms",
    topicId: "messaging_voice",
    title: "Messages · DMs & Ticket chats tabs",
    body: "Switch cleanly between Friends direct/group chats (DMs) and organization support conversations (Ticket chats).",
    tourPage: "Messages",
    targetSelector: ".message-tabs, #main .empty",
    action: "messages_tab_dms",
  },
  {
    id: "chat_invite_modal",
    topicId: "messaging_voice",
    title: "Messages · 48-hour Friend invite popup",
    body: "We opened the Invite popup: generate a 48-hour single-use code or shareable link to connect with someone safely.",
    tourPage: "Messages",
    targetSelector: "[data-walkthrough-id='active-modal'], #main .empty",
    action: "messages_open_invite",
    opensPopup: true,
  },
  {
    id: "chat_enter_code",
    topicId: "messaging_voice",
    title: "Messages · Enter an invite code",
    body: "Paste a friend’s invite code here or respond to incoming friend requests below to start encrypted chat.",
    tourPage: "Messages",
    targetSelector: "[data-walkthrough-id='messages-invite-code'], #main .empty",
    action: "messages_tab_dms",
  },
  {
    id: "chat_in_chat_tools",
    topicId: "messaging_voice",
    title: "Inside chat · Media, voice, search & unsend",
    body: "Open any thread to send encrypted text, chat-only voice notes, Documents/Notes/Plans/Location, search, or unsend.",
    tourPage: "Messages",
    targetSelector: ".chat-panel, .conversation-sidebar, #main .empty",
    action: "messages_tab_dms",
  },
  {
    id: "chat_tickets_tab",
    topicId: "messaging_voice",
    title: "Messages · Ticket chats & archives",
    body: "We switched to Ticket chats: open support tickets, manage invited friends on a ticket, or check archived chats.",
    tourPage: "Messages",
    targetSelector: ".message-tabs, #main .empty",
    action: "messages_tab_tickets",
  },
  {
    id: "location_lookup",
    topicId: "location",
    title: "Location · Current position check",
    body: "Trigger a browser location check anytime while open to inspect your current GPS accuracy without saving to disk.",
    tourPage: "Location",
    targetSelector: "#main .panel:first-child",
  },
  {
    id: "location_map_view",
    topicId: "location",
    title: "Location · Map preview & Android note",
    body: "Inspect your position on the map below; use the Android app for continuous background live-location sharing and routing.",
    tourPage: "Location",
    targetSelector: "#main .safe-map, #main .empty",
  },
  {
    id: "staff_request",
    topicId: "staff_help",
    title: "Request organization staff help",
    body: "Open a private support ticket here without exposing your safety plan; cancel anytime before an advocate claims it.",
    tourPage: "StaffHelp",
    targetSelector: "#main .safe-feature-page button, #main .panel",
    userOnly: true,
  },
  {
    id: "staff_ticket_chat",
    topicId: "staff_help",
    title: "Collaborate in your support ticket",
    body: "Once assigned, continue inside Friends & messages, share records selectively, and manage added friends on the ticket.",
    tourPage: "StaffHelp",
    targetSelector: "#main .safe-feature-page, #main .panel",
    userOnly: true,
  },
  {
    id: "hotlines_quick_links",
    topicId: "hotlines_contacts",
    title: "Hotlines · Immediate help shortcuts",
    body: "Dial 911 or 211 Canada directly or open Government of Canada and ShelterSafe national directories in a new tab.",
    tourPage: "Hotlines",
    targetSelector: "#main .panel:first-child",
    userOnly: true,
  },
  {
    id: "hotlines_filters",
    topicId: "hotlines_contacts",
    title: "Hotlines · Search & regional filters",
    body: "Filter verified Canadian crisis lines and shelters by province/territory, city, type, name, or phone number.",
    tourPage: "Hotlines",
    targetSelector: "#main .form-grid",
    userOnly: true,
  },
  {
    id: "contacts_add_modal",
    topicId: "hotlines_contacts",
    title: "Trusted contacts · Add contact popup",
    body: "We opened the Add contact popup: store up to 100 trusted contacts’ names and details securely inside Olive Branch.",
    tourPage: "Contacts",
    targetSelector: "[data-walkthrough-id='active-modal']",
    action: "contacts_open_add_modal",
    opensPopup: true,
    userOnly: true,
  },
  {
    id: "profile_overview",
    topicId: "activity_settings",
    title: "My profile · Personal & delivery details",
    body: "Edit your full name, birth date, optional email communication consent, situation notes, and city/address.",
    tourPage: "Profile",
    targetSelector: ".profile-form",
  },
  {
    id: "activity_filters",
    topicId: "activity_settings",
    title: "Account activity · Category & date filters",
    body: "Switch between Settings and Triggers logs and narrow events by optional From and To dates.",
    tourPage: "Activity",
    targetSelector: ".page-toolbar",
  },
  {
    id: "activity_log",
    topicId: "activity_settings",
    title: "Account activity · Security audit log",
    body: "Audit timestamped Settings changes, trigger checks, and account actions across web and Android.",
    tourPage: "Activity",
    targetSelector: ".activity-list",
  },
  {
    id: "settings_privacy_idle",
    topicId: "cover_entry",
    title: "Settings card · Idle auto-lock delay",
    body: "Choose how many minutes of inactivity automatically return to your cover site (also extendable from the lock warning).",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-privacy']",
    userOnly: true,
  },
  {
    id: "settings_entry_link",
    topicId: "cover_entry",
    title: "Settings card · Personal entry link",
    body: "Copy your personal bookmark entry link to jump back to your sign-in gate discreetly on any browser.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-entry-link']",
  },
  {
    id: "settings_entry_link_modal",
    topicId: "cover_entry",
    title: "Settings popup · Replace personal link",
    body: "We opened the Replacement link confirmation popup: rotating your link immediately invalidates the old URL.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='active-modal']",
    action: "settings_open_replace_link",
    opensPopup: true,
  },
  {
    id: "settings_password",
    topicId: "cover_entry",
    title: "Settings card · Account password",
    body: "Change your Olive Branch password securely after confirming your current password.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-password']",
  },
  {
    id: "settings_browser_pin",
    topicId: "cover_entry",
    title: "Settings card · Browser unlock PIN",
    body: "Set or rotate your 4–6 digit enrolled browser unlock PIN for quick local entry.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-browser-pin']",
    userOnly: true,
  },
  {
    id: "settings_android_trigger",
    topicId: "cover_entry",
    title: "Settings card · Android cover trigger",
    body: "Update the secret search phrase that unlocks Olive Branch from your Android cover app.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-android-trigger']",
    userOnly: true,
  },
  {
    id: "settings_account_sync",
    topicId: "cover_entry",
    title: "Settings card · Account sync & refresh",
    body: "Refresh synced profile, notes, contacts, and activity state from cloud storage anytime.",
    tourPage: "Settings",
    targetSelector: "[data-walkthrough-id='settings-account-sync']",
  },
];

export function computeWebCoachmarkPlacement({
  targetRect,
  viewportWidth,
  viewportHeight,
  cardWidth,
  cardHeight,
  safeTop = 16,
  safeBottom = viewportHeight - 84,
  margin = 16,
  gap = 12,
}: {
  targetRect: WebCoachmarkRect;
  viewportWidth: number;
  viewportHeight: number;
  cardWidth: number;
  cardHeight: number;
  safeTop?: number;
  safeBottom?: number;
  margin?: number;
  gap?: number;
}): { left: number; top: number } {
  const minLeft = margin;
  const maxLeft = Math.max(minLeft, viewportWidth - margin - cardWidth);
  const centerX = targetRect.left + targetRect.width / 2;
  const left = Math.min(maxLeft, Math.max(minLeft, centerX - cardWidth / 2));
  const maxTop = Math.max(safeTop, safeBottom - cardHeight);
  const targetCenterY = targetRect.top + targetRect.height / 2;
  const spaceBelow = safeBottom - targetRect.bottom;
  const spaceAbove = targetRect.top - safeTop;
  const preferBelow =
    spaceBelow >= cardHeight + gap &&
    (targetCenterY < viewportHeight * 0.55 || spaceBelow >= spaceAbove);
  let top: number;
  if (preferBelow) {
    top = Math.min(maxTop, Math.max(safeTop, targetRect.bottom + gap));
  } else if (spaceAbove >= cardHeight + gap) {
    top = Math.min(maxTop, Math.max(safeTop, targetRect.top - gap - cardHeight));
  } else if (spaceBelow >= spaceAbove) {
    top = Math.min(maxTop, Math.max(safeTop, targetRect.bottom + gap));
  } else {
    top = Math.min(maxTop, Math.max(safeTop, targetRect.top - gap - cardHeight));
  }
  return { left, top };
}

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
