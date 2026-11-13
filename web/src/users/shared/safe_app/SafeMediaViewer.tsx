// Safe-app users: previews a decrypted photo, video, recording, PDF, or text file without leaving Olive Branch.
import { useEffect, useState } from "react";
import { Modal } from "../../../components/SharedComponents";
import type { BrowserDocument } from "../../../services/MediaService";

function TextPreview({ url }: { url: string }) {
  const [text, setText] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const request = new AbortController();
    void fetch(url, { signal: request.signal })
      .then((response) => {
        if (!response.ok) throw new Error("Unable to read decrypted text");
        return response.text();
      })
      .then((value) => { if (!request.signal.aborted) setText(value); })
      .catch(() => { if (!request.signal.aborted) setFailed(true); });
    return () => request.abort();
  }, [url]);
  if (failed) return <p role="alert">Unable to preview this text file.</p>;
  return text === null ? <p role="status">Loading preview…</p> : <pre>{text}</pre>;
}

export function SafeMediaViewer({
  document,
  url,
  onClose,
}: {
  document: BrowserDocument;
  url: string;
  onClose: () => void;
}) {
  const mimeType = document.mimeType.toLowerCase().split(";", 1)[0].trim();
  const previewText = mimeType.startsWith("text/") && document.ciphertextSize <= 1024 * 1024;
  return (
    <Modal title={document.name} wide onClose={onClose}>
      <div className="safe-document-viewer">
        {mimeType.startsWith("image/") ? (
          <img src={url} alt={document.name} />
        ) : mimeType.startsWith("video/") ? (
          <video src={url} controls aria-label={document.name} />
        ) : mimeType.startsWith("audio/") ? (
          <audio src={url} controls aria-label={document.name} />
        ) : mimeType === "application/pdf" ? (
          <iframe src={url} title={document.name} />
        ) : previewText ? (
          <TextPreview key={url} url={url} />
        ) : (
          <div className="panel">
            <p>This file cannot be previewed in the browser.</p>
            <a className="button" href={url} download={document.name}>
              Download decrypted file
            </a>
          </div>
        )}
      </div>
    </Modal>
  );
}
