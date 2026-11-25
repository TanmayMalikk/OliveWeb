// Staff and admins: reads assigned support chats and sends encrypted replies.
import { useEffect, useMemo, useRef, useState } from "react";
import {
  Archive,
  ArrowLeft,
  FileLock2,
  FileText,
  MapPin,
  MessageCircle,
  Send,
  ShieldAlert,
  ShieldCheck,
} from "lucide-react";
import { Button, Empty, Form, Modal } from "../../components/SharedComponents";
import { accounts, cloudMode } from "../../services/AccountService";
import * as messaging from "../../services/MessagingService";
import type { Conversation, Message } from "../../services/MessagingService";
import {
  loadBrowserDocumentById,
  openBrowserDocument,
  type BrowserDocument,
} from "../../services/MediaService";
import { loadSharedPrivateEntry } from "../../services/SharedPrivateEntryService";
import type { Entry } from "../../domain/DomainModels";
import {
  closeSupportTicket,
  requestAdministratorHelp,
} from "../../services/OrganizationSupportService";
import { useSession } from "../../session/SessionProvider";
import { useChatDeviceKey, useChatTimeline } from "./useChatTimeline";

import { sharedMessage } from "./safe_app/SharedMessageContent";
import { SafeMediaViewer } from "./safe_app/SafeMediaViewer";
import { VoiceMessageBubble, VoiceMessageComposer } from "./safe_app/VoiceMessages";
import {
  BlurredDocumentPreview,
  NoteAttachmentPreviewGrid,
} from "../../components/BlurredDocumentPreview";
import {
  allTicketCategories as categories,
  categoryOfTicket as categoryOf,
  filterSupportTickets,
  roleConversationsForInbox,
  visibleCategoriesForRole,
  type TicketCategory,
} from "./OrganizationSupportModel";

const noMessages: Message[] = [];

