import { useState } from "react";
import { ArrowRight, Delete, Leaf, LockKeyhole, LogOut } from "lucide-react";
import { Button } from "../../../components/SharedComponents";
import { validPin } from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";

const keypadDigits = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"];

export default function BrowserPinGate() {
  const session = useSession();
  const [pin, setPin] = useState("");
  const storedLength = session.enrollment?.pinLength;
  const pinLength = storedLength === 4 || storedLength === 5 || storedLength === 6
    ? storedLength
    : undefined;
  const maxLength = pinLength ?? 6;
  const canUnlock = validPin(pin) && (pinLength === undefined || pin.length === pinLength);

  function updatePin(value: string) {
    if (session.message) session.clearMessage();
    setPin(value.replace(/[^0-9]/g, "").slice(0, maxLength));
  }

  return (
    <main id="main" className="access-page pin-page private-shell">
      <header className="access-header">
        <span className="access-wordmark"><Leaf size={21} aria-hidden="true" /> Olive Branch</span>
        {session.enrollment?.role === "USER" && (
          <Button variant="exit floating-quick-exit" onClick={session.quickExit}>
            <LogOut size={17} aria-hidden="true" /> Quick Exit
          </Button>
        )}
      </header>
      <section className="access-card pin-card" aria-labelledby="pin-heading">
        <span className="access-mark"><LockKeyhole size={23} aria-hidden="true" /></span>
        <h1 id="pin-heading">Enter your PIN</h1>
        <p id="pin-hint">Use your {pinLength ?? "4–6"}-digit browser PIN.</p>
        <form
          className="pin-form"
          onSubmit={(event) => {
            event.preventDefault();
            if (!canUnlock || session.busy) return;
            void session.perform(async () => {
              try {
                await session.unlock(pin);
              } finally {
                setPin("");
              }
            });
          }}
        >
          <div className="pin-entry" data-invalid={Boolean(session.message)}>
            <label className="sr-only" htmlFor="browser-pin">Browser PIN</label>
            <input
              id="browser-pin"
              name="pin"
              type="password"
              inputMode="numeric"
              autoComplete="off"
              autoFocus
              maxLength={maxLength}
              readOnly={session.busy}
              aria-invalid={Boolean(session.message)}
              aria-describedby={session.message ? "pin-hint pin-error" : "pin-hint"}
              value={pin}
              onChange={(event) => updatePin(event.target.value)}
            />
            <div className="pin-dots" aria-hidden="true">
              {Array.from({ length: maxLength }, (_, index) => (
                <span key={index} className={index < pin.length ? "pin-dot filled" : "pin-dot"} />
              ))}
            </div>
          </div>
          {session.message && <p id="pin-error" role="alert" className="form-error">{session.message}</p>}
          <Button type="submit" disabled={!canUnlock || session.busy}>
            {session.busy ? "Unlocking…" : "Unlock"} <ArrowRight size={17} aria-hidden="true" />
          </Button>
          <details className="pin-keypad-options">
            <summary>Use on-screen keypad</summary>
            <div className="pin-keypad" role="group" aria-label="PIN keypad">
              {keypadDigits.map((digit) => (
                <button key={digit} type="button" disabled={session.busy} onClick={() => updatePin(pin + digit)}>
                  {digit}
                </button>
              ))}
              <button
                type="button"
                aria-label="Delete digit"
                disabled={session.busy || pin.length === 0}
                onClick={() => updatePin(pin.slice(0, -1))}
              >
                <Delete size={20} aria-hidden="true" />
              </button>
            </div>
          </details>
        </form>
        <button
          className="text-link"
          type="button"
          onClick={() => {
            session.quickExit();
            session.go("/account/link");
          }}
        >
          Get a new PIN or link
        </button>
      </section>
    </main>
  );
}
