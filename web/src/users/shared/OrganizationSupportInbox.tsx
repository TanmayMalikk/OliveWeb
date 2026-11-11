// Staff and admins: reads assigned support chats and sends encrypted replies.
import { useEffect, useMemo, useState } from "react";
import { Archive, MessageCircle, Send, ShieldAlert } from "lucide-react";
import { Button, Empty, Form } from "../../components/SharedComponents";
import { accounts, cloudMode } from "../../services/AccountService";
import * as messaging from "../../services/MessagingService";
import type { Conversation, Message } from "../../services/MessagingService";
import {
  closeSupportTicket,
  requestAdministratorHelp,
} from "../../services/OrganizationSupportService";
import { useSession } from "../../session/SessionProvider";
import { useChatDeviceKey, useChatTimeline } from "./useChatTimeline";

type TicketCategory = "PLAN_CONSULT" | "SUPPORT" | "NEEDS_ADMIN";
const categories: { id: TicketCategory; label: string }[] = [
  { id: "PLAN_CONSULT", label: "Plan Consults" },
  { id: "SUPPORT", label: "Support" },
  { id: "NEEDS_ADMIN", label: "Need Admins" },
];

// Gives old support records a safe category based on their original request.
function categoryOf(conversation: Conversation): TicketCategory {
  if (conversation.supportTag === "NEEDS_ADMIN") return "NEEDS_ADMIN";
  return conversation.supportOrigin === "PLAN_CONSULT"
    ? "PLAN_CONSULT"
    : "SUPPORT";
}

