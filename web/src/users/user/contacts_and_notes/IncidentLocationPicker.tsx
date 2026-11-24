// User incident notes: lets the user search for an address or place a pin on a map.
import { useEffect, useRef, useState } from "react";
import { MapPin } from "lucide-react";
import { FadedMap } from "../../shared/FadedMap";

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

// Keeps the current GPS dot separate from the selected incident pin.
function IncidentMap({ latitude, longitude, onChange }: Omit<IncidentLocationPickerProps, "label">) {
  const [current, setCurrent] = useState<{ latitude: number; longitude: number }>();
  const selected = latitude !== undefined && longitude !== undefined &&
    Number.isFinite(latitude) && Number.isFinite(longitude) ? { latitude, longitude } : undefined;
  return (
    <div className="incident-map-wrap">
      <button type="button" className="text-link" onClick={() =>
        navigator.geolocation?.getCurrentPosition(({ coords }) =>
          setCurrent({ latitude: coords.latitude, longitude: coords.longitude }),
        )
      }>
        Show my current location
      </button>
      <FadedMap
        className="incident-map"
        label="Incident location map"
        current={current}
        selected={selected}
        onSelect={(point) => onChange({
          ...point,
          label: "Dropped pin (" + point.latitude.toFixed(5) + ", " + point.longitude.toFixed(5) + ")",
        })}
      />
      <small>Click the map to drop a pin. You can drag the pin to adjust it.</small>
    </div>
  );
}

const MAX_CACHE_ENTRIES = 64;
const addressCache = new Map<string, LocationResult[]>();

// Keep requests responsive at 50ms while cancelling stale in-flight lookups on rapid typing.
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
    const trimmed = query.trim();
    if (trimmed.length < 2 || query === label) {
      setResults([]);
      return;
    }
    const cacheKey = trimmed.toLowerCase().replace(/\s+/g, " ");
    const cached = addressCache.get(cacheKey);
    if (cached) {
      setResults(cached);
      setMessage("");
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      fetch(`/api/locations?q=${encodeURIComponent(trimmed)}`, {
        signal: controller.signal,
      })
        .then(async (response) => {
          const body = await response.json();
          if (!response.ok) throw new Error(body.error ?? "Address search failed.");
          const locations: LocationResult[] = body.locations ?? [];
          if (addressCache.size >= MAX_CACHE_ENTRIES) {
            const oldestKey = addressCache.keys().next().value;
            if (oldestKey !== undefined) addressCache.delete(oldestKey);
          }
          addressCache.set(cacheKey, locations);
          setResults(locations);
          setMessage("");
        })
        .catch((failure) => {
          if (failure.name !== "AbortError") setMessage(failure.message);
        });
    }, 50);
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
