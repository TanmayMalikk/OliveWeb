// Safe-app users: previews a decrypted photo, video, recording, PDF, or text file without leaving Olive Branch.
import { Modal } from "../../../components/SharedComponents";
import type { BrowserDocument } from "../../../services/MediaService";

export function SafeMediaViewer({
  document,
  url,
  onClose,
}: {
  document: BrowserDocument;
  url: string;
  onClose: () => void;
}) {
  return (
    <Modal title={document.name} wide onClose={onClose}>
      <div className="safe-document-viewer">
        {document.mimeType.startsWith("image/") ? (
          <img src={url} alt={document.name} />
        ) : document.mimeType.startsWith("video/") ? (
          <video src={url} controls aria-label={document.name} />
        ) : document.mimeType.startsWith("audio/") ? (
          <audio src={url} controls aria-label={document.name} />
        ) : document.mimeType === "application/pdf" ||
          document.mimeType.startsWith("text/") ? (
          <iframe src={url} title={document.name} />
        ) : (
          <div className="panel">
            <p>This file type cannot be previewed in the browser.</p>
            <a className="button" href={url} download={document.name}>
              Download decrypted file
            </a>
          </div>
        )}
      </div>
    </Modal>
  );
}
