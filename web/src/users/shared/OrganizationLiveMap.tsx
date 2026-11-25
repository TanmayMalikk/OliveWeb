// Staff and admins: displays tracked user live and last-known locations on an interactive Mapbox map.
import { useEffect, useMemo, useRef, useState } from "react";
import type { Map as MapboxMap, Marker } from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";
import { Crosshair, ExternalLink, LocateFixed, MapPin, Search } from "lucide-react";
import { Button, Empty } from "../../components/SharedComponents";
import type { AdminLiveLocation } from "../../services/EmergencyResponseService";
import {
  filterAndSortLocations,
  openStreetMapUrl,
  type LocationStatusFilter,
} from "./OrganizationLiveMapModel";

const DEFAULT_CENTER: [number, number] = [-113.4938, 53.5461];
const STYLE = "mapbox://styles/tanmaymalik/cmwn3afx4002m01sr80u4bzdl";

export function OrganizationLiveMap({
  locations,
  loading = false,
  error = "",
  initialFollowUid = null,
  onClearInitialFollow,
}: {
  locations: Record<string, AdminLiveLocation>;
  loading?: boolean;
  error?: string;
  initialFollowUid?: string | null;
  onClearInitialFollow?: () => void;
}) {
  const mapContainerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapboxMap | null>(null);
  const mapboxRef = useRef<typeof import("mapbox-gl").default | null>(null);
  const markersRef = useRef<globalThis.Map<string, Marker>>(new globalThis.Map());
  const fittedOnceRef = useRef(false);

  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState<LocationStatusFilter>("all");
  const [selectedUid, setSelectedUid] = useState<string | null>(initialFollowUid);
  const [following, setFollowing] = useState<boolean>(Boolean(initialFollowUid));
  const [mapStatus, setMapStatus] = useState<"loading" | "ready" | "error">("loading");
  const [attempt, setAttempt] = useState(0);

  const allPoints = useMemo(() => filterAndSortLocations(locations, "", "all"), [locations]);
  const visiblePoints = useMemo(
    () => filterAndSortLocations(locations, search, filter),
    [locations, search, filter],
  );
  const liveCount = useMemo(() => allPoints.filter((p) => p.active).length, [allPoints]);
  const lastKnownCount = allPoints.length - liveCount;
  const selectedPoint = selectedUid ? locations[selectedUid] ?? null : null;

  useEffect(() => {
    if (!initialFollowUid) return;
    setSelectedUid(initialFollowUid);
    setFollowing(true);
    const point = locations[initialFollowUid];
    if (point && mapRef.current) {
      mapRef.current.flyTo({ center: [point.longitude, point.latitude], zoom: 15, essential: true });
      onClearInitialFollow?.();
    } else if (point) {
      onClearInitialFollow?.();
    }
  }, [initialFollowUid, locations, onClearInitialFollow]);

  useEffect(() => {
    if (!selectedUid && visiblePoints.length > 0 && !following) {
      setSelectedUid(visiblePoints[0].uid);
    }
  }, [selectedUid, visiblePoints, following]);

  useEffect(() => {
    const token = import.meta.env.VITE_MAPBOX_ACCESS_TOKEN?.trim();
    const container = mapContainerRef.current;
    if (!container || !token?.startsWith("pk.")) {
      setMapStatus("error");
      return;
    }

    let active = true;
    let instance: MapboxMap | undefined;
    const dispose = () => {
      window.clearTimeout(timeout);
      markersRef.current.forEach((marker) => marker.remove());
      markersRef.current.clear();
      instance?.remove();
      mapRef.current = null;
      mapboxRef.current = null;
      instance = undefined;
    };
    const unavailable = () => {
      active = false;
      dispose();
      setMapStatus("error");
    };
    const timeout = window.setTimeout(unavailable, 15000);

    void import("mapbox-gl")
      .then(({ default: mapbox }) => {
        if (!active) return;
        if (!mapbox.supported()) return unavailable();
        mapboxRef.current = mapbox;
        const firstPoint = selectedUid ? locations[selectedUid] : allPoints[0];
        const map = (instance = new mapbox.Map({
          container,
          accessToken: token,
          style: STYLE,
          center: firstPoint ? [firstPoint.longitude, firstPoint.latitude] : DEFAULT_CENTER,
          zoom: firstPoint ? 13 : 9,
          maxZoom: 18,
          projection: "mercator",
          dragRotate: false,
          pitchWithRotate: false,
          performanceMetricsCollection: false,
        }));
        mapRef.current = map;
        map.addControl(new mapbox.NavigationControl({ showCompass: false }), "top-right");
        map.once("idle", () => {
          window.clearTimeout(timeout);
          setMapStatus("ready");
        });
        map.on("dragstart", () => setFollowing(false));
        map.on("webglcontextlost", unavailable);
      })
      .catch(() => {
        if (active) unavailable();
      });

    return () => {
      active = false;
      dispose();
    };
  }, [attempt]);

  useEffect(() => {
    const map = mapRef.current;
    const mapbox = mapboxRef.current;
    if (!map || !mapbox || mapStatus !== "ready") return;

    const visibleIds = new Set(visiblePoints.map((point) => point.uid));
    markersRef.current.forEach((marker, uid) => {
      if (!visibleIds.has(uid)) {
        marker.remove();
        markersRef.current.delete(uid);
      }
    });

    for (const point of visiblePoints) {
      const isSelected = point.uid === selectedUid;
      let marker = markersRef.current.get(point.uid);
      if (!marker) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "org-map-marker";
        button.addEventListener("click", (event) => {
          event.stopPropagation();
          setSelectedUid(point.uid);
          setFollowing(true);
          map.flyTo({ center: [point.longitude, point.latitude], zoom: Math.max(map.getZoom(), 14), essential: true });
        });
        marker = new mapbox.Marker({ element: button, anchor: "center" })
          .setLngLat([point.longitude, point.latitude])
          .addTo(map);
        markersRef.current.set(point.uid, marker);
      } else {
        marker.setLngLat([point.longitude, point.latitude]);
      }
      const el = marker.getElement();
      el.className = `org-map-marker ${point.active ? "live" : "last-known"}${isSelected ? " selected" : ""}`;
      el.setAttribute(
        "aria-label",
        `${point.name}, ${point.active ? "live sharing" : "last known location"}`,
      );
      el.title = `${point.name} (${point.active ? "Live" : "Last known"})`;
    }

    if (!fittedOnceRef.current && allPoints.length > 0 && !initialFollowUid) {
      fittedOnceRef.current = true;
      fitPoints(map, mapbox, allPoints);
    }
  }, [visiblePoints, allPoints, selectedUid, mapStatus, initialFollowUid]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || mapStatus !== "ready" || !following || !selectedPoint) return;
    map.easeTo({
      center: [selectedPoint.longitude, selectedPoint.latitude],
      zoom: Math.max(map.getZoom(), 14),
      duration: 450,
    });
  }, [following, selectedPoint?.latitude, selectedPoint?.longitude, selectedPoint?.uid, mapStatus]);

  function fitPoints(
    map: MapboxMap,
    mapbox: typeof import("mapbox-gl").default,
    points: AdminLiveLocation[],
  ) {
    if (points.length === 0) return;
    if (points.length === 1) {
      map.flyTo({ center: [points[0].longitude, points[0].latitude], zoom: 14, essential: true });
      return;
    }
    const bounds = new mapbox.LngLatBounds(
      [points[0].longitude, points[0].latitude],
      [points[0].longitude, points[0].latitude],
    );
    for (const point of points.slice(1)) {
      bounds.extend([point.longitude, point.latitude]);
    }
    map.fitBounds(bounds, { padding: 64, maxZoom: 15, duration: 500 });
  }

  function selectUser(point: AdminLiveLocation) {
    setSelectedUid(point.uid);
    setFollowing(true);
    if (mapRef.current) {
      mapRef.current.flyTo({
        center: [point.longitude, point.latitude],
        zoom: Math.max(mapRef.current.getZoom(), 14),
        essential: true,
      });
    }
  }

  return (
    <section className="org-map-workspace" aria-label="Organization live location map">
      <aside className="panel org-map-sidebar">
        <div className="org-map-sidebar-head">
          <div>
            <h2>User locations</h2>
            <p>
              {liveCount} live · {lastKnownCount} last known
            </p>
          </div>
        </div>
        <label className="org-map-search">
          <Search size={16} aria-hidden="true" />
          <input
            type="search"
            value={search}
            aria-label="Search tracked users"
            placeholder="Search by name…"
            onChange={(event) => setSearch(event.currentTarget.value)}
          />
        </label>
        <div className="org-map-filters" role="group" aria-label="Filter tracked locations">
          {(
            [
              ["all", `All (${allPoints.length})`],
              ["live", `Live (${liveCount})`],
              ["last-known", `Last known (${lastKnownCount})`],
            ] as const
          ).map(([id, label]) => (
            <button
              key={id}
              type="button"
              aria-pressed={filter === id}
              onClick={() => setFilter(id)}
            >
              {label}
            </button>
          ))}
        </div>

        {error && (
          <p role="alert" className="form-error">
            {error}
          </p>
        )}

        {loading && allPoints.length === 0 ? (
          <p role="status" className="help-note">
            Loading live location feed…
          </p>
        ) : visiblePoints.length === 0 ? (
          <Empty
            title={
              allPoints.length === 0
                ? "No shared locations yet."
                : "No matching users."
            }
          >
            {allPoints.length === 0
              ? "Users who share live location or trigger an emergency alert appear here."
              : "Try clearing the search or filter."}
          </Empty>
        ) : (
          <div className="org-map-list" role="list" aria-label="Tracked accounts">
            {visiblePoints.map((point) => {
              const isSelected = point.uid === selectedUid;
              return (
                <button
                  key={point.uid}
                  type="button"
                  role="listitem"
                  aria-pressed={isSelected}
                  className={`org-map-user${isSelected ? " selected" : ""}`}
                  onClick={() => selectUser(point)}
                >
                  <span
                    className={`org-map-dot ${point.active ? "live" : "last-known"}`}
                    aria-hidden="true"
                  />
                  <span className="org-map-user-copy">
                    <strong>{point.name}</strong>
                    <small>
                      {point.active ? "Live" : "Last known"} ·{" "}
                      {new Date(point.updatedAt).toLocaleString()}
                    </small>
                  </span>
                </button>
              );
            })}
          </div>
        )}
      </aside>

      <div className="org-map-stage">
        <div className="panel org-map-canvas-card">
          <div className="faded-map-shell org-map-canvas-shell">
            <div
              ref={mapContainerRef}
              className="faded-map-canvas"
              role="region"
              aria-label="Organization location map canvas"
            />
            {mapStatus === "ready" && (
              <div className="org-map-overlay-controls">
                <Button
                  variant="outline"
                  onClick={() => {
                    setFollowing(false);
                    if (mapRef.current && mapboxRef.current) {
                      fitPoints(
                        mapRef.current,
                        mapboxRef.current,
                        visiblePoints.length > 0 ? visiblePoints : allPoints,
                      );
                    }
                  }}
                >
                  <LocateFixed size={15} aria-hidden="true" /> Fit all
                </Button>
                {selectedPoint && (
                  <Button
                    variant={following ? "" : "outline"}
                    onClick={() => selectUser(selectedPoint)}
                  >
                    <Crosshair size={15} aria-hidden="true" />{" "}
                    {following ? `Following ${selectedPoint.name}` : "Recenter"}
                  </Button>
                )}
              </div>
            )}
            {mapStatus === "loading" && (
              <div className="faded-map-status" role="status">
                Loading map…
              </div>
            )}
            {mapStatus === "error" && (
              <div className="faded-map-status" role="status">
                <span>
                  Interactive map preview is unavailable in this browser session. Select any user on the left to inspect
                  coordinates or open in OpenStreetMap.
                </span>
                <button
                  type="button"
                  className="text-link"
                  onClick={() => {
                    setMapStatus("loading");
                    setAttempt((value) => value + 1);
                  }}
                >
                  Retry map
                </button>
              </div>
            )}
          </div>
        </div>

        {selectedPoint ? (
          <article className="panel org-map-detail" aria-label="Selected user location details">
            <div className="org-map-detail-head">
              <div>
                <h3>
                  <MapPin size={17} aria-hidden="true" /> {selectedPoint.name}
                </h3>
                <p>
                  {selectedPoint.active ? "Live sharing active" : "Last known location"} · Updated{" "}
                  {new Date(selectedPoint.updatedAt).toLocaleString()}
                </p>
              </div>
              <span className={`tag ${selectedPoint.active ? "emergency-badge" : ""}`}>
                {selectedPoint.active ? "Live" : "Last known"}
              </span>
            </div>
            <p className="org-map-coords">
              Coordinates: {selectedPoint.latitude.toFixed(5)}, {selectedPoint.longitude.toFixed(5)}
              {selectedPoint.accuracy !== null
                ? ` (±${Math.round(selectedPoint.accuracy)} m)`
                : ""}
            </p>
            <a
              className="text-link"
              href={openStreetMapUrl(selectedPoint.latitude, selectedPoint.longitude)}
              target="_blank"
              rel="noopener noreferrer"
            >
              Open in OpenStreetMap <ExternalLink size={14} aria-hidden="true" />
            </a>
          </article>
        ) : (
          <article className="panel org-map-detail">
            <p>Select a user from the list or click a marker on the map to view live coordinates.</p>
          </article>
        )}
      </div>
    </section>
  );
}
