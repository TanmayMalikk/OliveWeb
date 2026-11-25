// @vitest-environment happy-dom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AdminLiveLocation } from "../../services/EmergencyResponseService";
import { installDomMocks } from "../../test-utils/domTestHarness";

const mapMocks = vi.hoisted(() => {
  const createdMaps: Array<{
    listeners: Array<{ event: string; layer?: string; cb: (e?: unknown) => void }>;
    onceListeners: Array<{ event: string; cb: (e?: unknown) => void }>;
    sources: Record<string, { setData: ReturnType<typeof vi.fn> }>;
    layers: Set<string>;
    flyTo: ReturnType<typeof vi.fn>;
    easeTo: ReturnType<typeof vi.fn>;
    fitBounds: ReturnType<typeof vi.fn>;
    jumpTo: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
    addSource: ReturnType<typeof vi.fn>;
    canvas: { style: { cursor: string } };
  }> = [];
  const createdMarkers: Array<{
    listeners: Record<string, () => void>;
    element: HTMLElement;
    lngLat: [number, number];
    setLngLat: ReturnType<typeof vi.fn>;
    addTo: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  }> = [];
  const createdPopups: Array<{
    setLngLat: ReturnType<typeof vi.fn>;
    setDOMContent: ReturnType<typeof vi.fn>;
    addTo: ReturnType<typeof vi.fn>;
    remove: ReturnType<typeof vi.fn>;
  }> = [];

  return {
    supported: true,
    createdMaps,
    createdMarkers,
    createdPopups,
    reset() {
      this.supported = true;
      this.createdMaps.length = 0;
      this.createdMarkers.length = 0;
      this.createdPopups.length = 0;
    },
  };
});

vi.mock("mapbox-gl/dist/mapbox-gl.css", () => ({}));

vi.mock("mapbox-gl", () => {
  class MockMap {
    listeners: Array<{ event: string; layer?: string; cb: (e?: unknown) => void }> = [];
    onceListeners: Array<{ event: string; cb: (e?: unknown) => void }> = [];
    sources: Record<string, { setData: ReturnType<typeof vi.fn> }> = {};
    layers = new Set<string>();
    canvas = { style: { cursor: "" } };
    flyTo = vi.fn();
    easeTo = vi.fn();
    fitBounds = vi.fn();
    jumpTo = vi.fn();
    remove = vi.fn();
    addControl = vi.fn();
    getZoom = () => 11;
    getCanvas = () => this.canvas;
    addSource = vi.fn((id: string) => {
      this.sources[id] = { setData: vi.fn() };
    });
    getSource = (id: string) => this.sources[id];
    addLayer = vi.fn((layer: { id: string }) => {
      this.layers.add(layer.id);
    });
    on = (event: string, layerOrCb: unknown, maybeCb?: (e?: unknown) => void) => {
      if (typeof layerOrCb === "function") {
        this.listeners.push({ event, cb: layerOrCb as (e?: unknown) => void });
      } else if (maybeCb) {
        this.listeners.push({ event, cb: maybeCb, layer: String(layerOrCb) });
      }
    };
    once = (event: string, cb: (e?: unknown) => void) => {
      this.onceListeners.push({ event, cb });
    };
    constructor() {
      mapMocks.createdMaps.push(this);
    }
  }

  class MockMarker {
    listeners: Record<string, () => void> = {};
    element: HTMLElement;
    lngLat: [number, number] = [0, 0];
    setLngLat = vi.fn((coords: [number, number]) => {
      this.lngLat = coords;
      return this;
    });
    addTo = vi.fn(() => this);
    remove = vi.fn();
    getElement = () => this.element;
    on = vi.fn((event: string, cb: () => void) => {
      this.listeners[event] = cb;
      return this;
    });
    getLngLat = () => ({ wrap: () => ({ lat: 53.6, lng: -113.5 }) });
    constructor(opts?: { element?: HTMLElement }) {
      this.element = opts?.element ?? document.createElement("div");
      mapMocks.createdMarkers.push(this);
    }
  }

  class MockPopup {
    setLngLat = vi.fn(() => this);
    setDOMContent = vi.fn(() => this);
    addTo = vi.fn(() => this);
    remove = vi.fn();
    constructor() {
      mapMocks.createdPopups.push(this);
    }
  }

  class MockLngLatBounds {
    extend = vi.fn(() => this);
    isEmpty = () => false;
  }

  class MockNavigationControl {}

  return {
    default: {
      supported: () => mapMocks.supported,
      Map: MockMap,
      Marker: MockMarker,
      Popup: MockPopup,
      LngLatBounds: MockLngLatBounds,
      NavigationControl: MockNavigationControl,
    },
  };
});

