// Sends transactional email notifications over Hostinger SMTP with consent and idempotency checks.
const nodemailer = require("nodemailer");
const { createHash } = require("node:crypto");
const { defineSecret, defineString } = require("firebase-functions/params");
const { auth, db, FieldValue } = require("./firebaseAdminClient");
const { roleFromUser, text, validEmail } = require("./accountPolicy");
const { readProfile } = require("./accountStorage");

const smtpHost = defineString("SMTP_HOST", { default: "smtp.hostinger.com" });
const smtpPort = defineString("SMTP_PORT", { default: "465" });
const smtpUser = defineString("SMTP_USER", { default: "donotreply@yourolivebranch.ca" });
const emailFrom = defineString("EMAIL_FROM", { default: "Olive Branch <donotreply@yourolivebranch.ca>" });
const smtpPass = defineSecret("SMTP_PASS");

const EMAIL_SECRETS = [smtpPass];
const CONSENT_ROLES = new Set(["USER", "SAFE_CONTACT"]);

let transportFactoryOverride = null;
let cachedTransportKey = "";
let cachedTransport = null;

function _setTransportFactory(factory) {
  transportFactoryOverride = typeof factory === "function" ? factory : null;
  cachedTransportKey = "";
  cachedTransport = null;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function readSmtpConfig() {
  const host = String(smtpHost.value() || process.env.SMTP_HOST || "smtp.hostinger.com").trim();
  const parsedPort = Number(smtpPort.value() || process.env.SMTP_PORT || 465);
  const port = Number.isInteger(parsedPort) && parsedPort > 0 ? parsedPort : 465;
  const user = String(smtpUser.value() || process.env.SMTP_USER || "").trim();
  let pass = "";
  try {
    pass = String(smtpPass.value() || "").trim();
  } catch {
    pass = String(process.env.SMTP_PASS || "").trim();
  }
  const configuredFrom = String(emailFrom.value() || process.env.EMAIL_FROM || "").trim();
  const from = configuredFrom || (user ? `Olive Branch <${user}>` : "");
  return { host, port, user, pass, from };
}

function getTransport(config) {
  if (transportFactoryOverride) {
    return transportFactoryOverride(config);
  }
  const key = `${config.host}:${config.port}:${config.user}`;
  if (cachedTransport && cachedTransportKey === key) return cachedTransport;
  const secure = config.port === 465;
  cachedTransport = nodemailer.createTransport({
    host: config.host,
    port: config.port,
    secure,
    requireTLS: !secure,
    auth: { user: config.user, pass: config.pass },
    connectionTimeout: 10_000,
    greetingTimeout: 10_000,
    socketTimeout: 15_000,
  });
  cachedTransportKey = key;
  return cachedTransport;
}

function buildEmail({ subject, heading, paragraphs = [], footerNote = "" }) {
  const safeSubject = text(subject, 200);
  const safeHeading = text(heading || subject, 200);
  const cleanParagraphs = paragraphs
    .map((line) => String(line ?? "").trim())
    .filter(Boolean);
  const footer =
    footerNote ||
    "For your privacy, Olive Branch emails never include chat messages, location coordinates, or safety plan details.";
  const textBody = [safeHeading, "", ...cleanParagraphs, "", footer].join("\n");
  const htmlParagraphs = cleanParagraphs
    .map((line) => `<p style="margin:0 0 12px;color:#273026;font-size:15px;line-height:1.55;">${escapeHtml(line)}</p>`)
    .join("");
  const html = [
    "<!doctype html>",
    '<html lang="en"><body style="margin:0;padding:24px;background:#f5f6f1;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;">',
    '<div style="max-width:560px;margin:0 auto;background:#ffffff;border:1px solid #d6dfd2;border-radius:12px;padding:24px;">',
    '<p style="margin:0 0 8px;color:#486137;font-size:12px;font-weight:700;letter-spacing:0.06em;text-transform:uppercase;">Olive Branch</p>',
    `<h1 style="margin:0 0 16px;color:#1b241a;font-size:20px;line-height:1.3;">${escapeHtml(safeHeading)}</h1>`,
    htmlParagraphs,
    `<hr style="border:none;border-top:1px solid #e4ebe1;margin:20px 0 14px;" />`,
    `<p style="margin:0;color:#556154;font-size:12px;line-height:1.5;">${escapeHtml(footer)}</p>`,
    "</div></body></html>",
  ].join("");
  return { subject: safeSubject, text: textBody, html };
}

async function sendEmail({ to, subject, text: textBody, html }) {
  const recipient = text(to, 254).toLowerCase();
  if (!validEmail(recipient)) {
    return { sent: false, reason: "invalid_recipient" };
  }
  const config = readSmtpConfig();
  if (!config.user || !config.pass || !config.from) {
    console.warn("Email notification skipped because SMTP_USER or SMTP_PASS is not configured.");
    return { sent: false, reason: "not_configured" };
  }
  const transport = getTransport(config);
  const info = await transport.sendMail({
    from: config.from,
    to: recipient,
    subject,
    text: textBody,
    html,
  });
  return { sent: true, messageId: info?.messageId || "" };
}

async function resolveEmailRecipient(uid, allowedRoles) {
  if (typeof uid !== "string" || !uid) return null;
  let user;
  try {
    user = await auth.getUser(uid);
  } catch (error) {
    if (error?.code === "auth/user-not-found") return null;
    throw error;
  }
  if (!user || user.disabled) return null;
  const role = roleFromUser(user);
  if (Array.isArray(allowedRoles) && !allowedRoles.includes(role)) return null;
  const profile = await readProfile(uid);
  if (!profile) return null;
  const email = text(profile.email, 254).toLowerCase();
  if (!validEmail(email)) return null;
  if (CONSENT_ROLES.has(role) && profile.emailCommunicationConsent !== true) {
    return null;
  }
  const username = text(profile.username || user.displayName, 24).toLowerCase();
  return { uid, role, email, username };
}

function logDocId(dedupKey) {
  return createHash("sha256").update(String(dedupKey)).digest("hex");
}

function isAlreadyExistsError(error) {
  return error?.code === 6 ||
    error?.code === "already-exists" ||
    /already exists/i.test(String(error?.message || ""));
}

async function sendEmailOnce(dedupKey, meta, mail) {
  const eventType = text(meta?.eventType, 80);
  const recipient = text(mail?.to, 254).toLowerCase();
  if (!validEmail(recipient)) {
    console.info(JSON.stringify({ eventType, status: "skipped", reason: "invalid_recipient" }));
    return { sent: false, reason: "invalid_recipient" };
  }
  const ref = db.collection("emailNotificationLogs").doc(logDocId(dedupKey));
  try {
    await ref.create({
      uid: text(meta?.uid, 128),
      eventType,
      dedupKey: String(dedupKey).slice(0, 200),
      status: "sending",
      createdAt: FieldValue.serverTimestamp(),
    });
  } catch (error) {
    if (isAlreadyExistsError(error)) {
      console.info(JSON.stringify({ eventType, status: "skipped", reason: "duplicate" }));
      return { sent: false, reason: "duplicate" };
    }
    throw error;
  }
  try {
    const result = await sendEmail(mail);
    if (!result.sent) {
      await ref.delete().catch(() => {});
      console.info(JSON.stringify({ eventType, status: "skipped", reason: result.reason || "not_sent" }));
      return result;
    }
    await ref.set({ status: "sent", sentAt: FieldValue.serverTimestamp() }, { merge: true });
    console.info(JSON.stringify({ eventType, status: "sent" }));
    return result;
  } catch (error) {
    await ref.delete().catch(() => {});
    throw error;
  }
}

async function claimCooldown(cooldownKey, windowMs, now = Date.now()) {
  const ref = db.collection("emailCooldowns").doc(String(cooldownKey));
  return db.runTransaction(async (transaction) => {
    const snapshot = await transaction.get(ref);
    const lastSentAt = Number(snapshot.exists ? snapshot.get("lastSentAt") : 0) || 0;
    if (lastSentAt > 0 && now - lastSentAt < windowMs) {
      return false;
    }
    transaction.set(ref, {
      lastSentAt: now,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    return true;
  });
}

module.exports = {
  EMAIL_SECRETS,
  buildEmail,
  sendEmail,
  sendEmailOnce,
  resolveEmailRecipient,
  claimCooldown,
  _test: {
    _setTransportFactory,
    readSmtpConfig,
    logDocId,
    escapeHtml,
  },
};
