// All safe-app roles: handles web account sign-in and registration.
import { useState } from "react";
import { siteHref } from "../../../sites/SiteRouting";
import { ArrowLeft, ArrowRight, Check, Leaf } from "lucide-react";
import {
  Branch,
  Button,
  Field,
  Form,
  Logo,
  Forward,
  StepProgress,
} from "../../../components/SharedComponents";
import {
  covers,
  emptyProfile,
  validUsername,
  validPassword,
  validPin,
  type Profile,
  type Role,
} from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { accounts, triggerHash } from "../../../services/AccountService";
import { CoverPreview } from "../../../public/GameLandingPage";
export function AuthPage({
  organizationOnly = false,
}: {
  organizationOnly?: boolean;
}) {
  const session = useSession();
  const create = !organizationOnly && session.path === "/account/create";
  const [role, setRole] = useState<Role>(organizationOnly ? "STAFF" : "USER");
  return (
    <div className="account-layout">
      <aside className="account-aside">
        <Logo />
        <div>
          <span className="eyebrow">YOUR OLIVE BRANCH</span>
          <h1>
            A familiar place.
            <br />
            <em>A fresh start.</em>
          </h1>
          <p>
            Your everyday app is just the beginning. Let’s make this space your
            own.
          </p>
        </div>
        <Branch />
        <span className="aside-bottom">A little more room to be you.</span>
      </aside>
      <main className="account-main" id="main">
        {!organizationOnly && (
          <button className="text-link" onClick={() => session.go("/")}>
            <ArrowLeft size={17} /> Back to home
          </button>
        )}
        <div className="account-form">
          <span className="eyebrow">
            {create ? "A SIMPLE START" : "MAKE YOURSELF AT HOME"}
          </span>
          <h2>{create ? "Create your account." : "Welcome back."}</h2>
          <p>
            {create
              ? "Your details first. Your everyday app next."
              : "Sign in and we’ll take you to your linked app."}
          </p>
          <Form
            onSubmit={async (data) => {
              const username = String(data.get("username"));
              const password = String(data.get("password"));
              await session.perform(async () => {
                if (create && !validUsername(username))
                  throw new Error(
                    "Use 8–24 lowercase letters, numbers, or underscores.",
                  );
                if (create && !validPassword(password))
                  throw new Error(
                    "Use 8–24 characters with a letter, number, and special character.",
                  );
                if (create && password !== data.get("confirmation"))
                  throw new Error("Passwords do not match.");
                await session.authenticate(username, password, role, create);
              });
            }}
          >
            <label className="field">
              <span>Account type</span>
              <select
                value={role}
                onChange={(e) => setRole(e.target.value as Role)}
              >
                {organizationOnly ? (
                  <>
                    <option value="STAFF">Organization staff</option>
                    <option value="ADMIN">Organization administrator</option>
                  </>
                ) : (
                  <>
                    <option value="USER">Personal account</option>
                    <option value="SAFE_CONTACT">Safe contact</option>
                  </>
                )}
              </select>
            </label>
            <Field
              label={
                role === "STAFF" || role === "ADMIN"
                  ? "Username or organization email"
                  : "Username"
              }
              name="username"
              autoComplete="username"
              required
              minLength={create ? 8 : 4}
              maxLength={254}
              hint={
                create ? "8–24 letters, numbers, or underscores." : undefined
              }
            />
            <Field
              label="Password"
              name="password"
              type="password"
              autoComplete={create ? "new-password" : "current-password"}
              required
              minLength={create ? 8 : 1}
              maxLength={create ? 24 : 128}
              hint={
                create
                  ? "8–24 characters, including a letter, number, and special character."
                  : undefined
              }
            />
            {create && (
              <Field
                label="Confirm password"
                name="confirmation"
                type="password"
                autoComplete="new-password"
                required
                maxLength={24}
              />
            )}
            {session.message && (
              <p className="form-error" role="alert">
                {session.message}
              </p>
            )}
            <Button type="submit" disabled={session.busy}>
              <Forward>
                {session.busy
                  ? "Please wait…"
                  : create
                    ? "Create account"
                    : "Sign in"}
              </Forward>
            </Button>
          </Form>
          {!organizationOnly && (
            <p className="auth-switch">
              {create ? "Already have an account?" : "New around here?"}{" "}
              <button
                className="text-link"
                onClick={() => {
                  session.clearMessage();
                  session.go(create ? "/account/sign-in" : "/account/create");
                }}
              >
                {create ? "Sign in" : "Create an account"}{" "}
                <ArrowRight size={15} />
              </button>
            </p>
          )}
          {!organizationOnly && (
            <div className="account-utility-links">
              <a className="text-link" href={siteHref("/download")}>
                Download & installation <ArrowRight size={15} />
              </a>
              {!create && (
                <button
                  className="text-link"
                  onClick={() => {
                    session.clearMessage();
                    session.go("/account/link");
                  }}
                >
                  Replace a lost personal link <ArrowRight size={15} />
                </button>
              )}
            </div>
          )}
          <div className="storage-note">
            <Leaf size={18} />
            <span>
              {session.cloudMode
                ? "Connected to your shared Firebase account."
                : "Local preview: accounts are encrypted in this browser. They do not sync with the Android app."}
            </span>
          </div>
        </div>
      </main>
    </div>
  );
}
export function Setup() {
  const session = useSession();
  const identity = accounts.identity!;
  const existing = session.onboardingProfile;
  const personal = identity.role === "USER";
  const [step, setStep] = useState(
    personal
      ? existing && existing.fullName && existing.dob && existing.address.city
        ? 2
        : 0
      : 1,
  );
  const [profile, setProfile] = useState<Profile>(
    existing ?? emptyProfile(identity.username, identity.role),
  );
  const [trigger, setTrigger] = useState("");
  const steps = personal
    ? ["Your cover", "About you", "Private entry"]
    : ["Your profile", "Ready"];
  function update(key: keyof Profile, value: string) {
    setProfile((p) => ({ ...p, [key]: value }));
  }
  return (
    <div className="setup-page page-width">
      <header className="site-header">
        <Logo />
        <span className="eyebrow">MAKE IT YOURS</span>
        <button
          className="text-link"
          onClick={() => void session.perform(session.signOut)}
        >
          Sign out
        </button>
      </header>
      <main id="main">
        <StepProgress step={personal ? step : 0} steps={steps} />
        <div className="setup-heading">
          <span className="eyebrow">
            {step === 0
              ? "YOUR EVERYDAY, YOUR WAY"
              : step === 1
                ? "A LITTLE ABOUT YOU"
                : "ONE MORE THING"}
          </span>
          <h1>
            {step === 0
              ? "Choose your familiar."
              : step === 1
                ? "Make yourself at home."
                : "Your personal doorway."}
          </h1>
          <p>
            {step === 0
              ? "This is where you’ll land when you sign in. Your private space stays underneath."
              : step === 1
                ? "You can update these details in your profile at any time."
                : "Your personal link opens the entry screen. Your PIN unlocks this browser. The trigger preference is shared with your Android app."}
          </p>
        </div>
        {step === 0 ? (
          <>
            <div className="setup-covers">
              {covers.map((c) => (
                <button
                  key={c.id}
                  className={`setup-cover ${profile.coverType === c.id ? "selected" : ""}`}
                  aria-pressed={profile.coverType === c.id}
                  onClick={() => setProfile({ ...profile, coverType: c.id })}
                >
                  <CoverPreview cover={c.id} />
                  <span>
                    {c.name}
                    {profile.coverType === c.id && <Check size={18} />}
                  </span>
                </button>
              ))}
            </div>
            <div className="setup-controls">
              <span>You can change your cover later.</span>
              <Button onClick={() => setStep(1)}>
                <Forward>Continue</Forward>
              </Button>
            </div>
          </>
        ) : step === 1 ? (
          <Form
            className="setup-form"
            onSubmit={async () => {
              await session.perform(async () => {
                if (
                  !profile.fullName.trim() ||
                  !profile.dob ||
                  new Date(profile.dob) > new Date() ||
                  !profile.address.city.trim()
                )
                  throw new Error(
                    "Add your name, a valid birth date, and city.",
                  );
                if (personal) setStep(2);
                else await session.completeSetup(profile, "");
              });
            }}
          >
            <div className="form-grid">
              <Field
                label="Full name"
                value={profile.fullName}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  update("fullName", e.target.value)
                }
                autoComplete="name"
                required
                maxLength={120}
              />
              <Field
                label="Date of birth"
                type="date"
                value={profile.dob}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  update("dob", e.target.value)
                }
                max={new Date().toISOString().slice(0, 10)}
                required
              />
              <label className="field">
                <span>Language</span>
                <select
                  value={profile.language}
                  onChange={(e) => update("language", e.target.value)}
                >
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
              </label>
              <Field
                label="City"
                value={profile.address.city}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setProfile({
                    ...profile,
                    region: e.target.value,
                    address: { ...profile.address, city: e.target.value },
                  })
                }
                required
                maxLength={160}
              />
              {personal && (
                <>
                  <Field
                    label="People affected (optional)"
                    value={profile.affectedNames}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      update("affectedNames", e.target.value)
                    }
                    maxLength={500}
                  />
                  <Field
                    label="Person causing harm (optional)"
                    value={profile.abuserName}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                      update("abuserName", e.target.value)
                    }
                    maxLength={120}
                  />
                  <label className="field full">
                    <span>Anything you’d like to note (optional)</span>
                    <textarea
                      value={profile.situation}
                      onChange={(e) => update("situation", e.target.value)}
                      maxLength={2000}
                    />
                  </label>
                </>
              )}
            </div>
            {session.message && (
              <p role="alert" className="form-error">
                {session.message}
              </p>
            )}
            <div className="setup-controls">
              {personal ? (
                <Button variant="quiet" onClick={() => setStep(0)}>
                  <ArrowLeft size={17} /> Back
                </Button>
              ) : (
                <span />
              )}
              <Button type="submit" disabled={session.busy}>
                <Forward>{personal ? "Continue" : "Finish setup"}</Forward>
              </Button>
            </div>
          </Form>
        ) : (
          <Form
            className="setup-form narrow"
            onSubmit={async (data) => {
              await session.perform(async () => {
                const pin = String(data.get("pin"));
                if (!validPin(pin) || pin !== data.get("confirmation"))
                  throw new Error("Enter matching PINs with 4–6 digits.");
                if (!profile.triggerHash && trigger.trim().length < 3)
                  throw new Error("Choose a trigger with 3–64 characters.");
                const p = {
                  ...profile,
                  triggerHash: trigger
                    ? await triggerHash(trigger)
                    : profile.triggerHash,
                };
                await session.completeSetup(p, pin);
              });
            }}
          >
            {!profile.triggerHash && (
              <Field
                label="Trigger word or phrase"
                value={trigger}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  setTrigger(e.target.value)
                }
                maxLength={64}
                minLength={3}
                required
                autoComplete="off"
              />
            )}
            <Field
              label="Browser PIN"
              name="pin"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              minLength={4}
              maxLength={6}
              required
              autoComplete="new-password"
            />
            <Field
              label="Confirm PIN"
              name="confirmation"
              type="password"
              inputMode="numeric"
              pattern="[0-9]{4,6}"
              minLength={4}
              maxLength={6}
              required
              autoComplete="new-password"
            />
            <p className="help-note">
              Your PIN belongs to this browser. Keep your account password for
              signing in on another device. Use Quick Exit or Escape to return
              to your cover.
            </p>
            {session.message && (
              <p role="alert" className="form-error">
                {session.message}
              </p>
            )}
            <div className="setup-controls">
              <Button variant="quiet" onClick={() => setStep(1)}>
                <ArrowLeft size={17} /> Back
              </Button>
              <Button type="submit" disabled={session.busy}>
                <Forward>
                  {session.busy ? "Saving…" : "Open my everyday app"}
                </Forward>
              </Button>
            </div>
          </Form>
        )}
      </main>
    </div>
  );
}