import { FadedMap } from "./FadedMap";
import { OrganizationLiveMap } from "./OrganizationLiveMap";

const locationsFixture: Record<string, AdminLiveLocation> = {
  "u-live": {
    uid: "u-live",
    name: "Alex Rivera",
    latitude: 53.5461,
    longitude: -113.4938,
    accuracy: 10,
    active: true,
    updatedAt: 1700000000000,
  },
  "u-last": {
    uid: "u-last",
    name: "Sam Lee",
    latitude: 51.0447,
    longitude: -114.0719,
    accuracy: null,
    active: false,
    updatedAt: 1699900000000,
  },
};

describe("FadedMap and OrganizationLiveMap", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mapMocks.reset();
    vi.stubEnv("VITE_MAPBOX_ACCESS_TOKEN", "pk.test_token_12345");
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllEnvs();
  });

  it("drives FadedMap token error, retry, ready state, markers, click/dragend callbacks, and webgl error", async () => {
    vi.stubEnv("VITE_MAPBOX_ACCESS_TOKEN", "");
    const onSelect = vi.fn();
    const { rerender } = render(
      <FadedMap
        className="inc-map"
        label="Incident map"
        selected={{ latitude: 53.54, longitude: -113.49 }}
        current={{ latitude: 53.55, longitude: -113.50 }}
        onSelect={onSelect}
      />,
    );
    expect(screen.getByText("Map unavailable.")).toBeTruthy();

    vi.stubEnv("VITE_MAPBOX_ACCESS_TOKEN", "pk.test_token_12345");
    fireEvent.click(screen.getByRole("button", { name: "Retry map" }));

    await waitFor(() => expect(mapMocks.createdMaps.length).toBeGreaterThan(0));
    const map = mapMocks.createdMaps.at(-1)!;
    expect(mapMocks.createdMarkers.length).toBe(2);

    // Emit idle -> ready, click map, drag marker, update coordinates, trigger webglcontextlost.
    await act(async () => {
      map.onceListeners.find((l) => l.event === "idle")?.cb();
    });
    expect(map.jumpTo).toHaveBeenCalled();

    const clickListener = map.listeners.find((l) => l.event === "click");
    clickListener?.cb({ lngLat: { lat: 53.7, wrap: () => ({ lng: -113.8 }) } });
    expect(onSelect).toHaveBeenCalledWith({ latitude: 53.7, longitude: -113.8 });

    mapMocks.createdMarkers[0].listeners.dragend?.();
    expect(onSelect).toHaveBeenCalledWith({ latitude: 53.6, longitude: -113.5 });

    rerender(
      <FadedMap
        className="inc-map"
        label="Incident map"
        selected={undefined}
        current={{ latitude: 53.56, longitude: -113.51 }}
        onSelect={onSelect}
      />,
    );
    expect(mapMocks.createdMarkers[0].remove).toHaveBeenCalled();

    await act(async () => {
      map.listeners.find((l) => l.event === "webglcontextlost")?.cb();
    });
    expect(screen.getByText("Map unavailable.")).toBeTruthy();
  });

  it("drives OrganizationLiveMap filters, search, layers, click/hover popup, Fit all, Recenter, and initialFollowUid", async () => {
    const onClearInitialFollow = vi.fn();

    // Empty / loading / error states first.
    vi.stubEnv("VITE_MAPBOX_ACCESS_TOKEN", "");
    const { rerender } = render(
      <OrganizationLiveMap locations={{}} loading error="Feed warning" />,
    );
    expect(screen.getByText("Feed warning")).toBeTruthy();
    expect(screen.getByText(/Loading live location feed/i)).toBeTruthy();
    expect(screen.getByText(/Interactive map preview is unavailable/i)).toBeTruthy();

    // Retry with valid token and 2 points.
    vi.stubEnv("VITE_MAPBOX_ACCESS_TOKEN", "pk.test_token_12345");
    rerender(
      <OrganizationLiveMap
        locations={locationsFixture}
        initialFollowUid="u-live"
        onClearInitialFollow={onClearInitialFollow}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Retry map" }));

    await waitFor(() => expect(mapMocks.createdMaps.length).toBeGreaterThan(0));
    const map = mapMocks.createdMaps.at(-1)!;
    await act(async () => {
      map.onceListeners.find((l) => l.event === "load")?.cb();
    });
    expect(map.addSource).toHaveBeenCalledWith("org-locations", expect.anything());
    expect(map.layers.size).toBe(4);

    // Search & filters.
    fireEvent.click(screen.getByRole("button", { name: /Live \(1\)/i }));
    fireEvent.click(screen.getByRole("button", { name: /Last known \(1\)/i }));
    fireEvent.click(screen.getByRole("button", { name: /All \(2\)/i }));
    fireEvent.change(screen.getByLabelText("Search tracked users"), { target: { value: "zzzz" } });
    expect(screen.getByText("No matching users.")).toBeTruthy();
    fireEvent.change(screen.getByLabelText("Search tracked users"), { target: { value: "" } });

    // Select user from list -> flyTo + detail panel + OpenStreetMap link.
    fireEvent.click(screen.getByText("Sam Lee").closest("button")!);
    expect(map.flyTo).toHaveBeenCalled();
    expect(screen.getByLabelText("Selected user location details").textContent).toContain("51.04470, -114.07190");

    // Drag stops following -> Recenter button resumes -> Fit all fits bounds.
    await act(async () => {
      map.listeners.find((l) => l.event === "dragstart")?.cb();
    });
    fireEvent.click(screen.getByRole("button", { name: "Recenter" }));
    fireEvent.click(screen.getByRole("button", { name: "Fit all" }));
    expect(map.fitBounds).toHaveBeenCalled();

    // Hover & click on rendered Mapbox feature.
    const featureEvent = {
      features: [
        {
          properties: { uid: "u-live", name: "Alex Rivera", active: true },
          geometry: { type: "Point", coordinates: [-113.4938, 53.5461] },
        },
      ],
    };
    await act(async () => {
      map.listeners.find((l) => l.event === "mousemove")?.cb(featureEvent);
      map.listeners.find((l) => l.event === "mouseleave")?.cb();
      map.listeners.find((l) => l.event === "click")?.cb(featureEvent);
    });
    expect(mapMocks.createdPopups[0].setDOMContent).toHaveBeenCalled();
    expect(mapMocks.createdPopups[0].remove).toHaveBeenCalled();
    expect(screen.getByLabelText("Selected user location details").textContent).toContain("Alex Rivera");

    // Coordinate move while following triggers easeTo; zoomstart with originalEvent stops following.
    rerender(
      <OrganizationLiveMap
        locations={{
          ...locationsFixture,
          "u-live": { ...locationsFixture["u-live"], latitude: 53.55, longitude: -113.5 },
        }}
        initialFollowUid={null}
        onClearInitialFollow={onClearInitialFollow}
      />,
    );
    expect(map.easeTo).toHaveBeenCalled();
    await act(async () => {
      map.listeners.find((l) => l.event === "zoomstart")?.cb({ originalEvent: {} });
    });

    // Unsupported WebGL error path on retry.
    mapMocks.supported = false;
    await act(async () => {
      map.listeners.find((l) => l.event === "webglcontextlost")?.cb();
    });
    fireEvent.click(screen.getByRole("button", { name: "Retry map" }));
    await waitFor(() => expect(screen.getByText(/Interactive map preview is unavailable/i)).toBeTruthy());
  });
});
