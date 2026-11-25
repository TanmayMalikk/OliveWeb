import { describe, expect, it } from "vitest";
import {
  filterSupportTickets,
  roleConversationsForInbox,
  visibleCategoriesForRole,
} from "./OrganizationSupportModel";
import { filterAndSortLocations, toLocationFeatureCollection } from "./OrganizationLiveMapModel";
import type { AdminLiveLocation } from "../../services/EmergencyResponseService";

describe("OrganizationSupportModel", () => {
  const sampleTickets = [
    { id: "c1", supportOrigin: "PLAN_CONSULT" as const, supportTag: "PLAN_CONSULT" as const, closedAt: 0 },
    { id: "c2", supportOrigin: "SUPPORT" as const, supportTag: "SUPPORT" as const, closedAt: 0 },
    { id: "c3", supportOrigin: "SUPPORT" as const, supportTag: "NEEDS_ADMIN" as const, closedAt: 0 },
    { id: "c4", supportOrigin: "SUPPORT" as const, supportTag: "NEEDS_ADMIN" as const, closedAt: 100 },
    { id: "c5", supportOrigin: "SUPPORT" as const, supportTag: "SUPPORT" as const, closedAt: 200 },
  ];

  it("shows only escalation category and tickets to admins", () => {
    expect(visibleCategoriesForRole("ADMIN")).toEqual([
      { id: "NEEDS_ADMIN", label: "Need Admins" },
    ]);
    expect(roleConversationsForInbox(sampleTickets, "ADMIN").map((t) => t.id)).toEqual(["c3", "c4"]);
    expect(filterSupportTickets(sampleTickets, "ADMIN", "SUPPORT", false).map((t) => t.id)).toEqual(["c3"]);
    expect(filterSupportTickets(sampleTickets, "ADMIN", "SUPPORT", true).map((t) => t.id)).toEqual(["c4"]);
  });

  it("shows all three categories to staff", () => {
    expect(visibleCategoriesForRole("STAFF").map((item) => item.id)).toEqual([
      "PLAN_CONSULT",
      "SUPPORT",
      "NEEDS_ADMIN",
    ]);
    expect(filterSupportTickets(sampleTickets, "STAFF", "PLAN_CONSULT", false).map((t) => t.id)).toEqual(["c1"]);
    expect(filterSupportTickets(sampleTickets, "STAFF", "SUPPORT", false).map((t) => t.id)).toEqual(["c2"]);
    expect(filterSupportTickets(sampleTickets, "STAFF", "SUPPORT", true).map((t) => t.id)).toEqual(["c4", "c5"]);
  });
});

describe("OrganizationLiveMapModel", () => {
  const sampleLocations: Record<string, AdminLiveLocation> = {
    u1: { uid: "u1", name: "Alex", latitude: 53.54, longitude: -113.49, accuracy: 12, updatedAt: 1000, active: false },
    u2: { uid: "u2", name: "Jordan", latitude: 53.55, longitude: -113.5, accuracy: 8, updatedAt: 900, active: true },
    u3: { uid: "u3", name: "Taylor", latitude: 53.56, longitude: -113.51, accuracy: 10, updatedAt: 2000, active: true },
  };

  it("sorts live locations before last-known locations and orders newest first", () => {
    expect(filterAndSortLocations(sampleLocations, "", "all").map((p) => p.uid)).toEqual([
      "u3",
      "u2",
      "u1",
    ]);
  });

  it("filters by search text and live status", () => {
    expect(filterAndSortLocations(sampleLocations, "jor", "live").map((p) => p.uid)).toEqual(["u2"]);
    expect(filterAndSortLocations(sampleLocations, "", "last-known").map((p) => p.uid)).toEqual(["u1"]);
  });

  it("builds GeoJSON Point features with [longitude, latitude] and selected flags", () => {
    const invalid: AdminLiveLocation = {
      uid: "u4",
      name: "Broken",
      latitude: Number.NaN,
      longitude: -113.5,
      accuracy: null,
      updatedAt: 500,
      active: false,
    };
    const collection = toLocationFeatureCollection(
      [sampleLocations.u2, sampleLocations.u1, invalid],
      "u2",
    );
    expect(collection.type).toBe("FeatureCollection");
    expect(collection.features).toHaveLength(2);
    expect(collection.features[0]).toEqual({
      type: "Feature",
      geometry: { type: "Point", coordinates: [-113.5, 53.55] },
      properties: { uid: "u2", name: "Jordan", active: true, selected: true, updatedAt: 900 },
    });
    expect(collection.features[1].properties.selected).toBe(false);
  });
});
