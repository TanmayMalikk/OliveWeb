// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMockSession, installDomMocks } from "../test-utils/domTestHarness";
import { emptyProfile } from "../domain/DomainModels";
import type { SessionValue } from "../session/SessionContext";

const mockGetCached = vi.fn();
const mockLoadPreview = vi.fn();
const mockLoadPreviewById = vi.fn();
let mockCurrentSession!: SessionValue;

vi.mock("../services/DocumentPreviewCache", () => ({
  getCachedDocumentPreview: (...args: unknown[]) => mockGetCached(...args),
  loadDocumentPreview: (...args: unknown[]) => mockLoadPreview(...args),
  loadDocumentPreviewById: (...args: unknown[]) => mockLoadPreviewById(...args),
}));

vi.mock("../session/SessionProvider", () => ({
  SessionProvider: ({ children }: { children: React.ReactNode }) => <div data-testid="session-provider">{children}</div>,
  useSession: () => mockCurrentSession,
}));

vi.mock("../public/PublicCoverPage", () => ({
  default: ({ onPrivateEntry }: { onPrivateEntry: () => void }) => (
    <button type="button" onClick={onPrivateEntry}>PublicCoverMock</button>
  ),
}));
vi.mock("../public/AppDownloadInstructions", () => ({
  default: () => <div>DownloadMock</div>,
}));
vi.mock("../users/shared/auth/AccountEntryPage", () => ({
  AuthPage: ({ organizationOnly }: { organizationOnly?: boolean }) => <div>{organizationOnly ? "OrgAuthMock" : "AuthMock"}</div>,
  Setup: () => <div>SetupMock</div>,
}));
vi.mock("../users/shared/auth/PrivateEntryLink", () => ({ default: () => <div>EntryLinkMock</div> }));
vi.mock("../users/shared/auth/BrowserPinGate", () => ({ default: () => <div>PinGateMock</div> }));
vi.mock("../users/shared/safe_app/SafeAppLayout", () => ({ default: () => <div>SafeAppMock</div> }));
vi.mock("../users/admin/AdminDashboardPage", () => ({ default: () => <div>AdminPortalMock</div> }));
vi.mock("../users/staff/StaffDashboardPage", () => ({ default: () => <div>StaffPortalMock</div> }));

import { BirthdayField } from "./BirthdayField";
import { Branch, Button, Empty, External, Field, Form, Forward, Logo, Modal, StepProgress } from "./SharedComponents";
import { BlurredDocumentPreview, NoteAttachmentPreviewGrid } from "./BlurredDocumentPreview";

