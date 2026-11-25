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

export function openStreetMapUrl(latitude: number, longitude: number): string {
  return `https://www.openstreetmap.org/?mlat=${latitude}&mlon=${longitude}#map=16/${latitude}/${longitude}`;
}
