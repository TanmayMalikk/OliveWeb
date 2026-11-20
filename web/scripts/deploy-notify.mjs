// Web deployers: reports Hostinger build and startup status to Discord.
import "../server/serverEnvironment.mjs";
import { execFileSync, spawn } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const WEB_ROOT = resolve(import.meta.dirname, "..");
const REPO_ROOT = resolve(WEB_ROOT, "..");
const STATE_PATHS = [
  resolve(WEB_ROOT, "node_modules/.deploy-notify.json"),
  resolve(WEB_ROOT, "dist/.deploy-notify.json"),
];
const BUILD_INFO_PATH = resolve(WEB_ROOT, "dist/build-info.json");
const DEDUP_WINDOW_MS = 10 * 60 * 1000;
const MAX_OUTPUT_CHARS = 16 * 1024;
const MAX_EMBED_SNIPPET_CHARS = 1400;

const COLORS = {
  building: 16761095,
  built: 3447003,
  live: 3066993,
  failed: 15158332,
};

function stripAnsi(text) {
  return text.replace(
    /[\u001b\u009b][[()#;?]*(?:[0-9]{1,4}(?:;[0-9]{0,4})*)?[0-9A-ORZcf-nqry=><~]/g,
    "",
  );
}

function tailSnippet(text, maxLines = 20, maxChars = MAX_EMBED_SNIPPET_CHARS) {
  const cleaned = stripAnsi(String(text || "")).replace(/\r\n/g, "\n");
  const lines = cleaned.split("\n");
  while (lines.length && !lines[lines.length - 1].trim()) lines.pop();
  const joined = lines.slice(-maxLines).join("\n").trim();
  if (!joined) return "No output captured.";
  return joined.length > maxChars ? `...\n${joined.slice(-maxChars)}` : joined;
}

function readJson(path) {
  try {
    if (!existsSync(path)) return null;
    return JSON.parse(readFileSync(path, "utf8"));
  } catch {
    return null;
  }
}

function writeJson(path, value) {
  try {
    const dir = dirname(path);
    if (!existsSync(dir)) return;
    writeFileSync(path, JSON.stringify(value, null, 2));
  } catch (error) {
    console.warn(`[deploy-notify] Failed to write ${path}:`, error.message);
  }
}

function readState() {
  const candidates = STATE_PATHS.map(readJson).filter(Boolean);
  if (!candidates.length) return null;
  candidates.sort((a, b) => Date.parse(b.updatedAt || 0) - Date.parse(a.updatedAt || 0));
  return candidates[0];
}

function writeState(state) {
  for (const path of STATE_PATHS) {
    writeJson(path, state);
  }
}

function runGit(args) {
  try {
    return execFileSync("git", args, {
      cwd: REPO_ROOT,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    }).trim();
  } catch {
    return "";
  }
}

function getGitMetadata() {
  const buildInfo = readJson(BUILD_INFO_PATH);
  const sha = runGit(["rev-parse", "HEAD"]) || buildInfo?.sha || process.env.GIT_COMMIT_SHA || "unknown";
  const shortSha =
    runGit(["rev-parse", "--short", "HEAD"]) ||
    buildInfo?.shortSha ||
    (sha !== "unknown" ? sha.slice(0, 7) : "unknown");
  const branch = runGit(["rev-parse", "--abbrev-ref", "HEAD"]) || buildInfo?.branch || "unknown";
  const subject = runGit(["log", "-1", "--format=%s"]) || buildInfo?.subject || "Unavailable";
  const author = runGit(["log", "-1", "--format=%an"]) || buildInfo?.author || "Unknown";
  return { sha, shortSha, branch, subject, author };
}

function getWebhookUrl() {
  if (process.env.CI === "true") return "";
  return process.env.DISCORD_WEBHOOK_URL?.trim() || "";
}

function buildMetadataFields(meta, extraFields = []) {
  const commitText =
    meta.subject && meta.subject !== "Unavailable"
      ? `\`${meta.shortSha}\` — ${meta.subject.slice(0, 180)}`
      : `\`${meta.shortSha}\``;
  return [
    { name: "Branch", value: `\`${meta.branch}\``, inline: true },
    { name: "Author", value: meta.author, inline: true },
    { name: "Commit", value: commitText, inline: false },
    ...extraFields,
  ];
}

function webhookEndpoint(rawUrl, messageId = null) {
  const url = new URL(rawUrl);
  if (messageId) {
    url.pathname = `${url.pathname.replace(/\/$/, "")}/messages/${messageId}`;
    url.searchParams.delete("wait");
  } else {
    url.searchParams.set("wait", "true");
  }
  return url;
}

async function sendOrUpdateDiscord({ webhookUrl, messageId, embed }) {
  if (!webhookUrl) return messageId ?? null;
  const payload = JSON.stringify({ embeds: [embed] });
  const headers = {
    "Content-Type": "application/json",
    "User-Agent": "OliveWeb-Hostinger-Deploy",
  };

  if (messageId) {
    try {
      const response = await fetch(webhookEndpoint(webhookUrl, messageId), {
        method: "PATCH",
        headers,
        body: payload,
        signal: AbortSignal.timeout(8000),
      });
      if (response.ok) {
        const body = await response.json().catch(() => null);
        return body?.id ?? messageId;
      }
      console.warn(`[deploy-notify] Discord PATCH returned HTTP ${response.status}; posting new message.`);
    } catch (error) {
      console.warn("[deploy-notify] Discord PATCH failed; posting new message:", error.message);
    }
  }

  try {
    const response = await fetch(webhookEndpoint(webhookUrl), {
      method: "POST",
      headers,
      body: payload,
      signal: AbortSignal.timeout(8000),
    });
    if (!response.ok) {
      console.warn(`[deploy-notify] Discord POST returned HTTP ${response.status}.`);
      return messageId ?? null;
    }
    const body = await response.json().catch(() => null);
    return body?.id ?? messageId ?? null;
  } catch (error) {
    console.warn("[deploy-notify] Discord POST failed:", error.message);
    return messageId ?? null;
  }
}

function runStep({ name, command, args }) {
  return new Promise((resolveStep) => {
    let buffer = "";
    const appendOutput = (chunk) => {
      buffer += chunk.toString("utf8");
      if (buffer.length > MAX_OUTPUT_CHARS) {
        buffer = buffer.slice(-MAX_OUTPUT_CHARS);
      }
    };

    const child = spawn(command, args, {
      cwd: WEB_ROOT,
      env: process.env,
      shell: process.platform === "win32" && command !== process.execPath,
      stdio: ["ignore", "pipe", "pipe"],
    });

    child.stdout.on("data", (chunk) => {
      process.stdout.write(chunk);
      appendOutput(chunk);
    });
    child.stderr.on("data", (chunk) => {
      process.stderr.write(chunk);
      appendOutput(chunk);
    });
    child.on("error", (error) => {
      appendOutput(`\n${error.stack || error.message}`);
      resolveStep({ name, code: 1, output: buffer });
    });
    child.on("close", (code) => {
      resolveStep({ name, code: code ?? 1, output: buffer });
    });
  });
}

async function runBuild() {
  const webhookUrl = getWebhookUrl();
  const meta = getGitMetadata();
  const existing = readState();
  const sameRecentDeploy =
    existing &&
    existing.sha === meta.sha &&
    existing.messageId &&
    Date.now() - Date.parse(existing.updatedAt || 0) < DEDUP_WINDOW_MS;

  let messageId = sameRecentDeploy ? existing.messageId : null;
  messageId = await sendOrUpdateDiscord({
    webhookUrl,
    messageId,
    embed: {
      title: "⏳ Building & Deploying",
      description: "Hostinger has started installing and building the web app.",
      color: COLORS.building,
      fields: buildMetadataFields(meta),
      timestamp: new Date().toISOString(),
    },
  });

  writeState({
    sha: meta.sha,
    messageId,
    status: "building",
    updatedAt: new Date().toISOString(),
  });

  const steps = [
    { name: "security:check", command: process.execPath, args: ["scripts/check-public-secrets.mjs"] },
    { name: "qr:generate", command: process.execPath, args: ["scripts/generate-download-qr.mjs"] },
    { name: "tsc -b", command: "npx", args: ["tsc", "-b"] },
    { name: "vite build", command: "npx", args: ["vite", "build"] },
  ];

  for (const step of steps) {
    const result = await runStep(step);
    if (result.code !== 0) {
      const snippet = tailSnippet(result.output);
      messageId = await sendOrUpdateDiscord({
        webhookUrl,
        messageId,
        embed: {
          title: "❌ Hostinger Build Failed",
          description: `Step \`${result.name}\` exited with code \`${result.code}\`.\n\`\`\`\n${snippet}\n\`\`\``,
          color: COLORS.failed,
          fields: buildMetadataFields(meta),
          timestamp: new Date().toISOString(),
        },
      });
      writeState({
        sha: meta.sha,
        messageId,
        status: "build-failed",
        updatedAt: new Date().toISOString(),
      });
      process.exit(result.code || 1);
    }
  }

  try {
    mkdirSync(dirname(BUILD_INFO_PATH), { recursive: true });
    writeFileSync(
      BUILD_INFO_PATH,
      JSON.stringify({ ...meta, builtAt: new Date().toISOString() }, null, 2),
    );
  } catch (error) {
    console.warn("[deploy-notify] Failed to write build-info.json:", error.message);
  }

  messageId = await sendOrUpdateDiscord({
    webhookUrl,
    messageId,
    embed: {
      title: "🔄 Build Complete — Starting Server...",
      description: "Production assets built cleanly. Waiting for Hostinger to start the Node.js server.",
      color: COLORS.built,
      fields: buildMetadataFields(meta),
      timestamp: new Date().toISOString(),
    },
  });

  writeState({
    sha: meta.sha,
    messageId,
    status: "built",
    updatedAt: new Date().toISOString(),
  });
}

export async function notifyServerLive({ host = "0.0.0.0", port = 4173 } = {}) {
  try {
    const webhookUrl = getWebhookUrl();
    if (!webhookUrl) return;
    const meta = getGitMetadata();
    const existing = readState();
    if (existing?.sha === meta.sha && existing?.status === "live") {
      return;
    }
    const existingMessageId = existing?.sha === meta.sha ? existing.messageId : null;
    const messageId = await sendOrUpdateDiscord({
      webhookUrl,
      messageId: existingMessageId,
      embed: {
        title: "✅ Hostinger Deployment Successful",
        description: `Server is live and listening on \`${host}:${port}\`.`,
        color: COLORS.live,
        fields: buildMetadataFields(meta),
        timestamp: new Date().toISOString(),
      },
    });
    writeState({
      sha: meta.sha,
      messageId,
      status: "live",
      updatedAt: new Date().toISOString(),
    });
  } catch (error) {
    console.warn("[deploy-notify] Failed to send server-live notification:", error.message);
  }
}

export async function notifyServerFailure(error, phase = "startup") {
  try {
    const webhookUrl = getWebhookUrl();
    if (!webhookUrl) return;
    const meta = getGitMetadata();
    const existing = readState();
    const existingMessageId = existing?.sha === meta.sha ? existing.messageId : null;
    const snippet = tailSnippet(error?.stack || error?.message || String(error));
    const messageId = await sendOrUpdateDiscord({
      webhookUrl,
      messageId: existingMessageId,
      embed: {
        title: "❌ Hostinger Server Failed to Start",
        description: `Server crashed during \`${phase}\`.\n\`\`\`\n${snippet}\n\`\`\``,
        color: COLORS.failed,
        fields: buildMetadataFields(meta),
        timestamp: new Date().toISOString(),
      },
    });
    writeState({
      sha: meta.sha,
      messageId,
      status: "server-failed",
      updatedAt: new Date().toISOString(),
    });
  } catch (notifyError) {
    console.warn("[deploy-notify] Failed to send server-failure notification:", notifyError.message);
  }
}

function isExecutedDirectly() {
  try {
    return (
      Boolean(process.argv[1]) &&
      realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))
    );
  } catch {
    return false;
  }
}

if (isExecutedDirectly() && process.argv[2] === "build") {
  await runBuild();
}
