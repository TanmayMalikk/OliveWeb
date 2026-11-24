import { expect, test, type Page } from "./coverageFixture";

async function openPlan(page: Page, role = "owner", panel = "safety") {
  await page.route("**/__test_safety_plan?*", async route => {
    const response = await route.fetch({ url: new URL("/", route.request().url()).href });
    const html = await response.text();
    await route.fulfill({ response, body: html.replace("/src/main.tsx", "/tests/safetyPlanFixture.tsx") });
  });
  await page.route(/\/src\/services\/(AccountService|SafetyPlanService|MessagingService|MediaService|OrganizationSupportService)\.ts(?:\?|$)/, route =>
    route.fulfill({ status: 307, headers: { location: "/tests/safetyPlanBindings.ts" } }));
  await page.route(/\/src\/session\/SessionProvider\.tsx(?:\?|$)/, route =>
    route.fulfill({ status: 307, headers: { location: "/tests/safetyPlanBindings.ts" } }));
  await page.route(/\/src\/users\/admin\/AdminAccountApi\.ts(?:\?|$)/, route =>
    route.fulfill({ status: 307, headers: { location: "/tests/adminAccountsBackend.ts" } }));
  await page.route(/\/src\/services\/FriendInviteService\.ts(?:\?|$)/, route =>
    route.fulfill({ status: 307, headers: { location: "/tests/socialBackend.ts" } }));
  await page.goto("/__test_safety_plan?" + new URLSearchParams({ role, panel }));
}

test("plan owner edits checklist steps, previews documents and opens the consultation ticket", async ({ page }) => {
  await openPlan(page);
  const documents = page.getByRole("group", { name: "Important documents" });
  const row = (index: number) => documents.getByRole("textbox", { name: "Important documents step " + index });
  const statusBanner = page.getByRole("status").filter({
    has: page.getByRole("button", { name: "Dismiss" }),
  });
  await expect(row(1)).toHaveValue("Prepare identification");
  await page.getByRole("tab", { name: "Primary plan" }).press("ArrowRight");
  await expect(row(1)).toHaveValue("Backup identification");
  await page.getByRole("tab", { name: "Backup plan" }).press("Home");
  await page.getByRole("navigation", { name: "Jump to a checklist" }).getByRole("button", { name: "Documents", exact: true }).click();
  await documents.getByRole("checkbox", { name: "Prepare identification, unchecked" }).check();
  await expect(documents.getByText("2 of 2 prepared")).toBeVisible();

  await documents.getByRole("button", { name: "Add step" }).click();
  await row(3).fill("Pack copies");
  await row(3).blur();
  await expect(statusBanner).toContainText("Important documents saved.");
  await documents.getByRole("button", { name: "Remove Important documents step 3" }).click();
  await expect(row(3)).toHaveCount(0);

  await documents.getByLabel("Attach a saved document to step 1").selectOption("doc");
  await documents.getByRole("button", { name: "Identification.txt", exact: true }).click();
  const preview = page.getByRole("dialog", { name: "Identification.txt", exact: true });
  await expect(preview.getByText("Private sample document")).toBeVisible();
  await preview.getByRole("button", { name: "Close" }).click();
  await documents.getByRole("button", { name: "Remove attachment Identification.txt" }).click();
  await expect(documents.getByRole("button", { name: "Identification.txt", exact: true })).toHaveCount(0);
  await documents.getByLabel("Upload a document for step 1").setInputFiles({
    name: "Copies.txt", mimeType: "text/plain", buffer: Buffer.from("test copies"),
  });
  await expect(documents.getByRole("button", { name: "Copies.txt", exact: true })).toBeVisible();

  await page.getByRole("checkbox", { name: "Trusted", exact: true }).check();
  await page.getByRole("button", { name: "Save sharing" }).click();
  await expect(statusBanner).toContainText("Safe contact access updated.");
  await page.getByRole("button", { name: "Request consultation" }).click();
  await page.getByRole("button", { name: "Open consultation ticket" }).click();
  const navigation = await page.evaluate(() => (window as Window & {panelEvents?: unknown[][]}).panelEvents);
  expect(navigation).toEqual(Array.from({ length: 2 }).flatMap(() => [
    ["ticket", { id: "consult-room", uid: "owner" }], ["page", "Messages"],
  ]));
  await page.getByRole("button", { name: "End consultation request" }).click();
  await expect(page.getByRole("button", { name: "Request consultation" })).toBeVisible();
});

