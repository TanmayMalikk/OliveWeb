// All safe-app roles: shows friends and direct messages in the web safe app.
import { useEffect, useState } from "react";
import { ArrowLeft, Send, MessageCircle, UserPlus, Search } from "lucide-react";
import { Button, Empty, Field, Form } from "../../../components/SharedComponents";
import { useSession } from "../../../session/SessionProvider";
import { accounts, cloudMode } from "../../../services/AccountService";
import * as messaging from "../../../services/MessagingService";
import type {
  Friend,
  Request,
  Message,
  DirectoryUser,
  Conversation,
} from "../../../services/MessagingService";
export default function Messages() {
  const session = useSession();
  const [social, setSocial] = useState<{
    friends: Friend[];
    incoming: Request[];
    outgoing: Request[];
  }>({ friends: [], incoming: [], outgoing: [] });
  const [conversation, setConversation] = useState<Conversation | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [rows, setRows] = useState<Message[]>([]);
  const [found, setFound] = useState<DirectoryUser | null>(null);
  const [feedback, setFeedback] = useState("");
  const [draft, setDraft] = useState("");
  const [roomKey, setRoomKey] = useState<CryptoKey | null>(null);
  const identity = accounts.identity;
  // Update identity when information used by this screen changes.
  useEffect(() => {
    if (!cloudMode || !identity) return;
    let active = true;
    const stops: (() => void)[] = [];
    void messaging
      .register(identity)
      .then(() =>
        messaging.subscribeSocial(
          identity.uid,
          (value) => {
            if (active) setSocial(value);
          },
          (error) => {
            if (active) setFeedback(error.message);
          },
        ),
      )
      .then((cleanup) => {
        if (!active) cleanup();
        else stops.push(cleanup);
      })
      .catch(() => {
        if (active)
          setFeedback(
            "Unable to connect. Sign in again to reconnect to messaging.",
          );
      });
    void messaging.subscribeConversations(
      identity.uid,
      (value) => { if (active) setConversations(value); },
      (error) => { if (active) setFeedback(error.message); },
    ).then((cleanup) => {
      if (!active) cleanup(); else stops.push(cleanup);
    });
    return () => {
      active = false;
      stops.forEach((stop) => stop());
    };
  }, [identity?.uid]);
  // Update stops when information used by this screen changes.
  useEffect(() => {
    setRows([]);
    setDraft("");
    setRoomKey(null);
    if (!conversation) return;
    let active = true;
    let stop: (() => void) | undefined;
    void messaging
      .roomKey(conversation.id, identity!, conversation)
      .then((key) => {
        if (!active) return undefined;
        setRoomKey(key);
        return messaging.subscribeMessages(
          conversation.id,
          key,
          (value) => {
            if (active) setRows(value);
          },
          (error) => {
            if (active) setFeedback(error.message);
          },
        );
      })
      .then((cleanup) => {
        if (!cleanup) return;
        if (!active) cleanup();
        else stop = cleanup;
      })
      .catch((error) => {
        if (active)
          setFeedback(
            error instanceof Error
              ? error.message
              : "Unable to open secure messages.",
          );
      });
    return () => {
      active = false;
      stop?.();
    };
  }, [conversation?.id]);
  if (!cloudMode)
    return (
      <Empty
        title="Your conversations, connected."
        action={<span className="mode-pill">Browser-only account</span>}
      >
        Live messaging needs a Firebase-connected account. Local accounts stay
        in this browser and cannot contact other people.
      </Empty>
    );
  if (!identity) return null;
  // Web safe app: returns the action.
  const action = (work: () => Promise<void>) => session.perform(work);
  return (
    <div className={`messages-layout ${conversation ? "chat-open" : ""}`}>
      <aside className="conversation-sidebar">
        <h2>People you trust.</h2>
        <Form
          onSubmit={async (data) => {
            await action(async () => {
              setFeedback("");
              const result = await messaging.searchUser(
                String(data.get("username")),
                identity.uid,
              );
              setFound(result);
              if (!result) setFeedback("No matching account found.");
            });
          }}
        >
          <Field
            label="Find an exact username"
            name="username"
            required
            minLength={4}
            maxLength={24}
          />
          <Button type="submit" variant="outline" disabled={session.busy}>
            <Search size={16} /> Find person
          </Button>
        </Form>
        {found && (
          <div className="request-row">
            <span>@{found.username}</span>
            {social.friends.some((f) => f.friendUid === found.uid) ? (
              <small>Already friends</small>
            ) : social.outgoing.some((r) => r.recipientUid === found.uid) ? (
              <small>Request sent</small>
            ) : (
              <Button
                variant="quiet"
                onClick={() =>
                  void action(async () => {
                    await messaging.sendRequest(identity, found);
                    setFound(null);
                  })
                }
              >
                <UserPlus size={16} /> Add
              </Button>
            )}
          </div>
        )}
        {social.incoming.length > 0 && <h3>Friend requests</h3>}
        {social.incoming.map((request) => (
          <div className="request-row" key={request.id}>
            <strong>@{request.senderUsername}</strong>
            <div>
              <Button
                variant="quiet"
                onClick={() =>
                  void action(() => messaging.acceptRequest(identity, request))
                }
              >
                Accept
              </Button>
              <Button
                variant="quiet"
                onClick={() =>
                  void action(() => messaging.dismissRequest(request))
                }
              >
                Decline
              </Button>
            </div>
          </div>
        ))}
        {social.outgoing.map((request) => (
          <div className="request-row" key={request.id}>
            <span>
              @{request.recipientUsername}
              <small> Request pending</small>
            </span>
            <Button
              variant="quiet"
              onClick={() =>
                void action(() => messaging.dismissRequest(request))
              }
            >
              Cancel
            </Button>
          </div>
        ))}
        {conversations.some((item) => item.type === "SUPPORT" && !item.closedAt) && <h3>Support chats</h3>}
        {conversations.filter((item) => item.type === "SUPPORT" && !item.closedAt).map((item) => (
          <button
            key={item.id}
            className={`friend-row support-chat-row ${conversation?.id === item.id ? "selected" : ""}`}
            onClick={() => setConversation(item)}
          >
            <span className="avatar">O</span>
            <span>
              {messaging.conversationName(item, identity)}
              <small>{item.lastMessage || "New conversation"}</small>
            </span>
            <MessageCircle size={17} />
          </button>
        ))}
        {conversations.some((item) => item.type === "DIRECT" && !item.closedAt) && <h3>Direct messages</h3>}
        {conversations.filter((item) => item.type === "DIRECT" && !item.closedAt).map((item) => (
          <button key={item.id}
            className={`friend-row ${conversation?.id === item.id ? "selected" : ""}`}
            onClick={() => setConversation(item)}>
            <span className="avatar">{messaging.conversationName(item, identity)[0]?.toUpperCase()}</span>
            <span>@{messaging.conversationName(item, identity)}<small>{item.lastMessage || "New conversation"}</small></span>
            <MessageCircle size={17} />
          </button>
        ))}
        {conversations.some((item) => item.closedAt) && <h3>Archived chats</h3>}
        {conversations.filter((item) => item.closedAt).map((item) => (
          <button key={item.id} className={`friend-row ${conversation?.id === item.id ? "selected" : ""}`}
            onClick={() => setConversation(item)}>
            <span className="avatar">{item.type === "SUPPORT" ? "O" : messaging.conversationName(item, identity)[0]?.toUpperCase()}</span>
            <span>{messaging.conversationName(item, identity)}<small>Archived conversation</small></span>
            <MessageCircle size={17} />
          </button>
        ))}
        {!conversations.length && (
          <p className="help-note">
            Find a username and accept a friend request to start a conversation.
          </p>
        )}
      </aside>
      <section className="chat-panel">
        {conversation ? (
          <>
            <header className="chat-heading">
              <button
                className="icon-button"
                onClick={() => setConversation(null)}
                aria-label="Back to conversations"
              >
                <ArrowLeft />
              </button>
              <h2>{messaging.conversationName(conversation, identity)}</h2>
            </header>
            <div
              className="chat-messages"
              aria-label="Conversation messages"
              role="log"
              aria-live="polite"
            >
              {rows.map((message) => (
                <article
                  key={message.id}
                  className={`chat-bubble ${message.senderUid === identity.uid ? "own" : ""}`}
                >
                  <small>{conversation.type === "SUPPORT" &&
                    identity.role !== "STAFF" && identity.role !== "ADMIN" &&
                    message.senderUid !== conversation.ownerUid
                    ? "Olive Staff" : message.senderUsername}</small>
                  <p>{message.deleted ? "Message unsent" : message.body}</p>
                  <time>
                    {new Date(message.clientSentAt).toLocaleTimeString([], {
                      hour: "2-digit",
                      minute: "2-digit",
                    })}
                  </time>
                  {message.senderUid === identity.uid && !message.deleted && (
                    <button
                      className="text-link"
                      onClick={() =>
                        void action(() =>
                          messaging.unsendMessage(
                            conversation.id,
                            message,
                            identity,
                          ),
                        )
                      }
                    >
                      Unsend
                    </button>
                  )}
                </article>
              ))}
            </div>
            <Form
              className="chat-compose"
              onSubmit={async () => {
                await action(async () => {
                  await messaging.sendMessage(
                    conversation.id,
                    identity,
                    draft,
                    roomKey!,
                  );
                  setDraft("");
                });
              }}
            >
              <label className="sr-only" htmlFor="message-draft">
                Write a message
              </label>
              <textarea
                id="message-draft"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                maxLength={2000}
                placeholder="A message, at your pace…"
                required
              />
              <Button
                type="submit"
                disabled={session.busy || !draft.trim() || !roomKey || Boolean(conversation.closedAt)}
              >
                <Send size={18} />
                <span className="sr-only">Send message</span>
              </Button>
            </Form>
          </>
        ) : (
          <Empty title="A conversation starts with connection.">
            Choose a trusted person or find an exact username to send a friend
            request.
          </Empty>
        )}
      </section>
      {feedback && (
        <p className="form-error message-feedback" role="status">
          {feedback}
        </p>
      )}
    </div>
  );
}
