import { useEffect, useRef, useState } from "react";
import type { Map as MapboxMap, Marker } from "mapbox-gl";
import "mapbox-gl/dist/mapbox-gl.css";

type Coordinates = { latitude: number; longitude: number };
type MapProps = {
  label: string;
  className: string;
  selected?: Coordinates;
  current?: Coordinates;
  onSelect?: (position: Coordinates) => void;
};

const DEFAULT_CENTER: [number, number] = [-113.4938, 53.5461];
const STYLE = "mapbox://styles/tanmaymalik/cmwn3afx4002m01sr80u4bzdl";
const lngLat = ({ longitude, latitude }: Coordinates): [number, number] => [longitude, latitude];

export function FadedMap(props: MapProps) {
  const { selected, current, label, className } = props;
  const element = useRef<HTMLDivElement>(null);
  const initial = useRef(selected ?? current);
  const latest = useRef(props);
  const movePins = useRef<(() => void) | null>(null);
  const centerMap = useRef<((position: Coordinates) => void) | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [attempt, setAttempt] = useState(0);
  latest.current = props;

  useEffect(() => {
    const token = import.meta.env.VITE_MAPBOX_ACCESS_TOKEN?.trim();
    const container = element.current;
    if (!container || !token?.startsWith("pk.")) {
      setStatus("error");
      return;
    }

    let active = true;
    let instance: MapboxMap | undefined;
    let incidentPin: Marker | undefined;
    let currentPin: Marker | undefined;
    const dispose = () => {
      window.clearTimeout(timeout);
      movePins.current = null;
      centerMap.current = null;
      incidentPin?.remove();
      currentPin?.remove();
      instance?.remove();
      instance = undefined;
    };
    const unavailable = () => {
      active = false;
      dispose();
      setStatus("error");
    };
    const timeout = window.setTimeout(unavailable, 15000);
    void import("mapbox-gl").then(({ default: mapbox }) => {
      if (!active) return;
      if (!mapbox.supported()) return unavailable();
      const first = initial.current;
      const map = instance = new mapbox.Map({
        container,
        accessToken: token,
        style: STYLE,
        center: first ? lngLat(first) : DEFAULT_CENTER,
        zoom: first ? 14 : 9,
        maxZoom: 18,
        projection: "mercator",
        scrollZoom: false,
        dragRotate: false,
        pitchWithRotate: false,
        performanceMetricsCollection: false,
      });
      map.addControl(new mapbox.NavigationControl({ showCompass: false }), "top-right");
      centerMap.current = (position) => { map.jumpTo({ center: lngLat(position), zoom: 14 }); };
      map.once("idle", () => {
        window.clearTimeout(timeout);
        setStatus("ready");
      });
      map.on("webglcontextlost", unavailable);
      map.on("click", ({ lngLat: point }) =>
        latest.current.onSelect?.({ latitude: point.lat, longitude: point.wrap().lng }),
      );
      movePins.current = () => {
        const { selected, current } = latest.current;
        if (selected) {
          if (!incidentPin) {
            incidentPin = new mapbox.Marker({ color: "#354E2C", draggable: true });
            incidentPin.getElement().setAttribute("aria-label", "Incident pin");
            incidentPin.on("dragend", () => {
              if (!incidentPin) return;
              const point = incidentPin.getLngLat().wrap();
              latest.current.onSelect?.({ latitude: point.lat, longitude: point.lng });
            });
            incidentPin.setLngLat(lngLat(selected)).addTo(map);
          } else incidentPin.setLngLat(lngLat(selected));
        } else {
          incidentPin?.remove();
          incidentPin = undefined;
        }
        if (current) {
          if (!currentPin) {
            const dot = document.createElement("div");
            dot.className = "faded-map-current-dot";
            dot.setAttribute("role", "img");
            dot.setAttribute("aria-label", "Your current location");
            currentPin = new mapbox.Marker({ element: dot }).setLngLat(lngLat(current)).addTo(map);
          } else currentPin.setLngLat(lngLat(current));
        }
      };
      movePins.current();
    }).catch(() => { if (active) unavailable(); });

    return () => {
      active = false;
      dispose();
    };
  }, [attempt]);

  useEffect(() => {
    movePins.current?.();
  }, [selected, current, status]);

  useEffect(() => {
    if (selected) centerMap.current?.(selected);
  }, [selected?.latitude, selected?.longitude, status]);
  useEffect(() => {
    if (current) centerMap.current?.(current);
  }, [current?.latitude, current?.longitude, status]);

  return (
    <div className={"faded-map-shell " + className}>
      <div ref={element} className="faded-map-canvas" role="region" aria-label={label} />
      {status === "loading" && <div className="faded-map-status" role="status">Loading map…</div>}
      {status === "error" && (
        <div className="faded-map-status" role="status">
          <span>Map unavailable.</span>
          <button type="button" className="text-link" onClick={() => {
            setStatus("loading");
            setAttempt((value) => value + 1);
          }}>Retry map</button>
        </div>
      )}
    </div>
  );
}
