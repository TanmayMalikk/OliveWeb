// @vitest-environment happy-dom
import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyProfile } from "../../../domain/DomainModels";
import { createMockSession, installDomMocks, renderWithSession } from "../../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "uid-1", username: "user_one", role: "USER" as const } as {
    uid: string;
    username: string;
    role: "USER" | "SAFE_CONTACT" | "STAFF" | "ADMIN";
  },
  cloudMode: true,
  triggerHash: vi.fn(async (phrase: string) => `hash:${phrase}`),
  claimFriendInvite: vi.fn(async (_code: string) => ({ username: "friend_one" })),
}));

vi.mock("../../../services/AccountService", () => ({
  get cloudMode() {
    return mocks.cloudMode;
  },
  accounts: {
    get identity() {
      return mocks.identity;
    },
  },
  triggerHash: (...args: [string]) => mocks.triggerHash(...args),
}));

vi.mock("../../../services/FriendInviteService", () => ({
  claimFriendInvite: (...args: [string]) => mocks.claimFriendInvite(...args),
  createFriendInvite: vi.fn(),
  subscribeFriendInvite: vi.fn(),
}));

vi.mock("../../../session/SessionProvider", async () => {
  const { useSession } = await import("../../../session/SessionContext");
  return { useSession };
});

import { AuthPage, Setup } from "./AccountEntryPage";
import BrowserPinGate from "./BrowserPinGate";
import EntryLink from "./PrivateEntryLink";
import { PasswordRecoveryCard } from "../PasswordRecoveryCard";