export default function OrganizationSupportInbox() {
  const session = useSession();
  const identity = accounts.identity;
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [roomKey, setRoomKey] = useState<CryptoKey | null>(null);
  const [draft, setDraft] = useState("");
  const [feedback, setFeedback] = useState("");
  const [category, setCategory] = useState<TicketCategory>("SUPPORT");
  const [showArchived, setShowArchived] = useState(false);
  const timeline = useChatTimeline(selected?.id, identity?.uid, messages);
  const deviceKeyAvailable = useChatDeviceKey(selected?.id, identity?.uid);

  // Keeps the inbox current while the organization user is signed in.
  useEffect(() => {
    if (!cloudMode || !identity) return;
    let active = true;
    let stop: (() => void) | undefined;
    void messaging.register(identity)
      .then(() => messaging.prepareSupportTickets())
      .then(() => {
        if (!active) return undefined;
        return messaging.subscribeConversations(
          identity.uid,
          (value) => {
            if (!active) return;
            const tickets = value.filter((item) => item.type === "SUPPORT");
            setConversations(tickets);
            setSelected((current) => tickets.find((ticket) => ticket.id === current?.id) ?? null);
          },
          (error) => active && setFeedback(error.message),
        );
      })
      .then((cleanup) => {
        if (!cleanup) return;
        if (active) stop = cleanup;
        else cleanup();
      })
      .catch((error) => active && setFeedback(error.message));
    return () => {
      active = false;
      stop?.();
    };
  }, [identity?.uid]);

  // Opens a selected ticket using its device-approved encryption key.
  useEffect(() => {
    setMessages([]);
    setRoomKey(null);
    if (!selected || !identity) return;
    let active = true;
    let stop: (() => void) | undefined;
    void messaging
      .roomKey(selected.id, identity, selected)
      .then((key) => {
        if (!active) return undefined;
        setRoomKey(key);
        return messaging.subscribeMessages(
          selected.id,
          key,
          (value) => active && setMessages(value),
          (error) => active && setFeedback(error.message),
        );
      })
      .then((cleanup) => {
        if (!cleanup) return;
        if (active) stop = cleanup;
        else cleanup();
      })
      .catch((error) => active && setFeedback(error.message));
    return () => {
      active = false;
      stop?.();
    };
  }, [selected?.id, selected?.participantIds.join(","), identity?.uid, deviceKeyAvailable]);

  const visible = useMemo(
    () =>
      conversations
        .filter((item) =>
          showArchived ? Boolean(item.closedAt) : !item.closedAt,
        )
        .filter((item) => showArchived || categoryOf(item) === category),
    [conversations, category, showArchived],
  );
  if (!identity) return null;
  if (!cloudMode)
    return (
      <Empty title="Support inbox needs a shared account.">
        Sign in through Firebase to load assigned tickets.
      </Empty>
    );

  return (
    <section
      className="organization-inbox"
      aria-label="Organization support inbox"
    >
      <aside className="organization-ticket-list">
        <div className="organization-inbox-heading">
          <div>
            <span className="eyebrow">SECURE SUPPORT</span>
            <h2>Ticket inbox</h2>
          </div>
          <Button
            variant="outline"
            onClick={() => setShowArchived((value) => !value)}
          >
            <Archive size={16} /> {showArchived ? "Active tickets" : "Archived"}
          </Button>
        </div>
        {!showArchived && (
          <div
            className="organization-ticket-tabs"
            role="tablist"
            aria-label="Ticket categories"
          >
            {categories.map((item) => (
              <button
                key={item.id}
                role="tab"
                aria-selected={category === item.id}
                onClick={() => setCategory(item.id)}
              >
                {item.label}
              </button>
            ))}
          </div>
        )}
        {visible.length === 0 ? (
          <Empty
            title={
              showArchived
                ? "No archived tickets."
                : "No tickets in this category."
            }
          >
            New conversations will appear here when assigned to your account.
          </Empty>
        ) : (
          visible.map((item) => (
            <button
              key={item.id}
              className={`organization-ticket ${selected?.id === item.id ? "selected" : ""}`}
              onClick={() => setSelected(item)}
            >
              <span className="organization-ticket-icon">
                <MessageCircle size={18} />
              </span>
              <span>
                <small>
                  {
                    categories.find((entry) => entry.id === categoryOf(item))
                      ?.label
                  }
                </small>
                <strong>{messaging.conversationName(item, identity)}</strong>
                <span>{item.lastMessage || "New conversation"}</span>
              </span>
            </button>
          ))
        )}
      </aside>

      <section
        className="organization-ticket-chat"
        aria-label="Selected support conversation"
      >
        {feedback && (
          <p role="status" className="form-error message-feedback">
            {feedback}
          </p>
        )}
        {!selected ? (
          <Empty title="Choose a support ticket.">
            The encrypted conversation opens here.
          </Empty>
        ) : (
          <>
            <header className="organization-chat-heading">
              <div>
                <span className="eyebrow">
                  {
                    categories.find(
                      (entry) => entry.id === categoryOf(selected),
                    )?.label
                  }
                </span>
                <h2>{messaging.conversationName(selected, identity)}</h2>
              </div>
              <span>{selected.closedAt ? "Archived" : "Active"}</span>
            </header>
            {identity.role === "STAFF" &&
              !selected.closedAt &&
              categoryOf(selected) !== "NEEDS_ADMIN" && (
                <div className="organization-ticket-actions">
                  <Button
                    variant="outline"
                    disabled={session.busy}
                    onClick={() =>
                      void session.perform(async () => {
                        await requestAdministratorHelp(selected.id);
                        setCategory("NEEDS_ADMIN");
                      })
                    }
                  >
                    <ShieldAlert size={16} /> Request admin help
                  </Button>
                  <Button
                    variant="quiet"
                    disabled={session.busy}
                    onClick={() =>
                      void session.perform(async () => {
                        await closeSupportTicket(selected.id);
                        setSelected(null);
                      })
                    }
                  >
                    <Archive size={16} /> Close for everyone
                  </Button>
                </div>
              )}
            <div
              className="chat-messages"
              role="log"
              aria-label="Support messages"
              aria-live="polite"
            >
              {timeline.length === 0 ? (
                <p className="help-note">No messages yet.</p>
              ) : (
                timeline.map((item) => item.kind === "membership" ? (
                  <p key={`membership-${item.event.id}`} className="chat-membership-event">
                    {messaging.membershipEventText(selected, item.event, identity.role)}
                  </p>
                ) : (
                  <article
                    key={item.message.id}
                    className={`chat-bubble ${item.message.senderUid === identity.uid ? "own" : ""}`}
                  >
                    <small>{messaging.chatSenderName(selected, item.message, identity.role)}</small>
                    <p>{item.message.deleted ? "Message unsent" : item.message.body}</p>
                    <time>
                      {new Date(item.message.clientSentAt).toLocaleString()}
                    </time>
                  </article>
                ))
              )}
            </div>
            {!selected.closedAt && (
              <Form
                className="chat-compose"
                onSubmit={async () =>
                  session.perform(async () => {
                    if (!roomKey)
                      throw new Error(
                        "Open this conversation on an approved device before sending messages.",
                      );
                    await messaging.sendMessage(
                      selected.id,
                      identity,
                      draft,
                      roomKey,
                    );
                    setDraft("");
                  })
                }
              >
                <label className="sr-only" htmlFor="organization-message">
                  Write a secure reply
                </label>
                <textarea
                  id="organization-message"
                  value={draft}
                  maxLength={2000}
                  onChange={(event) => setDraft(event.target.value)}
                  placeholder="Write a secure reply…"
                  required
                />
                <Button
                  type="submit"
                  disabled={session.busy || !draft.trim() || !roomKey}
                >
                  <Send size={18} />
                  <span className="sr-only">Send reply</span>
                </Button>
              </Form>
            )}
          </>
        )}
      </section>
    </section>
  );
}
