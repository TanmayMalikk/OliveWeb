import { useEffect, useMemo, useState } from "react";
import { subscribeChatDeviceKey, subscribeMembershipEvents, type ChatMembershipEvent, type Message } from "../../services/MessagingService";

const noEvents: ChatMembershipEvent[] = [];

// An already-open chat can retry as soon as another participant approves this device.
export function useChatDeviceKey(conversationId: string | undefined, uid: string | undefined) {
  const [approved, setApproved] = useState<{ conversationId: string; uid: string }>();
  useEffect(() => {
    if (!conversationId || !uid) return;
    let active = true;
    let stop: (() => void) | undefined;
    setApproved(undefined);
    void subscribeChatDeviceKey(conversationId, uid, (available) => {
      if (active) setApproved(available ? { conversationId, uid } : undefined);
    }).then((unsubscribe) => {
      if (active) stop = unsubscribe;
      else unsubscribe();
    }).catch(() => {
      if (active) setApproved(undefined);
    });
    return () => { active = false; stop?.(); };
  }, [conversationId, uid]);
  return approved?.conversationId === conversationId && approved?.uid === uid && Boolean(approved);
}

export function useMembershipHistory(conversationId: string | undefined, uid: string | undefined, actorUid?: string) {
  const [history, setHistory] = useState<{ conversationId: string; uid: string; actorUid?: string; events: ChatMembershipEvent[] }>();
  useEffect(() => {
    if (!conversationId || !uid) return;
    let active = true;
    let stop: (() => void) | undefined;
    void subscribeMembershipEvents(conversationId, (events) => {
      if (active) setHistory({ conversationId, uid, actorUid, events });
    }, actorUid).then((unsubscribe) => {
      if (active) stop = unsubscribe;
      else unsubscribe();
    }).catch(() => {
      if (active) setHistory(undefined);
    });
    return () => { active = false; stop?.(); };
  }, [conversationId, uid, actorUid]);

  return history && history.conversationId === conversationId && history.uid === uid && history.actorUid === actorUid
    ? history.events : noEvents;
}

export function useChatTimeline(conversationId: string | undefined, uid: string | undefined, messages: Message[]) {
  const events = useMembershipHistory(conversationId, uid);
  return useMemo(() => {
    return [
      ...messages.map((message) => ({ kind: "message" as const, message, time: message.clientSentAt })),
      ...events.map((event) => ({ kind: "membership" as const, event, time: event.createdAt })),
    ].sort((a, b) => a.time - b.time);
  }, [messages, events]);
}
