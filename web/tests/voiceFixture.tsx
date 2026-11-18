import { createRoot } from "react-dom/client";
import type { Identity } from "../src/domain/DomainModels";
import type { Conversation } from "../src/services/MessagingService";
import { VoiceMessageBubble, VoiceMessageComposer } from "../src/users/shared/safe_app/VoiceMessages";
import { SafeMediaViewer } from "../src/users/shared/safe_app/SafeMediaViewer";

const identity: Identity = { uid: "voice-tester", username: "voice-tester", role: "USER" };
const conversation: Conversation = {
  id: "test-room", friendshipId: "test-friendship",
  participantIds: [identity.uid], participantUsernames: { [identity.uid]: identity.username },
  lastMessage: "", updatedAt: 0, type: "DIRECT", ownerUid: "", staffUid: "", adminUid: "",
  supportOrigin: "", supportTag: "", closedAt: 0,
};
const viewer = new URLSearchParams(location.search).get("viewer");
const root = createRoot(document.getElementById("root")!);

if (viewer) {
  const url = URL.createObjectURL(new Blob(["synthetic audio"], { type: "audio/mp4" }));
  root.render(<SafeMediaViewer document={{
    id: "audio", name: "Recording.m4a", mimeType: "audio/mp4", ownerUid: identity.uid, createdAt: 0,
    approved: true, approvalMessage: "", ciphertextSize: 15, ciphertextSha256: "", evidence: false,
  }} url={url} allowAudioPlayback={viewer === "chat"} onClose={() => {
    URL.revokeObjectURL(url);
    root.unmount();
  }} />);
} else {
  const roomKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt"]);
  root.render(
    <>
      <VoiceMessageComposer identity={identity} conversation={conversation} roomKey={roomKey} disabled={false} />
      <VoiceMessageBubble identity={identity} mediaId="received-voice" />
    </>,
  );
}
