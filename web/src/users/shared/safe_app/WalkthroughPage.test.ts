import { describe, expect, it, vi } from "vitest";
vi.mock("../../../session/SessionProvider", () => ({
  useSession: () => ({}),
}));
import {
  clampWalkthroughStep,
  nextWalkthroughTopicIndex,
  walkthroughTopicsForRole,
} from "./WalkthroughPage";

describe("WalkthroughPage table of contents and navigation helpers", () => {
  it("lists all user features in the table of contents and filters user-only topics for safe contacts", () => {
    const userTopics = walkthroughTopicsForRole("USER");
    const contactTopics = walkthroughTopicsForRole("SAFE_CONTACT");

    expect(userTopics).toHaveLength(10);
    expect(userTopics.every((topic) => topic.steps.length === 3)).toBe(true);
    expect(userTopics.map((topic) => topic.id)).toContain("cover_entry");
    expect(userTopics.map((topic) => topic.id)).toContain("quick_exit");
    expect(userTopics.map((topic) => topic.id)).toContain("staff_help");

    expect(contactTopics).toHaveLength(6);
    expect(contactTopics.some((topic) => topic.userOnly)).toBe(false);
    expect(contactTopics.map((topic) => topic.id)).toEqual([
      "safety_plan",
      "notes_incidents",
      "documents",
      "messaging_voice",
      "location",
      "activity_settings",
    ]);
  });

  it("clamps step and topic navigation indices within bounds", () => {
    expect(clampWalkthroughStep(-2, 3)).toBe(0);
    expect(clampWalkthroughStep(1, 3)).toBe(1);
    expect(clampWalkthroughStep(7, 3)).toBe(2);
    expect(clampWalkthroughStep(2, 0)).toBe(0);

    expect(nextWalkthroughTopicIndex(0, 10, -1)).toBe(0);
    expect(nextWalkthroughTopicIndex(0, 10, 1)).toBe(1);
    expect(nextWalkthroughTopicIndex(9, 10, 1)).toBe(9);
  });
});
