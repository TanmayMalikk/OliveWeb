// Safe-app users: provides staff requests, support directories, browser location, and camera capture.
import { useEffect, useState } from "react";
import {
  Camera,
  ExternalLink,
  FileLock2,
  LocateFixed,
  MapPin,
  Phone,
  RefreshCw,
  Upload,
} from "lucide-react";
import { Button, Empty } from "../../../components/SharedComponents";
import {
  accounts,
  cloudMode,
  firebase,
} from "../../../services/AccountService";
import { useSession } from "../../../session/SessionProvider";
import {
  listBrowserDocuments,
  openBrowserDocument,
  uploadBrowserDocument,
  type BrowserDocument,
} from "../../../services/MediaService";

// Calls the matching backend serviceable for web safe app. It sends a signed-in request to the protected cloud function and checks the reply.
async function callable<T>(name: string, data: unknown): Promise<T> {
  if (!cloudMode || !accounts.identity)
    throw new Error("Sign in to your shared account first.");
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(data)).data as T;
}

// Shows the staff help page view for web safe app.
export function StaffHelpPage() {
  const session = useSession();
  const [ticket, setTicket] = useState<{
    requested: boolean;
    staffName?: string;
    conversationId?: string;
  } | null>(null);
  return (
    <section className="panel safe-feature-page">
      <h2>Request help from staff</h2>
      <p>
        This opens a private support ticket. It does not share your safety plan.
      </p>
      {ticket?.requested ? (
        <div className="status-banner">
          <strong>
            {ticket.staffName
              ? "Olive Staff is assigned."
              : "Looking for an available staff member…"}
          </strong>
          <Button variant="outline" onClick={() => session.setPage("Messages")}>
            Open support chats
          </Button>
          <Button
            variant="quiet"
            onClick={() =>
              void session.perform(async () => {
                const result = await callable<{ ticket: typeof ticket }>(
                  "staffSupportRequest",
                  { action: "stop" },
                );
                setTicket(result.ticket);
              })
            }
          >
            Cancel request
          </Button>
        </div>
      ) : (
        <Button
          disabled={session.busy}
          onClick={() =>
            void session.perform(async () => {
              const result = await callable<{ ticket: typeof ticket }>(
                "staffSupportRequest",
                { action: "request" },
              );
              setTicket(result.ticket);
            })
          }
        >
          Request help from staff
        </Button>
      )}
    </section>
  );
}

const resources = [
  {
    name: "Emergency services",
    description: "Immediate danger or urgent medical help",
    phone: "911",
    url: "tel:911",
  },
  {
    name: "211 Canada",
    description: "Local social, community, and crisis services",
    phone: "211",
    url: "tel:211",
  },
  {
    name: "Government of Canada help directory",
    description: "Province and territory violence-support services",
    phone: "Online directory",
    url: "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",
  },
  {
    name: "ShelterSafe",
    description: "Find nearby shelters across Canada",
    phone: "Online directory",
    url: "https://sheltersafe.ca/",
  },
];
// Shows the hotlines page view for web safe app.
export function HotlinesPage() {
  return (
    <section className="safe-feature-page">
      <div className="panel">
        <h2>Support hotlines and shelters</h2>
        <p>
          Calling links open your device dialer and do not place a call
          automatically.
        </p>
      </div>
      <div className="safe-resource-grid">
        {resources.map((resource) => (
          <article className="panel" key={resource.name}>
            <h3>{resource.name}</h3>
            <p>{resource.description}</p>
            <strong>{resource.phone}</strong>
            <a
              className="button outline"
              href={resource.url}
              target={resource.url.startsWith("https") ? "_blank" : undefined}
              rel="noreferrer"
            >
              {resource.url.startsWith("tel") ? (
                <Phone size={17} />
              ) : (
                <ExternalLink size={17} />
              )}
              {resource.url.startsWith("tel")
                ? "Open dialer"
                : "Open directory"}
            </a>
          </article>
        ))}
      </div>
    </section>
  );
}

type Position = { latitude: number; longitude: number; accuracy: number };
// Web safe app: Shows the location page view.
export function LocationPage() {
  const [position, setPosition] = useState<Position | null>(null);
  const [error, setError] = useState("");
  // Web safe app: returns the locate. It asks the device for its location and turns the answer into map data.
  const locate = () => {
    setError("");
    navigator.geolocation.getCurrentPosition(
      ({ coords }) =>
        setPosition({
          latitude: coords.latitude,
          longitude: coords.longitude,
          accuracy: coords.accuracy,
        }),
      () =>
        setError(
          "Location is unavailable. Check this site's location permission.",
        ),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 },
    );
  };
  useEffect(locate, []);
  const map = position
    ? `https://www.openstreetmap.org/export/embed.html?bbox=${position.longitude - 0.01}%2C${position.latitude - 0.007}%2C${position.longitude + 0.01}%2C${position.latitude + 0.007}&layer=mapnik&marker=${position.latitude}%2C${position.longitude}`
    : "";
  return (
    <section className="safe-feature-page">
      <div className="panel">
        <h2>Your current location</h2>
        <p>
          The browser requests location only while this page is open. Android
          remains responsible for background live sharing and navigation.
        </p>
        <Button onClick={locate}>
          <LocateFixed size={17} /> Update location
        </Button>
        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}
      </div>
      {position ? (
        <div className="panel safe-map">
          <iframe title="Current location map" src={map} loading="lazy" />
          <p>
            <MapPin size={16} /> Accuracy about {Math.round(position.accuracy)}{" "}
            m
          </p>
        </div>
      ) : (
        <Empty title="Waiting for location.">
          Allow location access to show your position.
        </Empty>
      )}
    </section>
  );
}

