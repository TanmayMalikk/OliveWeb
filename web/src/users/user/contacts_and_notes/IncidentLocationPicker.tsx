// User incident notes: lets the user search for an address or place a pin on a map.
import { useEffect, useRef, useState } from "react";
import L from "leaflet";
import { MapPin } from "lucide-react";
import "leaflet/dist/leaflet.css";
import markerUrl from "leaflet/dist/images/marker-icon.png?url";
import retinaMarkerUrl from "leaflet/dist/images/marker-icon-2x.png?url";
import shadowUrl from "leaflet/dist/images/marker-shadow.png?url";

type LocationResult = {
  label: string;
  latitude: number;
  longitude: number;
};

type IncidentLocationPickerProps = {
  label: string;
  latitude?: number;
  longitude?: number;
  onChange: (result: LocationResult) => void;
};

const DEFAULT_CENTER: [number, number] = [53.5461, -113.4938];
const INCIDENT_PIN = L.icon({
  iconUrl: markerUrl,
  iconRetinaUrl: retinaMarkerUrl,
  shadowUrl,
  iconSize: [25, 41],
  iconAnchor: [12, 41],
  shadowSize: [41, 41],
});

function droppedPin(point: L.LatLng): LocationResult {
  return {
    label: "Dropped pin (" + point.lat.toFixed(5) + ", " + point.lng.toFixed(5) + ")",
    latitude: point.lat,
    longitude: point.lng,
  };
}

// The map keeps the saved incident pin separate from the user's current red location dot.
function IncidentMap({ latitude, longitude, onChange }: Omit<IncidentLocationPickerProps, "label">) {
  const element = useRef<HTMLDivElement>(null);
  const map = useRef<L.Map | null>(null);
  const selectedMarker = useRef<L.Marker | null>(null);
  const currentMarker = useRef<L.CircleMarker | null>(null);
  const latestChange = useRef(onChange);

  function placePin(instance: L.Map, point: L.LatLng) {
    if (selectedMarker.current) {
      selectedMarker.current.setLatLng(point);
      return;
    }
    const pin = L.marker(point, { icon: INCIDENT_PIN, draggable: true, alt: "Incident pin" })
      .addTo(instance);
    pin.on("dragend", () => latestChange.current(droppedPin(pin.getLatLng())));
    selectedMarker.current = pin;
  }

  useEffect(() => {
    latestChange.current = onChange;
  }, [onChange]);

  // This builds the map once and reports map clicks or pin drags to the incident form.
  useEffect(() => {
    if (!element.current || map.current) return;
    const initial: [number, number] =
      Number.isFinite(latitude) && Number.isFinite(longitude)
        ? [latitude!, longitude!]
        : DEFAULT_CENTER;
    const instance = L.map(element.current, {
      center: initial,
      zoom: Number.isFinite(latitude) ? 15 : 10,
      scrollWheelZoom: false,
    });
    L.tileLayer("/api/map-tiles/{z}/{x}/{y}.png", {
      attribution: "© OpenStreetMap contributors · Tiles by Geoapify",
      maxZoom: 19,
    }).addTo(instance);

    instance.on("click", (event: L.LeafletMouseEvent) => {
      placePin(instance, event.latlng);
      latestChange.current(droppedPin(event.latlng));
    });
    map.current = instance;
    return () => {
      instance.remove();
      map.current = null;
      selectedMarker.current = null;
      currentMarker.current = null;
    };
  }, []);

  // This moves the draggable pin when the user chooses an address suggestion.
  useEffect(() => {
    if (!map.current) return;
    if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
      selectedMarker.current?.remove();
      selectedMarker.current = null;
      return;
    }
    const point = L.latLng(latitude!, longitude!);
    placePin(map.current, point);
    map.current.setView(point, 15);
  }, [latitude, longitude]);

  // The browser location is shown as a red dot and does not replace the incident pin.
  function showCurrentLocation() {
    navigator.geolocation?.getCurrentPosition((position) => {
      if (!map.current) return;
      const point = L.latLng(position.coords.latitude, position.coords.longitude);
      if (!currentMarker.current) {
        currentMarker.current = L.circleMarker(point, {
          radius: 8,
          color: "#ffffff",
          weight: 2,
          fillColor: "#c7352b",
          fillOpacity: 1,
        }).addTo(map.current).bindTooltip("Your current location");
      } else {
        currentMarker.current.setLatLng(point);
      }
      map.current.setView(point, 15);
    });
  }

  return (
    <div className="incident-map-wrap">
      <button type="button" className="text-link" onClick={showCurrentLocation}>
        Show my current location
      </button>
      <div
        ref={element}
        className="incident-map"
        role="application"
        aria-label="Incident location map. Click the map or drag the pin to save a location."
      />
      <small>Click the map to drop a pin. You can drag the pin to adjust it.</small>
    </div>
  );
}

