// Staff and admins: sorts and filters tracked user locations for the organization map.
import type { AdminLiveLocation } from "../../services/EmergencyResponseService";

export type LocationStatusFilter = "all" | "live" | "last-known";

export function filterAndSortLocations(
  locations: Record<string, AdminLiveLocation>,
  search: string,
  filter: LocationStatusFilter = "all",
): AdminLiveLocation[] {
  const query = search.trim().toLowerCase();
  return Object.values(locations)
    .filter((point) => {
      if (filter === "live" && !point.active) return false;
      if (filter === "last-known" && point.active) return false;
      if (!query) return true;
      return point.name.toLowerCase().includes(query) || point.uid.toLowerCase().includes(query);
    })
    .sort((a, b) => Number(b.active) - Number(a.active) || b.updatedAt - a.updatedAt || a.name.localeCompare(b.name));
}

export type LocationFeatureProperties = {
  uid: string;
  name: string;
  active: boolean;
  selected: boolean;
  updatedAt: number;
};

export type LocationFeature = {
  type: "Feature";
  geometry: {
    type: "Point";
    coordinates: [number, number];
  };
  properties: LocationFeatureProperties;
};

export type LocationFeatureCollection = {
  type: "FeatureCollection";
  features: LocationFeature[];
};

export function toLocationFeatureCollection(
  points: AdminLiveLocation[],
  selectedUid: string | null,
): LocationFeatureCollection {
  return {
    type: "FeatureCollection",
    features: points
      .filter((point) => Number.isFinite(point.latitude) && Number.isFinite(point.longitude))
      .map((point) => ({
        type: "Feature",
        geometry: {
          type: "Point",
          coordinates: [point.longitude, point.latitude],
        },
        properties: {
          uid: point.uid,
          name: point.name,
          active: point.active,
          selected: point.uid === selectedUid,
          updatedAt: point.updatedAt,
        },
      })),
  };
}

export function openStreetMapUrl(latitude: number, longitude: number): string {
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=16/${latitude}/${longitude}`;
}
