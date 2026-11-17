import { createRoot } from "react-dom/client";
import type { Identity } from "../src/domain/DomainModels";
import type { Conversation } from "../src/services/MessagingService";
import { VoiceMessageBubble, VoiceMessageComposer } from "../src/users/shared/safe_app/VoiceMessages";

const identity: Identity = { uid: "voice-tester", username: "voice-tester", role: "USER" };
const conversation: Conversation = {
  id: "test-room", friendshipId: "test-friendship",
  participantIds: [identity.uid], participantUsernames: { [identity.uid]: identity.username },
  lastMessage: "", updatedAt: 0, type: "DIRECT", ownerUid: "", staffUid: "", adminUid: "",
  supportOrigin: "", supportTag: "", closedAt: 0,
};
const roomKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt"]);

createRoot(document.getElementById("root")!).render(
  <>
    <VoiceMessageComposer identity={identity} conversation={conversation} roomKey={roomKey} disabled={false} />
    <VoiceMessageBubble identity={identity} mediaId="received-voice" />
  </>,
);
