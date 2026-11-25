// Renders 50%-blurred live previews for documents, note cards, pickers, and shared chat media on Web.
import { useEffect, useState } from "react";
import { FileImage, FileLock2, FileText, FileVideo2, Play } from "lucide-react";
import type { Identity } from "../domain/DomainModels";
import type { BrowserDocument } from "../services/MediaService";
import {
  getCachedDocumentPreview,
  loadDocumentPreview,
  loadDocumentPreviewById,
  type CachedDocumentPreview,
} from "../services/DocumentPreviewCache";

function typeLabel(mimeType: string, name: string): string {
  const clean = mimeType.toLowerCase().split(";", 1)[0].trim();
  if (clean.startsWith("image/")) return "Photo";
  if (clean.startsWith("video/")) return "Video";
  if (clean === "application/pdf" || name.toLowerCase().endsWith(".pdf")) return "PDF";
  if (clean.startsWith("audio/")) return "Audio";
  return "Doc";
}

function TypeIcon({ mimeType, name }: { mimeType: string; name: string }) {
  const clean = mimeType.toLowerCase().split(";", 1)[0].trim();
  if (clean.startsWith("image/")) return <FileImage size={28} aria-hidden="true" />;
  if (clean.startsWith("video/")) return <FileVideo2 size={28} aria-hidden="true" />;
  if (clean === "application/pdf" || clean.startsWith("text/") || name.toLowerCase().endsWith(".pdf")) {
    return <FileText size={28} aria-hidden="true" />;
  }
  return <FileLock2 size={28} aria-hidden="true" />;
}

export function BlurredDocumentPreview({
  document,
  documentId,
  identity,
  compact = false,
  showEvidenceBadge = false,
  className = "",
  onResolvedDocument,
}: {
  document?: BrowserDocument;
  documentId?: string;
  identity?: Identity | null;
  compact?: boolean;
  showEvidenceBadge?: boolean;
  className?: string;
  onResolvedDocument?: (doc: BrowserDocument) => void;
}) {
  const targetId = document?.id ?? documentId ?? "";
  const [preview, setPreview] = useState<CachedDocumentPreview | null>(() =>
    identity && targetId ? getCachedDocumentPreview(identity.uid, targetId) ?? null : null,
  );
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!identity || !targetId) {
      setPreview(null);
      return;
    }
    const cached = getCachedDocumentPreview(identity.uid, targetId);
    if (cached) {
      setPreview(cached);
      if (cached.document) onResolvedDocument?.(cached.document);
      if (cached.url || cached.text || (document && !document.approved)) return;
    }
    let active = true;
    setLoading(true);
    const loader = document
      ? loadDocumentPreview(identity, document)
      : loadDocumentPreviewById(identity, targetId);
    void loader
      .then((result) => {
        if (!active) return;
        setPreview(result);
        if (result?.document) onResolvedDocument?.(result.document);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [identity?.uid, targetId, document?.approved, document?.ciphertextSha256]);

  const resolvedDoc = document ?? preview?.document;
  const mimeType = resolvedDoc?.mimeType ?? "";
  const name = resolvedDoc?.name ?? "";
  const isVideo = mimeType.toLowerCase().startsWith("video/") || preview?.kind === "video";
  const badge = typeLabel(mimeType, name);

  return (
    <div className={`blurred-preview ${compact ? "compact" : ""} ${loading ? "loading" : ""} ${className}`}>
      {preview?.kind === "image" && preview.url ? (
        <img className="blurred-preview-media" src={preview.url} alt="" />
      ) : preview?.kind === "video" && preview.url ? (
        <img className="blurred-preview-media" src={preview.url} alt="" />
      ) : preview?.kind === "pdf" && preview.url ? (
        <iframe className="blurred-preview-pdf" src={`${preview.url}#toolbar=0&navpanes=0&scrollbar=0`} title="" tabIndex={-1} />
      ) : preview?.kind === "text" && preview.text ? (
        <pre className="blurred-preview-text" aria-hidden="true">{preview.text}</pre>
      ) : (
        <div className="blurred-preview-fallback">
          <TypeIcon mimeType={mimeType} name={name} />
        </div>
      )}
      {isVideo && (
        <span className="blurred-preview-play" aria-hidden="true">
          <Play size={16} fill="currentColor" />
        </span>
      )}
      {!compact && <span className="blurred-preview-badge">{badge}</span>}
      {showEvidenceBadge && resolvedDoc?.evidence && (
        <span className="blurred-preview-evidence">Evidence</span>
      )}
    </div>
  );
}

export function NoteAttachmentPreviewGrid({
  attachmentIds,
  documents,
  identity,
  compact = false,
  onOpen,
}: {
  attachmentIds?: string[];
  documents?: BrowserDocument[];
  identity?: Identity | null;
  compact?: boolean;
  onOpen?: (mediaId: string, resolvedDoc?: BrowserDocument) => void;
}) {
  const uniqueIds = [...new Set((attachmentIds ?? []).filter(Boolean))];
  const [resolvedNames, setResolvedNames] = useState<Record<string, BrowserDocument>>({});
  if (!uniqueIds.length) return null;
  const displayed = compact ? uniqueIds.slice(0, 4) : uniqueIds;
  const extraCount = compact ? Math.max(0, uniqueIds.length - 4) : 0;

  if (compact) {
    return (
      <div className={`note-attachment-strip count-${Math.min(displayed.length, 4)}`} aria-hidden="true">
        {displayed.map((id, index) => {
          const doc = documents?.find((item) => item.id === id) ?? resolvedNames[id];
          const isOverflow = extraCount > 0 && index === displayed.length - 1;
          return (
            <div className="note-attachment-thumb" key={id}>
              <BlurredDocumentPreview
                document={doc}
                documentId={id}
                identity={identity}
                compact
                onResolvedDocument={(loaded) =>
                  setResolvedNames((cur) => (cur[id] ? cur : { ...cur, [id]: loaded }))
                }
              />
              {isOverflow && <span className="note-attachment-more">+{extraCount}</span>}
            </div>
          );
        })}
      </div>
    );
  }

  return (
    <div className="shared-attachment-grid">
      {displayed.map((id) => {
        const doc = documents?.find((item) => item.id === id) ?? resolvedNames[id];
        return (
          <button
            key={id}
            type="button"
            className="shared-attachment-card"
            onClick={() => onOpen?.(id, doc)}
          >
            <BlurredDocumentPreview
              document={doc}
              documentId={id}
              identity={identity}
              showEvidenceBadge
              onResolvedDocument={(loaded) =>
                setResolvedNames((cur) => (cur[id] ? cur : { ...cur, [id]: loaded }))
              }
            />
            <strong>{doc?.name || "Open encrypted attachment"}</strong>
          </button>
        );
      })}
    </div>
  );
}
