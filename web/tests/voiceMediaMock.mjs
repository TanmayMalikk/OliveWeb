export async function uploadBrowserDocument(file) {
  window.voiceCalls.push(["upload", file.name, file.size]);
  return "test-media";
}
export async function prepareVoiceMessage(mediaId, conversation) {
  window.voiceCalls.push(["prepare", mediaId, conversation.id]);
  return { id: "stable-message-id" };
}
export async function publishVoiceMessage(message) {
  if (window.voiceCalls.filter(([action]) => action === "publish").length === 0) {
    window.voiceCalls.push(["publish", message.id]);
    throw new Error("temporary failure");
  }
  window.voiceCalls.push(["publish", message.id]);
}
export async function openVoiceMessage(mediaId) {
  if (!window.voiceCalls.some(([action]) => action === "open")) {
    window.voiceCalls.push(["open", mediaId]);
    throw new Error("temporary failure");
  }
  window.voiceCalls.push(["open", mediaId]);
  return URL.createObjectURL(new Blob(["recording"], { type: "audio/mp4" }));
}