test("checklist conflict shows the changed field and preserves the selected version", async ({ page }) => {
  await openPlan(page);
  const input = page.getByRole("textbox", { name: "Important documents step 1" });
  await expect(input).toHaveValue("Prepare identification");
  const send = (name: string) => page.evaluate(command => {
    const fixture = (window as Window & {planMock?: Record<string, () => void>}).planMock;
    fixture?.[command]();
  }, name);
  await send("pushRemoteChecklist");
  await expect(input).toHaveValue("New staff identification");
  await send("conflictNextEdit");
  await input.fill("My new identification");
  await input.blur();
  const conflict = page.getByRole("dialog", { name: "Conflicts" });
  await expect(conflict).toBeVisible();
  await expect(conflict).toContainText("My new identification");
  await expect(conflict).toContainText("Incoming identification");
  await expect(conflict).not.toContainText("Unaffected step");
  await conflict.getByRole("radio", { name: /Your change/ }).check();
  await conflict.getByRole("button", { name: "Keep my change" }).click();
  await expect(conflict).toHaveCount(0);
  await expect(input).toHaveValue("My new identification");
});

test("safe contacts can switch shared plans and lose access when it is revoked", async ({ page }) => {
  await openPlan(page, "contact");
  await expect(page.getByRole("heading", { name: "Owner’s safety plan" })).toBeVisible();
  const checkbox = page.getByRole("checkbox", { name: "Prepare identification, unchecked" });
  await expect(checkbox).toBeDisabled();
  await expect(page.getByRole("button", { name: "Add step" })).toHaveCount(0);
  await page.getByLabel("Choose a shared plan").selectOption("other");
  await expect(page.getByRole("heading", { name: "Other owner’s safety plan" })).toBeVisible();
  await expect(page.getByText("Other identification")).toBeVisible();
  await page.evaluate(() => (window as Window & {planMock?: { revokeAccess(owner: string): void }}).planMock?.revokeAccess("other"));
  await expect(page.getByText("No safety plan is currently shared with you.")).toBeVisible();
  await expect(page.getByText("Other identification")).toHaveCount(0);
});

test("incident attachments can be uploaded, retry after failure and are marked as evidence", async ({ page }) => {
  await openPlan(page, "owner", "notes");
  await page.getByRole("button", { name: "New entry" }).click();
  await page.getByRole("tab", { name: "Incident" }).click();
  await page.getByLabel("Incident details (required)").fill("Synthetic incident with evidence");
  await page.getByText("Add attachment", { exact: true }).click();
  const attachment = page.locator('input[type="file"]');
  await attachment.setInputFiles({ name: "empty.txt", mimeType: "text/plain", buffer: Buffer.alloc(0) });
  await expect(page.getByRole("alert")).toHaveText("This file is empty.");
  await attachment.setInputFiles({ name: "Evidence.txt", mimeType: "text/plain", buffer: Buffer.from("Synthetic evidence") });
  await expect(page.getByRole("checkbox", { name: "Evidence.txt", exact: true })).toBeChecked();
  await page.getByRole("button", { name: "Save entry" }).click();
  const entry = page.getByRole("button", { name: /Synthetic incident with evidence/ });
  await expect(entry).toContainText("1 attached file");
  await entry.click();
  await page.getByText("Add attachment", { exact: true }).click();
  await expect(page.getByRole("checkbox", { name: "Evidence.txt · Evidence" })).toBeChecked();
});

