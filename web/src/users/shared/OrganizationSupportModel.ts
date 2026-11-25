// Staff and admins: filters support inbox categories by organization role.
import type { Role } from "../../domain/DomainModels";
import type { Conversation } from "../../services/MessagingService";

export type TicketCategory = "PLAN_CONSULT" | "SUPPORT" | "NEEDS_ADMIN";

export const allTicketCategories: { id: TicketCategory; label: string }[] = [
  { id: "PLAN_CONSULT", label: "Plan Consults" },
  { id: "SUPPORT", label: "Support" },
  { id: "NEEDS_ADMIN", label: "Need Admins" },
];

export function categoryOfTicket(conversation: Pick<Conversation, "supportTag" | "supportOrigin">): TicketCategory {
  if (conversation.supportTag === "NEEDS_ADMIN") return "NEEDS_ADMIN";
  return conversation.supportOrigin === "PLAN_CONSULT" ? "PLAN_CONSULT" : "SUPPORT";
}

export function visibleCategoriesForRole(role?: Role): { id: TicketCategory; label: string }[] {
  return role === "ADMIN"
    ? allTicketCategories.filter((item) => item.id === "NEEDS_ADMIN")
    : allTicketCategories;
}

export function roleConversationsForInbox<
  T extends Pick<Conversation, "supportTag" | "supportOrigin" | "closedAt">,
>(conversations: T[], role?: Role): T[] {
  return role === "ADMIN"
    ? conversations.filter((item) => categoryOfTicket(item) === "NEEDS_ADMIN")
    : conversations;
}

export function filterSupportTickets<
  T extends Pick<Conversation, "supportTag" | "supportOrigin" | "closedAt">,
>(
  conversations: T[],
  role: Role | undefined,
  category: TicketCategory,
  showArchived: boolean,
): T[] {
  const roleTickets = roleConversationsForInbox(conversations, role);
  const activeCategory: TicketCategory = role === "ADMIN" ? "NEEDS_ADMIN" : category;
  return roleTickets
    .filter((item) => (showArchived ? Boolean(item.closedAt) : !item.closedAt))
    .filter((item) => showArchived || categoryOfTicket(item) === activeCategory);
}