export default function OrganizationSupportInbox() {
  const session = useSession();
  const identity = accounts.identity;
  const isAdmin = identity?.role === "ADMIN";
  const visibleCategories = useMemo(
    () => visibleCategoriesForRole(identity?.role),
    [identity?.role],
  );
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [selected, setSelected] = useState<Conversation | null>(null);
  const [history, setHistory] = useState<{ ticketId: string; messages: Message[] } | null>(null);
  const messages = history && history.ticketId === selected?.id ? history.messages : noMessages;
  const roomScope = selected ? JSON.stringify([identity?.uid, selected.id, selected.participantIds]) : "";
  const [room, setRoom] = useState<{ scope: string; key: CryptoKey } | null>(null);
  const roomKey = room?.scope === roomScope ? room.key : null;
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const draft = selected ? drafts[selected.id] ?? "" : "";
  const [feedback, setFeedback] = useState("");
  const [viewerDocument, setViewerDocument] = useState<BrowserDocument | null>(null);
  const [viewerUrl, setViewerUrl] = useState("");
  const [viewerEntry, setViewerEntry] = useState<Entry | null>(null);
  const [category, setCategory] = useState<TicketCategory>(() =>
    identity?.role === "ADMIN" ? "NEEDS_ADMIN" : "SUPPORT",
  );
  const [showArchived, setShowArchived] = useState(false);
  const timeline = useChatTimeline(selected?.id, identity?.uid, messages);
  const deviceKeyAvailable = useChatDeviceKey(selected?.id, identity?.uid);
  const logEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!viewerUrl) return;
    return () => URL.revokeObjectURL(viewerUrl);
  }, [viewerUrl]);

  useEffect(() => {
    if (isAdmin && category !== "NEEDS_ADMIN") {
      setCategory("NEEDS_ADMIN");
    }
  }, [isAdmin, category]);

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [selected?.id, timeline.length]);

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
            setDrafts((current) => Object.fromEntries(tickets.flatMap(({ id, closedAt }) =>
              !closedAt && current[id] ? [[id, current[id]]] : [],
            )));
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
    setHistory(null);
    setRoom(null);
    if (!selected || !identity) return;
    let active = true;
    let stop: (() => void) | undefined;
    void messaging
      .roomKey(selected.id, identity, selected)
      .then((key) => {
        if (!active) return undefined;
        setRoom({ scope: roomScope, key });
        return messaging.subscribeMessages(
          selected.id,
          key,
          (value) => active && setHistory({ ticketId: selected.id, messages: value }),
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
  }, [roomScope, deviceKeyAvailable]);

  const visible = useMemo(
    () =>
      filterSupportTickets(
        conversations,
        identity?.role,
        category,
        showArchived,
      ),
    [conversations, identity?.role, category, showArchived],
  );
  const roleTickets = useMemo(
    () => roleConversationsForInbox(conversations, identity?.role),
    [conversations, identity?.role],
  );
  const openTicket = (ticket: Conversation | null) => {
    setSelected(ticket);
    setFeedback("");
    setViewerDocument(null);
    setViewerUrl("");
    setViewerEntry(null);
  };

  const openMedia = (id: string, preloaded?: BrowserDocument) => {
    if (!identity) return;
    void session.perform(async () => {
      const document = preloaded ?? await loadBrowserDocumentById(identity, id);
      const url = await openBrowserDocument(document);
      setViewerDocument(document);
      setViewerUrl(url);
    });
  };

  const openEntry = (id: string) => {
    if (!identity) return;
    void session.perform(async () => {
      const entry = await loadSharedPrivateEntry(id, identity);
      if (!entry) throw new Error("This shared entry could not be opened on this device.");
      setViewerEntry(entry);
    });
  };

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
      data-detail-open={Boolean(selected)}
      aria-label="Organization support inbox"
    >
      <aside className="organization-ticket-list">
        <div className="organization-inbox-heading">
          <div>
            <h2>Ticket inbox</h2>
          </div>
          <Button
            variant="outline"
            onClick={() => {
              openTicket(null);
              setShowArchived((value) => !value);
            }}
          >
            <Archive size={16} /> {showArchived ? "Active tickets" : "Archived"}
          </Button>
        </div>
        {!showArchived && (
          <div
            className="organization-ticket-tabs"
            role="group"
            aria-label={isAdmin ? "Escalated ticket category" : "Ticket categories"}
          >
            {visibleCategories.map((item) => {
              const count = roleTickets.filter(
                (c) => !c.closedAt && categoryOf(c) === item.id,
              ).length;
              const pressed = isAdmin ? true : category === item.id;
              return (
                <button
                  key={item.id}
                  type="button"
                  aria-pressed={pressed}
                  onClick={() => {
                    if (isAdmin) return;
                    if (category !== item.id) openTicket(null);
                    setCategory(item.id);
                  }}
                >
                  {item.label} ({count})
                </button>
              );
            })}
          </div>
        )}
        {visible.length === 0 ? (
          <Empty
            title={
              showArchived
                ? isAdmin
                  ? "No archived escalated tickets."
                  : "No archived tickets."
                : isAdmin
                  ? "No escalated tickets."
                  : "No tickets in this category."
            }
          >
            {isAdmin
              ? "Conversations appear here when staff request administrator help."
              : "New conversations will appear here when assigned to your account."}
          </Empty>
        ) : (
          visible.map((item) => (
            <button
              key={item.id}
              className={`organization-ticket ${selected?.id === item.id ? "selected" : ""}`}
              type="button"
              aria-pressed={selected?.id === item.id}
              onClick={() => openTicket(item)}
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
            <button className="text-link workspace-list-back" type="button" onClick={() => openTicket(null)}>
              <ArrowLeft size={17} aria-hidden="true" /> Back to tickets
            </button>
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
            {identity.role === "STAFF" && !selected.closedAt && (
                <div className="organization-ticket-actions">
                  {categoryOf(selected) !== "NEEDS_ADMIN" && (
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
                  )}
                  <Button
                    variant="quiet"
                    disabled={session.busy}
                    onClick={() =>
                      void session.perform(async () => {
                        await closeSupportTicket(selected.id);
                        openTicket(null);
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
              {!roomKey && !feedback ? (
                <p className="help-note">Opening encrypted room…</p>
              ) : timeline.length === 0 ? (
                <p className="help-note">No messages yet.</p>
              ) : (
                timeline.map((item) => {
                  const shared = item.kind === "message" && !item.message.deleted
                    ? sharedMessage(item.message.body) : null;
                  return item.kind === "membership" ? (
                    <p key={`membership-${item.event.id}`} className="chat-membership-event">
                      {messaging.membershipEventText(selected, item.event, identity.role)}
                    </p>
                  ) : (
                    <article
                      key={item.message.id}
                      className={`chat-bubble ${item.message.senderUid === identity.uid ? "own" : ""}`}
                    >
                      <small>{messaging.chatSenderName(selected, item.message, identity.role)}</small>
                      {shared?.kind === "voice" ? (
                        <VoiceMessageBubble key={identity.uid + shared.id} mediaId={shared.id} identity={identity} />
                      ) : shared ? (
                        <button
                          type="button"
                          className={`shared-message-card ${shared.kind === "media" ? "with-preview" : ""}`}
                          onClick={() => {
                            if (shared.kind === "media") openMedia(shared.id);
                            else if (shared.kind === "note" || shared.kind === "incident") openEntry(shared.id);
                          }}
                        >
                          {shared.kind === "media" && (
                            <BlurredDocumentPreview
                              documentId={shared.id}
                              identity={identity}
                              className="shared-message-preview"
                            />
                          )}
                          <div className="shared-message-row">
                            {shared.kind === "media" ? <FileLock2 />
                              : shared.kind === "incident" ? <ShieldCheck />
                                : shared.kind === "location" ? <MapPin /> : <FileText />}
                            <span>
                              <small>{shared.kind.replace("-", " ")}</small>
                              <strong>{shared.title}</strong>
                              <span>
                                {shared.kind === "media" || shared.kind === "note" || shared.kind === "incident"
                                  ? "Open securely"
                                  : "Shared item"}
                              </span>
                            </span>
                          </div>
                        </button>
                      ) : <p>{item.message.deleted ? "Message unsent" : item.message.body}</p>}
                      <time>
                        {new Date(item.message.clientSentAt).toLocaleString()}
                      </time>
                    </article>
                  );
                })
              )}
              <div ref={logEndRef} />
            </div>
            {!selected.closedAt && (
              <VoiceMessageComposer
                key={`${identity.uid}:${selected.id}`}
                identity={identity}
                conversation={selected}
                roomKey={roomKey}
                disabled={session.busy || !roomKey || selected.participantIds.length < 2}
              />
            )}
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
                    setDrafts((current) => ({ ...current, [selected.id]: "" }));
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
                  onChange={(event) => setDrafts({ ...drafts, [selected.id]: event.target.value })}
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
      {viewerDocument && viewerUrl && (
        <SafeMediaViewer
          document={viewerDocument}
          url={viewerUrl}
          allowAudioPlayback
          onClose={() => {
            setViewerDocument(null);
            setViewerUrl("");
          }}
        />
      )}
      {viewerEntry && (
        <Modal title={viewerEntry.title || "Shared entry"} wide onClose={() => setViewerEntry(null)}>
          <article className="shared-entry-viewer">
            <span className="mode-pill">{viewerEntry.kind}</span>
            {viewerEntry.incidentDate && <p><strong>Date:</strong> {viewerEntry.incidentDate}</p>}
            {viewerEntry.incidentTime && <p><strong>Approximate time:</strong> {viewerEntry.incidentTime}</p>}
            {viewerEntry.incidentLocation && <p><strong>Location:</strong> {viewerEntry.incidentLocation}</p>}
            <p className="shared-entry-body">{viewerEntry.body}</p>
            {(viewerEntry.attachmentIds?.length ?? 0) > 0 && (
              <>
                <h3>Attachments ({viewerEntry.attachmentIds!.length})</h3>
                <NoteAttachmentPreviewGrid
                  attachmentIds={viewerEntry.attachmentIds}
                  identity={identity}
                  onOpen={(id, doc) => openMedia(id, doc)}
                />
              </>
            )}
          </article>
        </Modal>
      )}
    </section>
  );
}
