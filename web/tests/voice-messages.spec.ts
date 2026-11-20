import { expect, test, type Page } from "./coverageFixture";

async function openVoiceFixture(page: Page, viewer?: "storage" | "chat") {
  await page.route(/\/__test_voice(?:\?.*)?$/, async route => {
    const response = await route.fetch({ url: new URL("/", route.request().url()).href });
    const html = await response.text();
    await route.fulfill({ response, body: html.replace("/src/main.tsx", "/tests/voiceFixture.tsx") });
  });
  // Keep the real UI and browser object URLs. Replace the microphone and remote media boundary.
  await page.route(/\/src\/services\/MediaService\.ts(?:\?|$)/, route => route.fulfill({
    status: 307, headers: { location: "/tests/voiceMediaMock.mjs" },
  }));
  await page.addInitScript(() => {
    const fixture = Object.assign(window, { voiceCalls: [] as unknown[][] });
    const revoke = URL.revokeObjectURL.bind(URL);
    URL.revokeObjectURL = url => {
      fixture.voiceCalls.push(["revoke", url]);
      revoke(url);
    };
    Object.defineProperty(navigator.mediaDevices, "getUserMedia", {
      value: async () => ({
        getTracks: () => [{ stop: () => fixture.voiceCalls.push(["stop-track"]) }],
      }),
    });
    class TestRecorder {
      static isTypeSupported() { return true; }
      state = "inactive";
      ondataavailable?: (event: { data: Blob }) => void;
      onstop?: () => void;
      start() { this.state = "recording"; }
      stop() {
        this.state = "inactive";
        queueMicrotask(() => {
          this.ondataavailable?.({ data: new Blob(["recording"]) });
          this.onstop?.();
        });
      }
    }
    Object.defineProperty(window, "MediaRecorder", { value: TestRecorder });
  });
  await page.goto(viewer ? `/__test_voice?viewer=${viewer}` : "/__test_voice");
}

test("audio can be downloaded from storage and played in chat", async ({ page }) => {
  await openVoiceFixture(page, "storage");
  await expect(page.getByText("This file cannot be previewed in the browser.")).toBeVisible();
  await expect(page.getByRole("link", { name: "Download decrypted file" })).toHaveAttribute("download", "Recording.m4a");
  await expect(page.locator("audio")).toHaveCount(0);
  await page.goto("/__test_voice?viewer=chat");
  await expect(page.locator("audio[controls]")).toBeVisible();
});

async function hidePage(page: Page) {
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { value: true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
}

function voiceCalls(page: Page) {
  return page.evaluate(() => (window as Window & { voiceCalls?: unknown[][] }).voiceCalls);
}

test("voice recording previews locally and retries without uploading or creating the message twice", async ({ page }) => {
  await openVoiceFixture(page);
  const record = page.getByRole("button", { name: "Record a voice message" });
  await record.click();
  await expect(page.getByRole("status")).toContainText("Recording");
  await page.getByRole("button", { name: "Stop recording" }).click();
  const preview = page.getByLabel("Preview voice message");
  await expect(preview).toHaveAttribute("src", /^blob:/);
  const previewUrl = await preview.getAttribute("src");

  const send = page.getByRole("button", { name: "Send voice message" });
  await send.click();
  await expect(page.getByRole("status")).toHaveText("Could not send this voice message. Try again.");
  await expect(preview).toHaveAttribute("src", previewUrl!);
  await send.click();
  await expect(record).toBeEnabled();
  await expect(preview).toHaveCount(0);
  expect(await voiceCalls(page)).toEqual([
    ["stop-track"], ["upload", "Voice message.m4a", 9], ["prepare", "test-media", "test-room"],
    ["publish", "stable-message-id"], ["publish", "stable-message-id"], ["revoke", previewUrl],
  ]);
});

test("backgrounding releases the microphone and revokes the received audio URL", async ({ page }) => {
  await openVoiceFixture(page);
  const play = page.getByRole("button", { name: "Play voice message" });
  await play.click();
  await expect(page.getByRole("status")).toHaveText("Could not open this voice message.");
  await play.click();
  const received = page.getByLabel("Voice message", { exact: true });
  await expect(received).toHaveAttribute("src", /^blob:/);
  const receivedUrl = await received.getAttribute("src");

  const record = page.getByRole("button", { name: "Record a voice message" });
  await record.click();
  await expect(page.getByRole("status")).toContainText("Recording");
  await hidePage(page);

  await expect(record).toBeEnabled();
  await expect(play).toBeVisible();
  await expect(page.getByLabel("Preview voice message")).toHaveCount(0);
  expect(await voiceCalls(page)).toEqual([
    ["open", "received-voice"], ["open", "received-voice"], ["stop-track"], ["revoke", receivedUrl],
  ]);
});
