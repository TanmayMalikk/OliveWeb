// @vitest-environment happy-dom
import { act, cleanup, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { emptyHistory, emptyProfile, type Entry } from "../../../domain/DomainModels";
import type { BrowserDocument } from "../../../services/MediaService";
import { installDomMocks, renderWithSession } from "../../../test-utils/domTestHarness";

const mocks = vi.hoisted(() => ({
  identity: { uid: "uid-1", username: "user_one", role: "USER" as const },
  listBrowserDocuments: vi.fn(async (..._args: unknown[]): Promise<BrowserDocument[]> => []),
  openBrowserDocument: vi.fn(async (..._args: unknown[]) => "blob:note-doc"),
  setBrowserDocumentEvidence: vi.fn(async (..._args: unknown[]) => {}),
  uploadBrowserDocument: vi.fn(async (..._args: unknown[]) => "doc-new"),
}));

vi.mock("../../../session/SessionProvider", async () => {
  const { useSession } = await import("../../../session/SessionContext");
  return { useSession };
});

vi.mock("../../../services/AccountService", () => ({
  accounts: {
    get identity() {
      return mocks.identity;
    },
  },
}));

vi.mock("../../../services/MediaService", () => ({
  listBrowserDocuments: (...args: unknown[]) => mocks.listBrowserDocuments(...args),
  openBrowserDocument: (...args: unknown[]) => mocks.openBrowserDocument(...args),
  setBrowserDocumentEvidence: (...args: unknown[]) => mocks.setBrowserDocumentEvidence(...args),
  uploadBrowserDocument: (...args: unknown[]) => mocks.uploadBrowserDocument(...args),
}));

vi.mock("../../shared/FadedMap", () => ({
  FadedMap: ({ onSelect }: { onSelect?: (pt: { latitude: number; longitude: number }) => void }) => (
    <button type="button" data-testid="faded-map-drop" onClick={() => onSelect?.({ latitude: 53.54, longitude: -113.49 })}>
      Drop pin
    </button>
  ),
}));

vi.mock("../../../components/BlurredDocumentPreview", () => ({
  BlurredDocumentPreview: ({ document }: { document: BrowserDocument }) => <span>{document.name}</span>,
  NoteAttachmentPreviewGrid: ({ attachmentIds, onOpen }: { attachmentIds?: string[]; onOpen?: (id: string) => void }) => (
    <div>
      {attachmentIds?.map((id) =>
        onOpen ? (
          <button key={id} type="button" onClick={() => onOpen(id)}>
            Preview:{id}
          </button>
        ) : (
          <span key={id}>Preview:{id}</span>
        ),
      )}
    </div>
  ),
}));

import Contacts from "./UserContactsPage";
import Notes from "./UserNotesPage";
import { IncidentLocationPicker } from "./IncidentLocationPicker";
import { WalkthroughActionContext } from "../../shared/safe_app/WalkthroughCoachmark";

const doc1: BrowserDocument = {
  id: "doc-1",
  name: "evidence.jpg",
  mimeType: "image/jpeg",
  ownerUid: "uid-1",
  createdAt: 1000,
  approved: true,
  approvalMessage: "",
  ciphertextSize: 120,
  ciphertextSha256: "a".repeat(64),
  evidence: false,
};

const noteEntry: Entry = {
  id: "n1",
  timestamp: 100,
  updatedAt: 200,
  kind: "Note",
  title: "Morning check",
  body: "All okay",
  pinned: false,
  attachmentIds: ["doc-1"],
  edits: [{ id: "ed1", timestamp: 150, changes: [{ field: "Title", before: "", after: "Morning check" }] }],
};

const trashedEntry: Entry = {
  id: "n2",
  timestamp: 100,
  updatedAt: 200,
  deletedAt: Date.now() - 1000,
  kind: "Incident",
  title: "Old incident",
  body: "Removed detail",
  pinned: false,
  edits: [],
};

describe("UserNotesPage, UserContactsPage, and IncidentLocationPicker", () => {
  beforeEach(() => {
    installDomMocks();
    vi.clearAllMocks();
    mocks.listBrowserDocuments.mockResolvedValue([doc1]);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("drives UserContactsPage add, validation, edit, remove, and walkthrough trigger", async () => {
    const saveProfile = vi.fn(async () => {});
    const profile = {
      ...emptyProfile("user_one", "USER"),
      contacts: [{ id: "c1", name: "Jordan", details: "555-0100", relationship: "Sister" }],
    };

    renderWithSession(<Contacts />, { profile, saveProfile });
    expect(screen.getByText("Jordan")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    await waitFor(() => expect(saveProfile).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Jordan Updated" } });
    fireEvent.submit(screen.getByRole("button", { name: "Save contact" }).closest("form")!);
    await waitFor(() => expect(saveProfile).toHaveBeenCalledTimes(2));

    // Empty state + add validation + walkthrough modal.
    cleanup();
    renderWithSession(
      <WalkthroughActionContext.Provider value={{ stepId: "c-add", action: "contacts_open_add_modal" }}>
        <Contacts />
      </WalkthroughActionContext.Provider>,
      { profile: emptyProfile("user_one", "USER"), saveProfile },
    );
    expect(screen.getByText(/You don’t have to hold every detail/i)).toBeTruthy();
    const form = screen.getByRole("button", { name: "Save contact" }).closest("form")!;
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "  " } });
    fireEvent.change(screen.getByLabelText(/Phone, email/i), { target: { value: "555-0200" } });
    fireEvent.submit(form); // validation shake
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Sam" } });
    fireEvent.submit(form);
    await waitFor(() => expect(saveProfile).toHaveBeenCalledTimes(3));
  });

  it("drives IncidentLocationPicker address search, cache, map drop-pin, and geolocation", async () => {
    vi.useFakeTimers();
    const onChange = vi.fn();
    const origFetch = globalThis.fetch;
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes("q=fail")) return { ok: false, json: async () => ({ error: "Lookup error" }) };
      if (url.includes("latitude=")) {
        return { ok: true, json: async () => ({ locations: [{ label: "100 Jasper Ave, Edmonton", latitude: 53.54, longitude: -113.49 }] }) };
      }
      return { ok: true, json: async () => ({ locations: [{ label: "Edmonton City Hall", latitude: 53.546, longitude: -113.49 }] }) };
    });
    globalThis.fetch = fetchMock as typeof fetch;
    Object.defineProperty(navigator, "geolocation", {
      configurable: true,
      value: {
        getCurrentPosition: (ok: (pos: { coords: { latitude: number; longitude: number } }) => void) =>
          ok({ coords: { latitude: 53.5, longitude: -113.5 } }),
      },
    });

    try {
      renderWithSession(<IncidentLocationPicker label="" onChange={onChange} />);
      const input = screen.getByLabelText("Location of incident");
      fireEvent.change(input, { target: { value: "City Hall" } });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(80);
      });
      expect(screen.getByRole("button", { name: "Edmonton City Hall" })).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "Edmonton City Hall" }));
      expect(onChange).toHaveBeenCalledWith({ label: "Edmonton City Hall", latitude: 53.546, longitude: -113.49 });

      // Error branch.
      fireEvent.change(input, { target: { value: "fail query" } });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(80);
      });
      expect(screen.getByText(/Lookup error/i)).toBeTruthy();

      // Cached query hit + open map & drop pin + geolocation.
      fireEvent.change(input, { target: { value: "City Hall" } });
      expect(screen.getByRole("button", { name: "Edmonton City Hall" })).toBeTruthy();

      fireEvent.click(screen.getByRole("button", { name: "Choose incident location on map" }));
      fireEvent.click(screen.getByRole("button", { name: "Show my current location" }));
      await act(async () => {
        fireEvent.click(screen.getByTestId("faded-map-drop"));
        await Promise.resolve();
      });
      expect(onChange).toHaveBeenCalled();
    } finally {
      globalThis.fetch = origFetch;
    }
  });

  it("drives UserNotesPage create, incident attachments, pin, revisions, trash, and restore", async () => {
    const saveEntry = vi.fn(async () => {});
    const record = vi.fn(async () => {});
    const profile = emptyProfile("user_one", "USER");

    renderWithSession(<Notes />, {
      profile,
      history: { ...emptyHistory(), private_entries: [noteEntry, trashedEntry] },
      saveEntry,
      record,
    });

    await waitFor(() => expect(screen.getByText("Morning check")).toBeTruthy());

    // Pin, inspect edit history, search filter + clear filter.
    fireEvent.click(screen.getByRole("button", { name: "Pin Morning check" }));
    expect(record).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Edit history" }));
    expect(screen.getByText("Entry edit history")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Close dialog" }));

    fireEvent.change(screen.getByPlaceholderText(/Find a thought/i), { target: { value: "no-match" } });
    expect(screen.getByText(/No entries match your filter/i)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));

    // Open Trash -> restore entry -> return to notes.
    fireEvent.click(screen.getByRole("button", { name: "Trash" }));
    expect(screen.getByText("Old incident")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Restore" }));
    await waitFor(() => expect(saveEntry).toHaveBeenCalled());
    fireEvent.click(screen.getByRole("button", { name: "Back to notes" }));

    // Edit existing note -> switch to Incident, attach saved doc, preview doc, upload new attachment, save (marks evidence).
    fireEvent.click(document.querySelector(".note-open")!);
    fireEvent.click(screen.getByRole("tab", { name: "Incident" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview:doc-1" }));
    await waitFor(() => expect(screen.getByAltText("evidence.jpg")).toBeTruthy());
    const viewerDialog = screen.getByAltText("evidence.jpg").closest("dialog")!;
    fireEvent.click(within(viewerDialog).getByRole("button", { name: "Close dialog", hidden: true }));

    const upload = document.querySelector('input[type="file"]') as HTMLInputElement;
    mocks.listBrowserDocuments.mockResolvedValueOnce([doc1, { ...doc1, id: "doc-new", name: "new.png" }]);
    Object.defineProperty(upload, "files", {
      configurable: true,
      value: [new File(["img"], "new.png", { type: "image/png" })],
    });
    fireEvent.change(upload);
    await waitFor(() => expect(mocks.uploadBrowserDocument).toHaveBeenCalled());

    fireEvent.submit(screen.getByRole("button", { name: "Save entry" }).closest("form")!);
    await waitFor(() => expect(saveEntry).toHaveBeenCalledTimes(2));
    expect(mocks.setBrowserDocumentEvidence).toHaveBeenCalledWith("doc-1", true);

    // Move note to trash + walkthrough triggers.
    fireEvent.click(screen.getByRole("button", { name: "Move Morning check to trash" }));
    expect(record).toHaveBeenCalled();

    cleanup();
    renderWithSession(
      <WalkthroughActionContext.Provider value={{ stepId: "n-inc", action: "notes_open_incident_editor" }}>
        <Notes />
      </WalkthroughActionContext.Provider>,
      { profile, history: emptyHistory(), saveEntry, record },
    );
    expect(screen.getByText("A new thought")).toBeTruthy();
  });
});
