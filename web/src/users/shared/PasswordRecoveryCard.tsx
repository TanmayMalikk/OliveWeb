// All signed-in roles: manages zero-knowledge OPAQUE password recovery and ARK access.
import { useState } from "react";
import { ShieldCheck } from "lucide-react";
import { Button, Field } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";

/** Renders password recovery setup or browser-tab ARK unlock inside account settings. */
export function PasswordRecoveryCard({
  onNotice,
}: {
  onNotice?: (message: string) => void;
}) {
  const s = useSession();
  const [password, setPassword] = useState("");

  if (!s.cloudMode) return null;

  return (
    <section className="panel" data-walkthrough-id="settings-password-recovery">
      <h2>Password recovery</h2>
      <p>
        Use your account password as a zero-knowledge recovery method so you can unlock encrypted documents, notes, and conversations across devices.
      </p>
      {s.passwordRecoveryEnrolled === null ? (
        <p className="help-note">Checking recovery status…</p>
      ) : s.passwordRecoveryEnrolled ? (
        <>
          <p className="help-note">
            <ShieldCheck size={16} aria-hidden="true" /> Password recovery is active for this account.
          </p>
          {s.hasEncryptedAccess ? (
            <p className="help-note">
              Encrypted access is unlocked for this browser tab.
            </p>
          ) : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                const entered = password;
                setPassword("");
                void s.perform(async () => {
                  await s.recoverEncryptedAccess(entered);
                  s.clearMessage();
                  onNotice?.("Encrypted documents and messages are unlocked for this browser tab.");
                });
              }}
            >
              <p className="help-note">This is the same as your account password.</p>
              <Field
                label="Account password"
                type="password"
                autoComplete="off"
                value={password}
                disabled={s.busy}
                required
                onChange={(event: { target: { value: string } }) =>
                  setPassword(event.target.value)
                }
              />
              <Button type="submit" disabled={s.busy || !password}>
                {s.busy ? "Unlocking…" : "Unlock encrypted access"}
              </Button>
            </form>
          )}
        </>
      ) : s.passwordRecoveryKeysetExists ? (
        <p className="help-note">
          An encrypted keyset already exists on another device. Enable password recovery on that device first so your existing encrypted data stays accessible.
        </p>
      ) : (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            const entered = password;
            setPassword("");
            void s.perform(async () => {
              await s.enablePasswordRecovery(entered);
              s.clearMessage();
              onNotice?.("Password recovery enabled.");
            });
          }}
        >
          <Field
            label="Account password"
            type="password"
            autoComplete="current-password"
            value={password}
            disabled={s.busy}
            required
            onChange={(event: { target: { value: string } }) =>
              setPassword(event.target.value)
            }
          />
          <Button type="submit" disabled={s.busy || !password}>
            {s.busy ? "Enabling…" : "Enable password recovery"}
          </Button>
        </form>
      )}
    </section>
  );
}