describe("Shared components and main.tsx entrypoint", () => {
  beforeEach(() => {
    installDomMocks();
    mockCurrentSession = createMockSession();
    vi.clearAllMocks();
    mockGetCached.mockReturnValue(undefined);
    mockLoadPreview.mockResolvedValue({ kind: "text", text: "Preview snippet" });
    mockLoadPreviewById.mockResolvedValue({
      kind: "image",
      url: "blob:preview-1",
      document: { id: "doc-1", name: "photo.jpg", mimeType: "image/jpeg", approved: true, evidence: true },
    });
  });

  afterEach(() => {
    cleanup();
    document.body.innerHTML = "";
    delete document.documentElement.dataset.olivePublicAds;
    delete document.documentElement.dataset.olivePrivateRedirect;
  });

  it("renders BirthdayField multi-step picker, clear button, and validation errors", () => {
    const { rerender } = render(<BirthdayField defaultValue="1995-04-12" error="" />);
    fireEvent.click(screen.getByRole("button", { name: /1995/i }));
    fireEvent.click(screen.getByRole("button", { name: "1996\u20131987" }));
    fireEvent.click(screen.getByRole("button", { name: "1995" }));
    fireEvent.click(screen.getByRole("button", { name: /Back/i }));
    fireEvent.click(screen.getByRole("button", { name: "1995" }));
    fireEvent.click(screen.getByRole("button", { name: "April" }));
    fireEvent.click(screen.getByRole("button", { name: "15" }));
    expect(screen.getByRole("button", { name: /Clear date/i })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Clear date/i }));

    rerender(<BirthdayField required error="Enter a birthday" shakeTrigger={1} />);
    expect(screen.getByRole("alert").textContent).toBe("Enter a birthday");
  });

  it("renders FormElements, BrandElements, Modal variants, StepProgress, and Empty", () => {
    const onSubmit = vi.fn();
    const onClose = vi.fn();
    render(
      <div>
        <Branch />
        <Logo small />
        <Forward>Next</Forward>
        <External href="https://example.ca">Guide</External>
        <StepProgress step={1} steps={["One", "Two", "Three"]} />
        <Empty title="Nothing yet" action={<Button onClick={onClose}>Add</Button>}>Details</Empty>
        <Form onSubmit={onSubmit}>
          <Field label="Secret" type="password" hint="Use 8+ chars" />
          <Field label="Email" error="Invalid email" shakeTrigger={1} />
          <Button type="submit">Submit</Button>
        </Form>
        <Modal title="Native modal" onClose={onClose}><p>Body</p></Modal>
        <Modal title="Non modal" nonModal onClose={onClose}><p>Layer</p></Modal>
      </div>,
    );

    fireEvent.click(screen.getByLabelText("Show secret"));
    expect((screen.getByLabelText("Secret") as HTMLInputElement).type).toBe("text");
    fireEvent.click(screen.getByRole("button", { name: "Submit" }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getAllByLabelText("Close dialog")[0]);
    expect(onClose).toHaveBeenCalled();
  });

  it("renders BlurredDocumentPreview and NoteAttachmentPreviewGrid in compact and full modes", async () => {
    const id = { uid: "u1", username: "user_one", role: "USER" as const };
    const onOpen = vi.fn();

    render(
      <div>
        <BlurredDocumentPreview
          identity={id}
          showEvidenceBadge
          document={{
            id: "doc-txt",
            ownerUid: "u1",
            name: "notes.txt",
            mimeType: "text/plain",
            ciphertextSize: 10,
            ciphertextSha256: "a".repeat(64),
            createdAt: 1,
            evidence: true,
            approved: true,
            approvalMessage: "",
          }}
        />
        <NoteAttachmentPreviewGrid attachmentIds={["a1", "a2", "a3", "a4", "a5"]} identity={id} compact />
        <NoteAttachmentPreviewGrid attachmentIds={["doc-1"]} identity={id} onOpen={onOpen} />
      </div>,
    );

    await waitFor(() => expect(screen.getByText("+1")).toBeTruthy());
    await waitFor(() => expect(screen.getByText("photo.jpg")).toBeTruthy());
    fireEvent.click(screen.getByText("photo.jpg").closest("button")!);
    expect(onOpen).toHaveBeenCalledWith("doc-1", expect.objectContaining({ id: "doc-1" }));
  });

  it("mounts main.tsx routes including recovery unlock popup, portals, public cover, and ads fallback", async () => {
    const runMain = async () => {
      document.body.innerHTML = '<div id="root"></div>';
      vi.resetModules();
      await import("../main");
    };

    // Blocking recovery modal when encrypted access is locked.
    mockCurrentSession = createMockSession({
      phase: "safe",
      hasEncryptedAccess: false,
      passwordRecoveryEnrolled: true,
      recoverEncryptedAccess: vi.fn().mockRejectedValueOnce(new Error("Wrong account password")),
    });
    await runMain();
    await waitFor(() => expect(screen.getByText("Unlock encrypted access")).toBeTruthy());
    fireEvent.change(screen.getByLabelText("Account password"), { target: { value: "BadPass1!" } });
    fireEvent.submit(screen.getByRole("button", { name: "Unlock" }).closest("form")!);
    await waitFor(() => expect(screen.getByText("Wrong account password")).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Quick Exit" }));
    expect(mockCurrentSession.quickExit).toHaveBeenCalled();

    // Public cover route + private entry trigger.
    mockCurrentSession = createMockSession({ phase: "public", path: "/" });
    await runMain();
    await waitFor(() => expect(screen.getByText("PublicCoverMock")).toBeTruthy());
    fireEvent.click(screen.getByText("PublicCoverMock"));
    expect(mockCurrentSession.perform).toHaveBeenCalled();

    // Public ads document mode + private redirect mode.
    document.documentElement.dataset.olivePublicAds = "true";
    await runMain();
    await waitFor(() => expect(screen.getByText("PublicCoverMock")).toBeTruthy());
    delete document.documentElement.dataset.olivePublicAds;

    document.documentElement.dataset.olivePrivateRedirect = "true";
    await runMain();
    await waitFor(() => expect(screen.getByText("Opening\u2026")).toBeTruthy());
    delete document.documentElement.dataset.olivePrivateRedirect;

    // Account routes across phases.
    for (const [phase, path, text] of [
      ["setup", "/", "SetupMock"],
      ["link", "/", "EntryLinkMock"],
      ["pin", "/", "PinGateMock"],
      ["public", "/account/sign-in", "AuthMock"],
      ["public", "/get", "DownloadMock"],
    ] as const) {
      mockCurrentSession = createMockSession({
        phase,
        path,
        profile: emptyProfile("user_one", "USER"),
      });
      await runMain();
      await waitFor(() => expect(screen.getByText(text)).toBeTruthy());
    }
  });
});