// Web safe app: Shows the camera page view.
export function CameraPage() {
  const session = useSession();
  const [preview, setPreview] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [progress, setProgress] = useState<number | null>(null);
  useEffect(
    () => () => {
      if (preview) URL.revokeObjectURL(preview);
    },
    [preview],
  );
  return (
    <section className="panel safe-feature-page">
      <h2>Take a photo or document image</h2>
      <p>
        Capture with your device or choose an existing file. Saved files are
        encrypted and shared with the same account vault as the phone app.
      </p>
      <label className="button">
        <Camera size={17} /> Open camera
        <input
          className="sr-only"
          type="file"
          accept="image/*,video/*"
          capture="environment"
          onChange={(event) => {
            const selected = event.target.files?.[0];
            if (selected) {
              setFile(selected);
              setPreview(URL.createObjectURL(selected));
            }
          }}
        />
      </label>
      {preview &&
        (file?.type.startsWith("video/") ? (
          <video
            className="camera-preview"
            src={preview}
            controls
            aria-label="Captured video preview"
          />
        ) : (
          <img
            className="camera-preview"
            src={preview}
            alt="Captured preview"
          />
        ))}
      {file && (
        <p>
          {file.name} · {(file.size / (1024 * 1024)).toFixed(1)} MB
        </p>
      )}
      {file && (
        <Button
          disabled={session.busy}
          onClick={() =>
            void session.perform(async () => {
              if (!accounts.identity)
                throw new Error("Sign in again before saving this file.");
              await uploadBrowserDocument(file, accounts.identity, setProgress);
              setFile(null);
              setPreview("");
              setProgress(null);
            })
          }
        >
          {progress === null
            ? "Save encrypted file"
            : `Uploading ${Math.round(progress * 100)}%`}
        </Button>
      )}
    </section>
  );
}

// Web safe app: Shows the documents page view.
export function DocumentsPage() {
  const session = useSession();
  const [documents, setDocuments] = useState<BrowserDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [uploadProgress, setUploadProgress] = useState<number | null>(null);
  // Reloads the latest data for web safe app.
  const refresh = async () => {
    const identity = accounts.identity;
    if (!identity) return;
    setLoading(true);
    setError("");
    try {
      setDocuments(await listBrowserDocuments(identity));
    } catch (failure) {
      setError(
        failure instanceof Error
          ? failure.message
          : "Unable to load encrypted documents.",
      );
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    void refresh();
  }, []);
  // Web safe app: Opens the requested item. It waits for the set time and cancels the timer when the feature closes.
  const open = (document: BrowserDocument) =>
    void session.perform(async () => {
      const tab = window.open("about:blank", "_blank");
      if (tab) tab.opener = null;
      // If tab fails, catch the error so the app can keep running and explain the problem.
      try {
        const url = await openBrowserDocument(document);
        if (tab) tab.location.replace(url);
        else window.location.assign(url);
        window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
      } catch (failure) {
        tab?.close();
        throw failure;
      }
    });
  return (
    <section className="safe-feature-page">
      <div className="panel documents-heading">
        <div>
          <Upload size={28} />
          <h2>Encrypted documents</h2>
          <p>
            Files stay encrypted in cloud storage and open for the signed-in
            account on phone or web.
          </p>
        </div>
        <div className="toolbar-actions">
          <Button
            variant="outline"
            onClick={() => void refresh()}
            disabled={loading}
          >
            <RefreshCw size={16} /> Refresh
          </Button>
          <label className="button outline">
            <Upload size={16} /> Upload a file
            <input
              className="sr-only"
              type="file"
              disabled={session.busy}
              onChange={(event) => {
                const file = event.target.files?.[0];
                if (!file) return;
                void session
                  .perform(async () => {
                    if (!accounts.identity)
                      throw new Error("Sign in again before uploading files.");
                    setError("");
                    setUploadProgress(0);
                    await uploadBrowserDocument(
                      file,
                      accounts.identity,
                      setUploadProgress,
                    );
                    setUploadProgress(null);
                    event.target.value = "";
                    await refresh();
                  })
                  .catch((failure) => {
                    setError(
                      failure instanceof Error
                        ? failure.message
                        : "Unable to upload this file.",
                    );
                    setUploadProgress(null);
                  });
              }}
            />
          </label>
        </div>
      </div>
      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}
      {uploadProgress !== null && (
        <p role="status">
          Encrypting and saving: {Math.round(uploadProgress * 100)}%
        </p>
      )}
      {loading ? (
        <Empty title="Loading documents…">
          Checking your account’s encrypted files.
        </Empty>
      ) : documents.length === 0 ? (
        <Empty title="No documents yet.">
          Upload a document from the Android app and it will appear here.
        </Empty>
      ) : (
        <div className="document-grid">
          {documents.map((document) => (
            <article className="panel browser-document" key={document.id}>
              <FileLock2 size={22} />
              <div>
                <h3>{document.name}</h3>
                <p>
                  {document.approved
                    ? "Ready to open securely on this device."
                    : document.approvalMessage}
                </p>
              </div>
              <Button
                variant={document.approved ? "primary" : "outline"}
                disabled={!document.approved || session.busy}
                onClick={() => open(document)}
              >
                {document.approved ? "Open document" : "Upgrade required"}
              </Button>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
