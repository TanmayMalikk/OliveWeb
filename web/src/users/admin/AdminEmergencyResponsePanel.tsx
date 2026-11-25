// Admins: lists active and resolved emergency alerts, opens encrypted report PDFs, and displays live locations.
import { useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock, ExternalLink, FileText, MapPin, RotateCcw } from "lucide-react";
import { Button, Empty } from "../../components/SharedComponents";
import {
  openEmergencyReportPdf,
  setEmergencyResolved,
  type AdminLiveLocation,
  type EmergencyReport,
} from "../../services/EmergencyResponseService";
import type { BrowserDocument } from "../../services/MediaService";
import { accounts } from "../../services/AccountService";
import { SafeMediaViewer } from "../shared/safe_app/SafeMediaViewer";
import { useSession } from "../../session/SessionProvider";

export function AdminEmergencyResponsePanel({
  alerts,
  locations,
  loading,
  error,
  onTrackOnMap,
}: {
  alerts: EmergencyReport[];
  locations: Record<string, AdminLiveLocation>;
  loading: boolean;
  error: string;
  onTrackOnMap?: (ownerUid: string) => void;
}) {
  const session = useSession();
  const [tab, setTab] = useState<"active" | "resolved">("active");
  const [openingId, setOpeningId] = useState<string | null>(null);
  const [resolvingId, setResolvingId] = useState<string | null>(null);
  const [trackingUid, setTrackingUid] = useState<string | null>(null);
  const [cardErrors, setCardErrors] = useState<Record<string, string>>({});
  const [viewer, setViewer] = useState<{ document: BrowserDocument; url: string } | null>(null);

  useEffect(() => {
    const currentUrl = viewer?.url;
    return () => {
      if (currentUrl) URL.revokeObjectURL(currentUrl);
    };
  }, [viewer?.url]);

  const activeAlerts = alerts.filter((item) => item.resolvedAt <= 0);
  const resolvedAlerts = alerts.filter((item) => item.resolvedAt > 0);
  const visible = tab === "active" ? activeAlerts : resolvedAlerts;
  const activeLiveLocations = Object.values(locations)
    .filter((point) => point.active)
    .sort((a, b) => b.updatedAt - a.updatedAt);

  async function openPdf(alert: EmergencyReport) {
    const identity = accounts.identity;
    if (!identity) {
      setCardErrors((prev) => ({ ...prev, [alert.id]: "Sign in again to open encrypted files." }));
      return;
    }
    setOpeningId(alert.id);
    setCardErrors((prev) => {
      const next = { ...prev };
      delete next[alert.id];
      return next;
    });
    try {
      const opened = await openEmergencyReportPdf(identity, alert.mediaId);
      setViewer(opened);
    } catch (failure) {
      setCardErrors((prev) => ({
        ...prev,
        [alert.id]: failure instanceof Error ? failure.message : "Unable to open report PDF.",
      }));
    } finally {
      setOpeningId((current) => (current === alert.id ? null : current));
    }
  }

  async function toggleResolved(alert: EmergencyReport, resolved: boolean) {
    setResolvingId(alert.id);
    setCardErrors((prev) => {
      const next = { ...prev };
      delete next[alert.id];
      return next;
    });
    try {
      await session.perform(() => setEmergencyResolved(alert.id, resolved));
    } catch (failure) {
      setCardErrors((prev) => ({
        ...prev,
        [alert.id]: failure instanceof Error ? failure.message : "Unable to update emergency status.",
      }));
    } finally {
      setResolvingId((current) => (current === alert.id ? null : current));
    }
  }

  return (
    <>
      <div className="panel organization-toolbar">
        <div>
          <h2>Emergency alerts</h2>
          <p>
            Review incoming emergency requests, decrypt report PDFs in your browser, and check live coordinates. This
            workspace does not dispatch emergency services.
          </p>
        </div>
        <div className="emergency-tabs" role="tablist" aria-label="Emergency status filter">
          <Button
            variant={tab === "active" ? "" : "outline"}
            role="tab"
            aria-selected={tab === "active"}
            onClick={() => setTab("active")}
          >
            Active ({activeAlerts.length})
          </Button>
          <Button
            variant={tab === "resolved" ? "" : "outline"}
            role="tab"
            aria-selected={tab === "resolved"}
            onClick={() => setTab("resolved")}
          >
            Resolved ({resolvedAlerts.length})
          </Button>
        </div>
      </div>

      {error && (
        <div className="panel" role="alert">
          <p className="form-error">{error}</p>
        </div>
      )}

      {loading && alerts.length === 0 ? (
        <div className="panel" role="status">
          <p>Loading emergency requests…</p>
        </div>
      ) : visible.length === 0 ? (
        <Empty
          title={
            tab === "active"
              ? "No active emergency alerts"
              : "No resolved emergency alerts"
          }
        >
          {tab === "active"
            ? "New emergency requests submitted from the mobile app appear here in real time."
            : "Resolved alerts remain archived here and can be reopened at any time."}
        </Empty>
      ) : (
        <div className="emergency-grid">
          {visible.map((alert) => {
            const isResolved = alert.resolvedAt > 0;
            const livePoint = locations[alert.ownerUid];
            const trackingOpen = trackingUid === alert.ownerUid;
            const mapUrl = livePoint
              ? `https://www.openstreetmap.org/?mlat=${livePoint.latitude}&mlon=${livePoint.longitude}#map=16/${livePoint.latitude}/${livePoint.longitude}`
              : "";
            return (
              <article
                key={alert.id}
                className={isResolved ? "panel emergency-card" : "panel emergency-card emergency-card-urgent"}
              >
                <div className="emergency-card-header">
                  <div>
                    <h3>{alert.ownerName}</h3>
                    {alert.createdAt > 0 && (
                      <small>
                        <Clock size={13} aria-hidden="true" />{" "}
                        {new Date(alert.createdAt).toLocaleString()}
                      </small>
                    )}
                  </div>
                  <span className={isResolved ? "tag emergency-badge-resolved" : "tag emergency-badge"}>
                    {isResolved ? (
                      <>
                        <CheckCircle2 size={13} aria-hidden="true" /> Resolved
                      </>
                    ) : (
                      <>
                        <AlertTriangle size={13} aria-hidden="true" /> Emergency
                      </>
                    )}
                  </span>
                </div>

                <div className="emergency-actions">
                  {alert.mediaId ? (
                    <Button
                      disabled={openingId === alert.id}
                      onClick={() => void openPdf(alert)}
                    >
                      <FileText size={16} aria-hidden="true" />{" "}
                      {openingId === alert.id ? "Decrypting PDF…" : "Open report PDF"}
                    </Button>
                  ) : (
                    <p className="emergency-waiting">Waiting for report PDF upload…</p>
                  )}

                  <Button
                    variant="outline"
                    aria-expanded={onTrackOnMap ? undefined : trackingOpen}
                    onClick={() => {
                      if (onTrackOnMap) onTrackOnMap(alert.ownerUid);
                      else setTrackingUid((cur) => (cur === alert.ownerUid ? null : alert.ownerUid));
                    }}
                  >
                    <MapPin size={16} aria-hidden="true" />{" "}
                    {onTrackOnMap || !trackingOpen ? "Track live location" : "Hide live location"}
                  </Button>

                  <Button
                    variant="quiet"
                    disabled={resolvingId === alert.id || session.busy}
                    onClick={() => void toggleResolved(alert, !isResolved)}
                  >
                    {isResolved ? (
                      <>
                        <RotateCcw size={15} aria-hidden="true" /> Reopen
                      </>
                    ) : (
                      <>
                        <CheckCircle2 size={15} aria-hidden="true" /> Mark as resolved
                      </>
                    )}
                  </Button>
                </div>

                {cardErrors[alert.id] && (
                  <p role="alert" className="form-error">{cardErrors[alert.id]}</p>
                )}

                {trackingOpen && (
                  <div className="emergency-location-box">
                    {livePoint ? (
                      <>
                        <p>
                          <strong>{livePoint.active ? "Live sharing active" : "Last known location"}</strong>
                          {" · "}
                          Updated {new Date(livePoint.updatedAt).toLocaleString()}
                        </p>
                        <p>
                          Coordinates: {livePoint.latitude.toFixed(5)}, {livePoint.longitude.toFixed(5)}
                          {livePoint.accuracy !== null ? ` (±${Math.round(livePoint.accuracy)} m)` : ""}
                        </p>
                        <a
                          className="text-link"
                          href={mapUrl}
                          target="_blank"
                          rel="noopener noreferrer"
                        >
                          Open in OpenStreetMap <ExternalLink size={14} aria-hidden="true" />
                        </a>
                      </>
                    ) : (
                      <p>No live location shared yet for this account.</p>
                    )}
                  </div>
                )}
              </article>
            );
          })}
        </div>
      )}

      {activeLiveLocations.length > 0 && (
        <section className="panel">
          <h2>Active emergency live locations ({activeLiveLocations.length})</h2>
          <div className="emergency-grid" style={{ marginTop: "0.75rem" }}>
            {activeLiveLocations.map((point) => (
              <div key={point.uid} className="emergency-location-box">
                <p>
                  <strong>{point.name}</strong> · Updated {new Date(point.updatedAt).toLocaleTimeString()}
                </p>
                <p>
                  {point.latitude.toFixed(5)}, {point.longitude.toFixed(5)}
                  {point.accuracy !== null ? ` (±${Math.round(point.accuracy)} m)` : ""}
                </p>
                <a
                  className="text-link"
                  href={`https://www.openstreetmap.org/?mlat=${point.latitude}&mlon=${point.longitude}#map=16/${point.latitude}/${point.longitude}`}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Open in OpenStreetMap <ExternalLink size={14} aria-hidden="true" />
                </a>
                {onTrackOnMap && (
                  <div>
                    <Button variant="outline" onClick={() => onTrackOnMap(point.uid)}>
                      <MapPin size={15} aria-hidden="true" /> Focus on live map
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {viewer && (
        <SafeMediaViewer
          document={viewer.document}
          url={viewer.url}
          onClose={() => setViewer(null)}
        />
      )}
    </>
  );
}