// The address field waits briefly after typing so it does not send a request for every key press.
export function IncidentLocationPicker({
  label,
  latitude,
  longitude,
  onChange,
}: IncidentLocationPickerProps) {
  const [query, setQuery] = useState(label);
  const [results, setResults] = useState<LocationResult[]>([]);
  const [message, setMessage] = useState("");
  const [mapOpen, setMapOpen] = useState(false);
  const reverseLookup = useRef<AbortController | null>(null);

  useEffect(() => () => reverseLookup.current?.abort(), []);

  useEffect(() => setQuery(label), [label]);
  useEffect(() => {
    if (query.trim().length < 2 || query === label) {
      setResults([]);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      fetch(`/api/locations?q=${encodeURIComponent(query.trim())}`, {
        signal: controller.signal,
      })
        .then(async (response) => {
          const body = await response.json();
          if (!response.ok) throw new Error(body.error ?? "Address search failed.");
          setResults(body.locations ?? []);
          setMessage("");
        })
        .catch((failure) => {
          if (failure.name !== "AbortError") setMessage(failure.message);
        });
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query, label]);

  // A map selection is reverse-geocoded when possible, but coordinates remain usable if lookup fails.
  function chooseMapPoint(result: LocationResult) {
    reverseLookup.current?.abort();
    const controller = new AbortController();
    reverseLookup.current = controller;
    chooseLocation(result);
    void fetch("/api/locations?latitude=" + result.latitude + "&longitude=" + result.longitude, {
      signal: controller.signal,
    })
      .then(async (response) => {
        const body = await response.json();
        const label = body.locations?.[0]?.label;
        if (!controller.signal.aborted && response.ok && typeof label === "string" && label) {
          chooseLocation({ ...result, label });
        }
      })
      .catch(() => undefined);
  }

  function chooseLocation(result: LocationResult) {
    onChange(result);
    setQuery(result.label);
    setResults([]);
  }

  return (
    <div className="incident-location-picker">
      <div className="field">
        <label htmlFor="incident-location-input">Location of incident</label>
        <div className="incident-location-input">
          <input
            id="incident-location-input"
            value={query}
            onChange={(event) => {
              reverseLookup.current?.abort();
              chooseLocation({ label: event.target.value, latitude: NaN, longitude: NaN });
            }}
            autoComplete="off"
            required
          />
          <button
            type="button"
            aria-label={mapOpen ? "Hide incident location map" : "Choose incident location on map"}
            aria-expanded={mapOpen}
            onClick={() => setMapOpen((open) => !open)}
          >
            <MapPin aria-hidden="true" size={20} />
          </button>
        </div>
      </div>
      {results.length > 0 && (
        <ul className="incident-address-results" aria-label="Address suggestions">
          {results.map((result) => (
            <li key={`${result.latitude}:${result.longitude}`}>
              <button type="button" onClick={() => {
                  reverseLookup.current?.abort();
                  chooseLocation(result);
                }}>
                {result.label}
              </button>
            </li>
          ))}
        </ul>
      )}
      {message && <small className="error">{message} You can still type the location or use the map.</small>}
      {mapOpen && <IncidentMap latitude={latitude} longitude={longitude} onChange={chooseMapPoint} />}
    </div>
  );
}