test("documents persist evidence, report rejected uploads, and hide locked previews", async ({ page }) => {
  await openPlan(page, "owner", "documents");
  const original = page.getByRole("article").filter({ hasText: "Identification.txt" });
  const originalEvidence = original.getByRole("checkbox", { name: "Evidence" });
  await expect(originalEvidence).not.toBeChecked();
  await originalEvidence.check();
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(originalEvidence).toBeChecked();
  const locked = page.getByRole("article").filter({ hasText: "Other device.txt" });
  await expect(locked.getByRole("checkbox")).toBeDisabled();
  await expect(locked.getByRole("button", { name: "Unavailable on this device" })).toBeDisabled();

  const file = page.getByLabel("Upload a file");
  await file.setInputFiles({ name: "Empty.txt", mimeType: "text/plain", buffer: Buffer.alloc(0) });
  await expect(page.getByRole("alert")).toHaveText("This file is empty.");
  await file.setInputFiles({ name: "Sample.txt", mimeType: "text/plain", buffer: Buffer.from("Sample") });
  const uploaded = page.getByRole("article").filter({ hasText: "Sample.txt" });
  await uploaded.getByRole("button", { name: "Open document" }).click();
  const viewer = page.getByRole("dialog", { name: "Sample.txt" });
  await expect(viewer.getByText("Private sample document")).toBeVisible();
  await viewer.getByRole("button", { name: "Close" }).click();
  await expect(viewer).toHaveCount(0);
});

test("location page reports denied permission and shows the new location after retry", async ({ page, context }) => {
  await context.setGeolocation({ latitude: 53.5, longitude: -113.5, accuracy: 12 });
  await openPlan(page, "owner", "location");
  await expect(page.getByRole("alert")).toContainText("Location is unavailable.");
  const map = page.getByRole("region", { name: "Current location map" });
  await expect(map).toHaveCount(0);
  await context.grantPermissions(["geolocation"]);
  await page.getByRole("button", { name: "Update location" }).click();
  await expect(map).toBeVisible();
  await expect(page.getByText("Accuracy about 12 m · 53.5000, -113.5000")).toBeVisible();
});

test("assigned staff can update both checklists, preview an attachment and complete the consultation", async ({ page }) => {
  await openPlan(page, "staff", "staff");
  await expect(page.getByRole("heading", { name: "Consult Ticket #500" })).toBeVisible();
  await page.getByRole("navigation", { name: "Jump to a checklist" }).getByRole("button", { name: "Important documents" }).click();
  const docs = page.getByRole("group", { name: "Important documents" });
  await docs.getByRole("checkbox", { name: "Complete Prepare identification" }).check();
  await expect(docs.getByRole("checkbox", { name: "Complete Prepare identification" })).toBeChecked();
  await expect(docs.getByText("Document unavailable")).toBeVisible();
  await docs.getByRole("button", { name: "Identification.txt" }).click();
  const preview = page.getByRole("dialog", { name: "Identification.txt" });
  await expect(preview).toContainText("Private sample document");
  await preview.getByRole("button", { name: "Close" }).click();

  await page.getByRole("button", { name: "Backup plan" }).click();
  await docs.getByRole("button", { name: "Add step" }).click();
  const entry = docs.getByRole("textbox", { name: "backup documents step" }).last();
  await entry.fill("Store the backup safely");
  await expect(docs.getByRole("checkbox", { name: "Complete Store the backup safely" })).toBeVisible();
  await page.getByRole("button", { name: "Mark complete" }).click();
  await expect(page.getByRole("status")).toHaveText("Consultation complete");
});

