// Safe-app users: provides staff requests, support directories, browser location, and camera capture.
import { useEffect, useState } from "react";
import { Camera, ExternalLink, LocateFixed, MapPin, Phone, Upload } from "lucide-react";
import { Button, Empty } from "../../../components/SharedComponents";
import { accounts, cloudMode, firebase } from "../../../services/AccountService";
import { useSession } from "../../../session/SessionProvider";

async function callable<T>(name: string, data: unknown): Promise<T> {
  if (!cloudMode || !accounts.identity) throw new Error("Sign in to your shared account first.");
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  return (await httpsCallable(functions, name)(data)).data as T;
}

export function StaffHelpPage() {
  const session = useSession();
  const [ticket, setTicket] = useState<{ requested: boolean; staffName?: string; conversationId?: string } | null>(null);
  return <section className="panel safe-feature-page"><h2>Request help from staff</h2>
    <p>This opens a private support ticket. It does not share your safety plan.</p>
    {ticket?.requested ? <div className="status-banner"><strong>{ticket.staffName ? "Olive Staff is assigned." : "Looking for an available staff member…"}</strong>
      <Button variant="outline" onClick={() => session.setPage("Messages")}>Open support chats</Button>
      <Button variant="quiet" onClick={() => void session.perform(async () => {
        const result = await callable<{ ticket: typeof ticket }>("staffSupportRequest", { action: "stop" });
        setTicket(result.ticket);
      })}>Cancel request</Button></div>
      : <Button disabled={session.busy} onClick={() => void session.perform(async () => {
        const result = await callable<{ ticket: typeof ticket }>("staffSupportRequest", { action: "request" });
        setTicket(result.ticket);
      })}>Request help from staff</Button>}
  </section>;
}

const resources = [
  { name: "Emergency services", description: "Immediate danger or urgent medical help", phone: "911", url: "tel:911" },
  { name: "211 Canada", description: "Local social, community, and crisis services", phone: "211", url: "tel:211" },
  { name: "Government of Canada help directory", description: "Province and territory violence-support services", phone: "Online directory", url: "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html" },
  { name: "ShelterSafe", description: "Find nearby shelters across Canada", phone: "Online directory", url: "https://sheltersafe.ca/" },
];
export function HotlinesPage() {
  return <section className="safe-feature-page"><div className="panel"><h2>Support hotlines and shelters</h2>
    <p>Calling links open your device dialer and do not place a call automatically.</p></div>
    <div className="safe-resource-grid">{resources.map((resource) => <article className="panel" key={resource.name}>
      <h3>{resource.name}</h3><p>{resource.description}</p><strong>{resource.phone}</strong>
      <a className="button outline" href={resource.url} target={resource.url.startsWith("https") ? "_blank" : undefined}
        rel="noreferrer">{resource.url.startsWith("tel") ? <Phone size={17} /> : <ExternalLink size={17} />}
        {resource.url.startsWith("tel") ? "Open dialer" : "Open directory"}</a>
    </article>)}</div></section>;
}

type Position = { latitude: number; longitude: number; accuracy: number };
export function LocationPage() {
  const [position, setPosition] = useState<Position | null>(null);
  const [error, setError] = useState("");
  const locate = () => {
    setError("");
    navigator.geolocation.getCurrentPosition(
      ({ coords }) => setPosition({ latitude: coords.latitude, longitude: coords.longitude, accuracy: coords.accuracy }),
      () => setError("Location is unavailable. Check this site's location permission."),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 5000 },
    );
  };
  useEffect(locate, []);
  const map = position ? `https://www.openstreetmap.org/export/embed.html?bbox=${position.longitude - .01}%2C${position.latitude - .007}%2C${position.longitude + .01}%2C${position.latitude + .007}&layer=mapnik&marker=${position.latitude}%2C${position.longitude}` : "";
  return <section className="safe-feature-page"><div className="panel"><h2>Your current location</h2>
    <p>The browser requests location only while this page is open. Android remains responsible for background live sharing and navigation.</p>
    <Button onClick={locate}><LocateFixed size={17} /> Update location</Button>{error && <p role="alert" className="form-error">{error}</p>}</div>
    {position ? <div className="panel safe-map"><iframe title="Current location map" src={map} loading="lazy" />
      <p><MapPin size={16} /> Accuracy about {Math.round(position.accuracy)} m</p></div>
      : <Empty title="Waiting for location.">Allow location access to show your position.</Empty>}
  </section>;
}

export function CameraPage() {
  const [preview, setPreview] = useState("");
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);
  return <section className="panel safe-feature-page"><h2>Take a photo or document image</h2>
    <p>Use the device camera or choose an existing image. Saving encrypted media to the shared Android vault is being kept unavailable until this browser has a compatible device encryption key.</p>
    <label className="button"><Camera size={17} /> Open camera<input className="sr-only" type="file" accept="image/*" capture="environment"
      onChange={(event) => { const file = event.target.files?.[0]; if (file) setPreview(URL.createObjectURL(file)); }} /></label>
    {preview && <img className="camera-preview" src={preview} alt="Captured preview" />}
  </section>;
}

export function DocumentsPage() {
  return <section className="panel safe-feature-page"><Upload size={32} /><h2>Encrypted documents</h2>
    <p>Your Android files remain encrypted with device-held keys. The web app will not weaken that protection by uploading plaintext or pretending it can decrypt another device’s files.</p>
    <Button onClick={() => location.assign("/download")}>Open Android download instructions</Button>
  </section>;
}