describe("Auth, Setup, BrowserPinGate, PrivateEntryLink, and PasswordRecoveryCard", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.identity = { uid: "uid-1", username: "user_one", role: "USER" };
    mocks.cloudMode = true;
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn(async () => {}) },
    });
  });

  afterEach(() => {
    cleanup();
  });

  it("drives AuthPage across sign-in, create, validation errors, and organization modes", async () => {
    const authenticate = vi
      .fn()
      .mockRejectedValueOnce(new Error("Wrong username"))
      .mockRejectedValueOnce(new Error("Wrong password"))
      .mockResolvedValue(undefined);
    const go = vi.fn();
    const clearMessage = vi.fn();

    // Sign-in route + error branches + utility links + local preview banner.
    renderWithSession(<AuthPage />, {
      path: "/account/sign-in",
      cloudMode: false,
      authenticate,
      go,
      clearMessage,
      message: "Invalid login",
    });

    fireEvent.click(screen.getByRole("button", { name: /Back to Little Grove/i }));
    fireEvent.click(screen.getByRole("button", { name: /Replace a lost personal link/i }));
    fireEvent.click(screen.getByRole("button", { name: /Create an account/i }));
    expect(go).toHaveBeenCalledWith("/");
    expect(go).toHaveBeenCalledWith("/account/link");
    expect(go).toHaveBeenCalledWith("/account/create");
    expect(clearMessage).toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("Account type"), { target: { value: "SAFE_CONTACT" } });
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "user_one" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "Pass123!" } });
    fireEvent.submit(screen.getByRole("button", { name: /Sign in/i }).closest("form")!);
    await waitFor(() => expect(authenticate).toHaveBeenCalledTimes(1));

    fireEvent.submit(screen.getByRole("button", { name: /Sign in/i }).closest("form")!);
    await waitFor(() => expect(authenticate).toHaveBeenCalledTimes(2));

    // Create route: validation branches + valid submit.
    cleanup();
    renderWithSession(<AuthPage />, {
      path: "/account/create",
      authenticate,
      go,
      clearMessage,
    });
    fireEvent.click(screen.getAllByRole("radio")[1]);
    fireEvent.click(screen.getAllByRole("radio")[0]);

    const form = () => screen.getByRole("button", { name: /Create account/i }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "bad" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "Pass123!" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "Pass123!" } });
    await expect(form()).toBeTruthy();
    fireEvent.submit(form());

    fireEvent.change(screen.getByLabelText("Username"), { target: { value: "valid_user_1" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "weak" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "weak" } });
    fireEvent.submit(form());

    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "Valid123!" } });
    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "Other123!" } });
    fireEvent.submit(form());

    fireEvent.change(screen.getByLabelText("Confirm password"), { target: { value: "Valid123!" } });
    fireEvent.submit(form());
    await waitFor(() => expect(authenticate).toHaveBeenCalledWith("valid_user_1", "Valid123!", "USER", true));
    cleanup();

    // Organization mode.
    renderWithSession(<AuthPage organizationOnly />, { path: "/account/sign-in", authenticate });
    fireEvent.change(screen.getByLabelText("Account type"), { target: { value: "ADMIN" } });
    expect(screen.getByLabelText("Username or email")).toBeTruthy();
  });

  it("drives Setup across step 1 validations, affected people, email consent, and step 2 PIN", async () => {
    const completeSetup = vi.fn().mockRejectedValueOnce(new Error("PIN error")).mockResolvedValue(undefined);
    const signOut = vi.fn(async () => {});
    const baseProfile = {
      ...emptyProfile("user_one", "USER"),
      affectedNames: "Pat (10)\nSam",
      email: "bad-email",
      address: { ...emptyProfile("user_one", "USER").address, city: "" },
    };

    renderWithSession(<Setup />, { onboardingProfile: baseProfile, completeSetup, signOut });

    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    expect(signOut).toHaveBeenCalled();

    // Add/edit/remove affected person fields.
    fireEvent.click(screen.getByRole("button", { name: "Add another person" }));
    fireEvent.change(screen.getByLabelText("Person 3 name"), { target: { value: "Lee" } });
    fireEvent.change(screen.getByLabelText("Person 3 age (optional)"), { target: { value: "7" } });
    fireEvent.click(screen.getByRole("button", { name: "Remove person 2" }));

    // Step 1 email + consent + required fields validations.
    const step1Form = () => screen.getByRole("button", { name: /Continue/i }).closest("form")!;
    fireEvent.submit(step1Form()); // invalid email

    fireEvent.change(screen.getByLabelText("Email (optional)"), { target: { value: "user@example.com" } });
    fireEvent.submit(step1Form()); // missing consent checkbox

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.submit(step1Form()); // missing name/dob/city

    fireEvent.change(screen.getByLabelText("Full name"), { target: { value: "Alex Rivera" } });
    fireEvent.change(screen.getByLabelText("City"), { target: { value: "Edmonton" } });
    fireEvent.change(screen.getByLabelText(/Person causing harm/i), { target: { value: "None" } });
    fireEvent.change(screen.getByLabelText(/Anything you’d like to note/i), { target: { value: "Notes" } });

    // Pick date via BirthdayField dialog.
    fireEvent.click(screen.getByRole("button", { name: /Choose date of birth/i }));
    fireEvent.click(screen.getByRole("button", { name: "2006\u20131997" }));
    fireEvent.click(screen.getByRole("button", { name: "2000" }));
    fireEvent.click(screen.getByRole("button", { name: "January" }));
    fireEvent.click(screen.getByRole("button", { name: "10" }));

    fireEvent.submit(step1Form());
    await waitFor(() => expect(screen.getByText("Set up private entry")).toBeTruthy());

    // Step 2: Back button works, invalid PIN/trigger paths, then valid submit.
    fireEvent.click(screen.getByRole("button", { name: /Back/i }));
    expect(screen.getByText("Set up your profile")).toBeTruthy();
    fireEvent.submit(screen.getByRole("button", { name: /Continue/i }).closest("form")!);
    await waitFor(() => expect(screen.getByLabelText("Browser PIN")).toBeTruthy());

    const step2Form = () => screen.getByRole("button", { name: /Open my everyday app/i }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Trigger word or phrase"), { target: { value: "ab" } });
    fireEvent.change(screen.getByLabelText("Browser PIN"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("Confirm PIN"), { target: { value: "9999" } });
    fireEvent.submit(step2Form());

    fireEvent.change(screen.getByLabelText("Confirm PIN"), { target: { value: "1234" } });
    fireEvent.submit(step2Form()); // trigger too short

    fireEvent.change(screen.getByLabelText("Trigger word or phrase"), { target: { value: "mysecret" } });
    fireEvent.submit(step2Form()); // first call rejects
    await waitFor(() => expect(completeSetup).toHaveBeenCalledTimes(1));

    fireEvent.change(screen.getByLabelText("Browser PIN"), { target: { value: "1234" } });
    fireEvent.change(screen.getByLabelText("Confirm PIN"), { target: { value: "1234" } });
    fireEvent.submit(step2Form());
    await waitFor(() => expect(completeSetup).toHaveBeenCalledTimes(2));

    // Safe contact skips step 2.
    cleanup();
    mocks.identity = { uid: "uid-2", username: "safe_one", role: "SAFE_CONTACT" };
    renderWithSession(<Setup />, {
      onboardingProfile: { ...emptyProfile("safe_one", "SAFE_CONTACT"), fullName: "Safe One", dob: "1995-05-05", address: { ...emptyProfile("safe_one", "SAFE_CONTACT").address, city: "Calgary" } },
      completeSetup,
    });
    fireEvent.submit(screen.getByRole("button", { name: /Finish setup/i }).closest("form")!);
    await waitFor(() => expect(completeSetup).toHaveBeenCalledTimes(3));
  });

  it("drives BrowserPinGate, EntryLink, and PasswordRecoveryCard", async () => {
    const unlock = vi.fn().mockRejectedValueOnce(new Error("Bad PIN")).mockResolvedValue(undefined);
    const quickExit = vi.fn();
    const go = vi.fn();
    const clearMessage = vi.fn();

    renderWithSession(<BrowserPinGate />, {
      message: "Try again",
      unlock,
      quickExit,
      go,
      clearMessage,
    });

    fireEvent.click(screen.getByRole("button", { name: /Quick Exit/i }));
    expect(quickExit).toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "1" }));
    fireEvent.click(screen.getByRole("button", { name: "2" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete digit" }));
    fireEvent.change(screen.getByLabelText("Browser PIN"), { target: { value: "123456" } });
    fireEvent.submit(screen.getByRole("button", { name: /Unlock/i }).closest("form")!);
    await waitFor(() => expect(unlock).toHaveBeenCalledWith("123456"));

    fireEvent.change(screen.getByLabelText("Browser PIN"), { target: { value: "654321" } });
    fireEvent.submit(screen.getByRole("button", { name: /Unlock/i }).closest("form")!);
    await waitFor(() => expect(unlock).toHaveBeenCalledTimes(2));

    fireEvent.click(screen.getByRole("button", { name: /Get a new PIN or link/i }));
    expect(go).toHaveBeenCalledWith("/account/link");

    // PrivateEntryLink copy success and failure.
    cleanup();
    renderWithSession(<EntryLink />, { personalLink: "https://yourolivebranch.ca/#tok12345678", quickExit });
    const linkInput = screen.getByLabelText("Your personal entry link") as HTMLInputElement;
    fireEvent.focus(linkInput);
    fireEvent.click(screen.getByRole("button", { name: /Copy link/i }));
    await waitFor(() => expect(screen.getByText("Copied")).toBeTruthy());

    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: vi.fn(async () => { throw new Error("Denied"); }) },
    });
    fireEvent.click(screen.getByRole("button", { name: /Copied|Copy link/i }));
    await waitFor(() => expect(screen.getByText(/Select and copy the link above/i)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: /Continue to my cover/i }));
    expect(quickExit).toHaveBeenCalled();

    // PasswordRecoveryCard states.
    cleanup();
    const onNotice = vi.fn();
    const recoverEncryptedAccess = vi.fn(async () => {});
    const enablePasswordRecovery = vi.fn(async () => {});

    const c0 = renderWithSession(<PasswordRecoveryCard onNotice={onNotice} />, { cloudMode: false });
    expect(c0.container.textContent).toBe("");
    c0.unmount();

    const c1 = renderWithSession(<PasswordRecoveryCard onNotice={onNotice} />, { passwordRecoveryEnrolled: null });
    expect(screen.getByText(/Checking recovery status/i)).toBeTruthy();
    c1.unmount();

    const c2 = renderWithSession(<PasswordRecoveryCard onNotice={onNotice} />, {
      passwordRecoveryEnrolled: true,
      hasEncryptedAccess: false,
      recoverEncryptedAccess,
    });
    fireEvent.change(screen.getByLabelText("Account password"), { target: { value: "Pass123!" } });
    fireEvent.submit(screen.getByRole("button", { name: /Unlock encrypted access/i }).closest("form")!);
    await waitFor(() => expect(recoverEncryptedAccess).toHaveBeenCalledWith("Pass123!"));
    expect(onNotice).toHaveBeenCalled();
    c2.unmount();

    const c3 = renderWithSession(<PasswordRecoveryCard onNotice={onNotice} />, {
      passwordRecoveryEnrolled: false,
      passwordRecoveryKeysetExists: true,
    });
    expect(screen.getByText(/already exists on another device/i)).toBeTruthy();
    c3.unmount();

    renderWithSession(<PasswordRecoveryCard onNotice={onNotice} />, {
      passwordRecoveryEnrolled: false,
      passwordRecoveryKeysetExists: false,
      enablePasswordRecovery,
    });
    fireEvent.change(screen.getByLabelText("Account password"), { target: { value: "Pass123!" } });
    fireEvent.submit(screen.getByRole("button", { name: /Enable password recovery/i }).closest("form")!);
    await waitFor(() => expect(enablePasswordRecovery).toHaveBeenCalledWith("Pass123!"));
  });
});
