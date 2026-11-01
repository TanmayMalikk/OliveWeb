// All safe-app roles: shows account and privacy settings.
import { useEffect, useState } from "react";
import { Copy, RefreshCw } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Field, Form, Modal } from "../../../components/SharedComponents";
import { newEvent, validPassword, validPin, type Profile } from "../../../domain/DomainModels";
import { accounts, triggerHash } from "../../../services/AccountService";
import { issueEntryLink, savedEntryLink } from "../../../services/PrivateEntryLinks";
export default function Settings() {
  const s = useSession();
  const p = s.profile!;
  const [link, setLink] = useState<string | null>(null);
  const [confirmRotation, setConfirmRotation] = useState(false);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    let live = true;
    void savedEntryLink(accounts.identity!.uid)
      .then((value) => {
        if (live) setLink(value);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, []);
  return (
    <div className="settings-grid">
      <section className="panel">
        <h2>Your everyday.</h2>
        <p>The cover you return to when you leave this space.</p>
        <Form
          onSubmit={async (data) => {
            await s.perform(() =>
              s.saveProfile(
                {
                  ...p,
                  coverType: String(data.get("cover")) as Profile["coverType"],
                  language: String(data.get("language")),
                },
                "Updated cover and language",
              ),
            );
          }}
        >
          <label className="field">
            <span>Cover app</span>
            <select name="cover" defaultValue={p.coverType}>
              <option value="FOOD">Olive</option>
              <option value="NEWS">Daily Digest</option>
              <option value="FITNESS">Fitness Tracker</option>
              <option value="GAME">Little Grove</option>
            </select>
          </label>
          <label className="field">
            <span>Profile language</span>
            <select name="language" defaultValue={p.language}>
              {[
                "English",
                "French",
                "Spanish",
                "Arabic",
                "Chinese",
                "Tagalog",
                "Cree",
              ].map((l) => (
                <option key={l}>{l}</option>
              ))}
            </select>
            <small>
              Your language preference syncs with mobile. The web interface
              currently uses English.
            </small>
          </label>
          <Button type="submit" disabled={s.busy}>
            Save preferences
          </Button>
        </Form>
      </section>
      <section className="panel">
        <h2>Your personal link.</h2>
        <p>
          Your link opens an entry gate. A password or enrolled-browser PIN is
          still required.
        </p>
        {link && (
          <>
            <Field label="Personal entry link" value={link} readOnly />
            <Button
              variant="outline"
              onClick={() =>
                void navigator.clipboard
                  .writeText(link)
                  .then(() => setCopied(true))
                  .catch(() => {})
              }
            >
              <Copy size={16} />
              {copied ? "Copied" : "Copy link"}
            </Button>
          </>
        )}
        <Button variant="quiet" onClick={() => setConfirmRotation(true)}>
          <RefreshCw size={16} /> Create a replacement link
        </Button>
      </section>
      <section className="panel">
        <h2>Account password.</h2>
        <Form
          onSubmit={async (data) => {
            await s.perform(async () => {
              const next = String(data.get("next"));
              if (!validPassword(next) || next !== data.get("confirmation"))
                throw new Error(
                  "Use matching 8–24 character passwords with a letter, number, and special character.",
                );
              await accounts.changePassword(String(data.get("current")), next);
              await s.record("settings", newEvent("Changed account password"));
              s.clearMessage();
            });
          }}
        >
          <Field
            label="Current password"
            name="current"
            type="password"
            autoComplete="current-password"
            required
          />
          <Field
            label="New password"
            name="next"
            type="password"
            autoComplete="new-password"
            required
            maxLength={24}
          />
          <Field
            label="Confirm new password"
            name="confirmation"
            type="password"
            autoComplete="new-password"
            required
            maxLength={24}
          />
          <Button type="submit" disabled={s.busy}>
            Change password
          </Button>
        </Form>
      </section>
      {p.role === "USER" && (
        <>
          <section className="panel">
            <h2>Browser PIN.</h2>
            <Form
              onSubmit={async (data) => {
                await s.perform(async () => {
                  const next = String(data.get("next"));
                  if (!validPin(next) || next !== data.get("confirmation"))
                    throw new Error("Enter matching PINs with 4–6 digits.");
                  await accounts.changePin(
                    String(data.get("current")),
                    next,
                    p,
                  );
                  await s.record("settings", newEvent("Changed browser PIN"));
                });
              }}
            >
              <Field
                label="Current PIN"
                name="current"
                type="password"
                inputMode="numeric"
                required
                maxLength={6}
              />
              <Field
                label="New PIN"
                name="next"
                type="password"
                inputMode="numeric"
                pattern="[0-9]{4,6}"
                required
                maxLength={6}
              />
              <Field
                label="Confirm new PIN"
                name="confirmation"
                type="password"
                inputMode="numeric"
                pattern="[0-9]{4,6}"
                required
                maxLength={6}
              />
              <Button type="submit" disabled={s.busy}>
                Change PIN
              </Button>
            </Form>
          </section>
          <section className="panel">
            <h2>Cover trigger.</h2>
            <p>
              Your web entry uses your personal link. This trigger preference
              also syncs with your Android cover.
            </p>
            <Form
              onSubmit={async (data) => {
                await s.perform(async () => {
                  const value = String(data.get("trigger"));
                  if (value.trim().length < 3 || value.trim().length > 64)
                    throw new Error("Choose 3–64 characters.");
                  await s.saveProfile(
                    { ...p, triggerHash: await triggerHash(value) },
                    "Updated private entry trigger",
                  );
                });
              }}
            >
              <Field
                label="New trigger phrase"
                name="trigger"
                type="password"
                minLength={3}
                maxLength={64}
                required
                autoComplete="off"
              />
              <Button type="submit" disabled={s.busy}>
                Save trigger
              </Button>
            </Form>
          </section>
        </>
      )}
      <section className="panel">
        <h2>Your account connection.</h2>
        <p>
          {s.cloudMode
            ? "Profiles, notes, contacts, and activity use the same Firebase paths as Android."
            : "This account is encrypted in this browser. It cannot read the Android app’s local vault."}
        </p>
        <Button
          variant="outline"
          disabled={s.busy}
          onClick={() => void s.perform(s.refresh)}
        >
          <RefreshCw size={16} /> Refresh account
        </Button>
        <p className="help-note">
          Quick Exit does not clear browser history or prevent screen recording.
          Keep access to your device and browser secure.
        </p>
      </section>
      {confirmRotation && (
        <Modal
          title="Replace your personal link?"
          onClose={() => setConfirmRotation(false)}
        >
          <p>
            Your previous link will stop working. Save the replacement before
            you leave.
          </p>
          <Button
            disabled={s.busy}
            onClick={() =>
              void s.perform(async () => {
                setLink(await issueEntryLink(accounts.identity!.uid));
                setConfirmRotation(false);
              })
            }
          >
            Create replacement
          </Button>
          <Button variant="quiet" onClick={() => setConfirmRotation(false)}>
            Keep current link
          </Button>
        </Modal>
      )}
    </div>
  );
}