test("staff can reply, escalate, and review closed tickets in the support inbox", async ({ page }) => {
  await openPlan(page, "staff", "inbox");
  const list = page.getByRole("complementary");
  const chat = page.getByRole("region", { name: "Selected support conversation" });
  await list.getByRole("button", { name: /Support Ticket #501/ }).click();
  const timeline = chat.getByRole("log");
  await expect(timeline.getByText("Could you help with this?")).toBeVisible();
  await expect(timeline.getByText("Message unsent")).toBeVisible();
  await expect(timeline).not.toContainText("Removed private words");
  const reply = chat.getByRole("textbox", { name: "Write a secure reply" });
  await reply.fill("I can help with the next step.");
  await chat.getByRole("button", { name: "Send reply" }).click();
  await expect(timeline.getByText("I can help with the next step.")).toBeVisible();
  await expect(reply).toHaveValue("");
  await chat.getByRole("button", { name: "Request admin help" }).click();
  await expect(timeline.getByText("Admin help requested")).toBeVisible();
  await expect(list.getByRole("button", { name: /^Need Admins(?: \(\d+\))?$/ })).toHaveAttribute("aria-pressed", "true");
  await expect(chat.getByRole("button", { name: "Request admin help" })).toHaveCount(0);

  await list.getByRole("button", { name: /^Plan Consults(?: \(\d+\))?$/ }).click();
  await list.getByRole("button", { name: /Consult Ticket #500/ }).click();
  await chat.getByRole("button", { name: "Close for everyone" }).click();
  await expect(list.getByRole("button", { name: /Consult Ticket #500/ })).toHaveCount(0);
  await list.getByRole("button", { name: "Archived", exact: true }).click();
  await list.getByRole("button", { name: /Consult Ticket #500/ }).click();
  await expect(chat.getByText("Archived", { exact: true })).toBeVisible();
  await expect(reply).toHaveCount(0);
});

test("invites replace friend discovery and keep existing requests and ticket chats separate", async ({ page }) => {
  await openPlan(page, "owner", "messages");
  await expect(page.getByRole("textbox", { name: "Find an exact username" })).toHaveCount(0);
  await page.getByRole("button", { name: "Invite", exact: true }).click();
  const invite = page.getByRole("dialog", { name: "Invite someone" });
  await expect(invite.getByText("ExampleCode1", { exact: true })).toBeVisible();
  await invite.getByRole("button", { name: "Refresh invite code" }).click();
  await expect(invite.getByText("ExampleCode2", { exact: true })).toBeVisible();
  await invite.getByRole("button", { name: "Close dialog" }).click();
  const code = page.getByRole("textbox", { name: "Have an invite code? (optional)", exact: true });
  await code.fill("ExpiredCode");
  await page.getByRole("button", { name: "Add connection" }).click();
  await expect(page.getByRole("alert")).toHaveText("This invite has expired or been used.");
  await code.fill("FreshCode1");
  await page.getByRole("button", { name: "Add connection" }).click();
  await expect(page.getByRole("status")).toHaveText("Connected with new_contact. You can add another code.");
  await expect(code).toHaveValue("");

  const requests = (handle: string) => page.locator(".request-row").filter({ hasText: handle });
  await requests("@request_contact").getByRole("button", { name: "Accept" }).click();
  await expect(requests("@request_contact")).toHaveCount(0);
  await requests("@decline_contact").getByRole("button", { name: "Decline" }).click();
  await expect(requests("@decline_contact")).toHaveCount(0);

  await page.getByRole("tab", { name: "DMs" }).press("End");
  await expect(page.getByRole("tab", { name: "Ticket chats" })).toHaveAttribute("aria-selected", "true");
  await expect(code).toHaveCount(0);
  await page.getByRole("button", { name: /Support Ticket #499/ }).click();
  await expect(page.getByRole("heading", { name: "Support Ticket #499" })).toBeVisible();
  await page.getByRole("textbox", { name: "Write a message" }).fill("Archived draft");
  await expect(page.getByRole("button", { name: "Send message", exact: true })).toBeDisabled();
  await page.getByRole("tab", { name: "Ticket chats" }).press("Home");
  await expect(code).toBeVisible();
});

test("ticket owners open shared records, manage invited friends and can unsend their messages", async ({ page }) => {
  await openPlan(page, "owner", "shared-messages");
  await page.getByRole("tab", { name: "Ticket chats" }).click();
  await page.getByRole("button", { name: /Support Ticket #501/ }).click();
  const log = page.getByRole("log");
  await log.getByRole("button", { name: /Shared fixture incident/ }).click();
  const incident = page.getByRole("dialog", { name: "Private fixture incident" });
  await expect(incident.getByText("Synthetic incident details")).toBeVisible();
  await expect(incident.getByText(/Fixture location/)).toBeVisible();
  await incident.getByRole("button", { name: "Open attached evidence" }).click();
  const document = page.getByRole("dialog", { name: "Identification.txt" });
  await expect(document.getByText("Private sample document")).toBeVisible();
  await document.getByRole("button", { name: "Close dialog", exact: true }).click();
  await incident.getByRole("button", { name: "Close dialog" }).click();
  await log.getByRole("button", { name: /Unavailable record/ }).click();
  await expect(page.getByRole("status").filter({ hasText: "This protected record is not stored" })).toBeVisible();
  await log.getByRole("button", { name: /Plan fixture/ }).click();
  await log.getByRole("button", { name: /Shared live location/ }).click();
  expect(await page.evaluate(() => (window as Window & { panelEvents?: unknown[][] }).panelEvents)).toEqual([
    ["page", "SafetyPlan"], ["page", "Location"],
  ]);
  await page.getByRole("button", { name: "Manage added friends" }).click();
  await expect(page.getByRole("button", { name: "Remove Staff" })).toHaveCount(0);
  await page.getByRole("button", { name: "Remove Guest Fixture" }).click();
  await expect(page.getByText("No added friends to remove.")).toBeVisible();
  await page.getByRole("button", { name: "Hide added friends" }).click();
  await page.getByRole("textbox", { name: "Write a message" }).fill("Synthetic reply");
  await page.getByRole("button", { name: "Send message" }).click();
  const reply = log.locator("article").filter({ hasText: "Synthetic reply" });
  await reply.getByRole("button", { name: "Unsend" }).click();
  await expect(reply).toHaveCount(0);
  await page.getByRole("button", { name: "Back to conversations" }).click();
  await expect(page.getByText("Select a ticket chat")).toBeVisible();
});

test("administrator filters accounts and confirms deletion only for eligible records", async ({ page }) => {
  await openPlan(page, "admin", "admin");
  const nav = page.getByRole("navigation", { name: "Administrator workspace" });
  await nav.getByRole("button", { name: "Accounts" }).click();
  await expect(page.getByRole("heading", { name: "Accounts", exact: true })).toBeFocused();
  const results = page.getByRole("region", { name: "Account results" });
  const row = (username: string) => results.getByRole("row").filter({ hasText: "@" + username });
  await expect(row("main_admin").getByRole("button", { name: "Delete" })).toBeDisabled();
  await page.getByLabel("Profile status").selectOption("required");
  await expect(row("main_admin")).toHaveCount(0);
  await expect(row("pending_person")).toBeVisible();

  await page.getByRole("button", { name: "Create account" }).click();
  const create = page.getByRole("dialog", { name: "Create a managed account" });
  await create.getByLabel("Username").fill("staff_fixture");
  await create.getByLabel("Temporary password", { exact: true }).fill("Sample-Password1");
  await create.getByLabel("Role").selectOption("STAFF");
  await create.getByLabel("Full name").fill("Fixture Staff");
  await create.getByRole("button", { name: "Create account" }).click();
  await expect(row("staff_fixture")).toBeVisible();

  await page.getByRole("searchbox", { name: "Search accounts" }).fill("Fixture Staff");
  await expect(row("pending_person")).toHaveCount(0);
  await row("staff_fixture").getByRole("button", { name: "Delete" }).click();
  const confirm = page.getByRole("dialog", { name: "Delete this account?" });
  await confirm.getByRole("button", { name: "Cancel" }).click();
  await expect(row("staff_fixture")).toBeVisible();
  await row("staff_fixture").getByRole("button", { name: "Delete" }).click();
  await confirm.getByRole("button", { name: "Delete @staff_fixture" }).click();
  await expect(results.getByText("No matching accounts.")).toBeVisible();

  await nav.getByRole("button", { name: "Consultations" }).click();
  await expect(page.getByRole("heading", { name: "Consultation queue" })).toBeVisible();
  await nav.getByRole("button", { name: "Escalation chats" }).click();
  await expect(page.getByRole("heading", { name: "Ticket inbox" })).toBeVisible();
});

test("staff availability persists when navigating and staff can open the assigned plan", async ({ page }) => {
  await openPlan(page, "staff", "staff-office");
  const nav = page.getByRole("navigation", { name: "Staff workspace" });
  await page.getByRole("button", { name: "Pause availability" }).click();
  await expect(page.getByText("Not accepting new requests")).toBeVisible();
  await nav.getByRole("button", { name: "Consultations" }).click();
  await expect(page.getByRole("button", { name: "Become available" })).toBeVisible();
  await page.getByRole("button", { name: "Become available" }).click();
  await expect(page.getByText("Available for new requests")).toBeVisible();
  await page.getByRole("button", { name: /Consult Ticket #500/ }).click();
  await expect(page.getByRole("heading", { name: "Consult Ticket #500" })).toBeVisible();
  await nav.getByRole("button", { name: "Support chats" }).click();
  await expect(page.getByRole("heading", { name: "Ticket inbox" })).toBeVisible();
});
