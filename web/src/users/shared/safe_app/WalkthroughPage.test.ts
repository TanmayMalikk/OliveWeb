import { describe, expect, it } from "vitest";
import {
  clampWalkthroughStep,
  computeWebCoachmarkPlacement,
  firstWalkthroughStepForTopic,
  nextWalkthroughTopicIndex,
  nextWalkthroughTourStepId,
  walkthroughStepCountForTopic,
  walkthroughTopicGroups,
  walkthroughTopicsForRole,
  walkthroughTourStepsForRole,
} from "./WalkthroughCatalog";

describe("WalkthroughCatalog table of contents, highlight targeting, and placement", () => {
  it("lists all user features in the table of contents and filters user-only topics for safe contacts", () => {
    const userTopics = walkthroughTopicsForRole("USER");
    const contactTopics = walkthroughTopicsForRole("SAFE_CONTACT");

    expect(userTopics).toHaveLength(10);
    expect(userTopics.every((topic) => topic.steps.length === 3)).toBe(true);
    expect(userTopics.map((topic) => topic.id)).toContain("cover_entry");
    expect(userTopics.map((topic) => topic.id)).toContain("quick_exit");
    expect(userTopics.map((topic) => topic.id)).toContain("staff_help");

    expect(contactTopics).toHaveLength(7);
    expect(contactTopics.some((topic) => topic.userOnly)).toBe(false);
    expect(contactTopics.map((topic) => topic.id)).toEqual([
      "safety_plan",
      "notes_incidents",
      "documents",
      "messaging_voice",
      "location",
      "activity_settings",
      "cover_entry",
    ]);
  });

  it("includes multi-step target coverage for quick exit, messaging, settings, and hotlines/contacts", () => {
    const userSteps = walkthroughTourStepsForRole("USER");
    const contactSteps = walkthroughTourStepsForRole("SAFE_CONTACT");

    expect(userSteps.length).toBeGreaterThanOrEqual(22);
    expect(contactSteps.every((step) => !step.userOnly)).toBe(true);
    expect(walkthroughStepCountForTopic("USER", "messaging_voice")).toBe(4);
    expect(walkthroughStepCountForTopic("USER", "cover_entry")).toBe(3);
    expect(firstWalkthroughStepForTopic("USER", "messaging_voice")?.id).toBe("chat_add_friends");

    const quickExit = userSteps.find((step) => step.id === "quick_exit_fab");
    expect(quickExit?.tourPage).toBe("Home");
    expect(quickExit?.targetSelector).toBe(".floating-quick-exit");

    expect(nextWalkthroughTourStepId(userSteps, "quick_exit_fab", 1)).toBe("home_sidebar_nav");
    expect(nextWalkthroughTourStepId(userSteps, "quick_exit_fab", -1)).toBeNull();
    expect(nextWalkthroughTourStepId(userSteps, "settings_passkey", 1)).toBeNull();
  });

  it("positions popup strictly below upper targets and strictly above lower targets without overlap", () => {
    const belowUpper = computeWebCoachmarkPlacement({
      targetRect: { left: 280, top: 80, right: 620, bottom: 140, width: 340, height: 60 },
      viewportWidth: 1280,
      viewportHeight: 800,
      cardWidth: 368,
      cardHeight: 200,
    });
    expect(belowUpper.top).toBeGreaterThanOrEqual(140);

    const aboveQuickExit = computeWebCoachmarkPlacement({
      targetRect: { left: 1080, top: 720, right: 1250, bottom: 770, width: 170, height: 50 },
      viewportWidth: 1280,
      viewportHeight: 800,
      cardWidth: 368,
      cardHeight: 200,
      safeBottom: 784,
    });
    expect(aboveQuickExit.top + 200).toBeLessThanOrEqual(720);
  });

  it("groups topics by category in display order and omits empty categories", () => {
    const userGroups = walkthroughTopicGroups(walkthroughTopicsForRole("USER"));
    const contactGroups = walkthroughTopicGroups(walkthroughTopicsForRole("SAFE_CONTACT"));

    expect(userGroups.map((group) => group.category.id)).toEqual([
      "privacy",
      "tools",
      "support",
      "account",
    ]);
    expect(contactGroups.map((group) => group.category.id)).toEqual(["privacy", "tools", "account"]);
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
