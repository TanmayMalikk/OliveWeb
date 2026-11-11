// All safe-app roles: handles web account sign-in and registration.
import { useState } from "react";
import { siteHref } from "../../../sites/SiteRouting";
import { ArrowLeft, ArrowRight, Building2, Leaf, UserRound } from "lucide-react";
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
  emptyProfile,
  validUsername,
  validPassword,
  validPin,
  type Profile,
  type Role,
} from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { accounts, triggerHash } from "../../../services/AccountService";
// Shows the sign-in or registration form for the selected account role.
export function AuthPage({
  organizationOnly = false,
}: {
  organizationOnly?: boolean;
}) {
  const session = useSession();
  const create = !organizationOnly && session.path === "/account/create";
  const [role, setRole] = useState<Role>(organizationOnly ? "STAFF" : "USER");
  const form = (
    <>
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
          {create ? "Already have an account?" : "Need an account?"}{" "}
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
          {create && <a className="text-link" href={siteHref("/get")}>
            Download & installation <ArrowRight size={15} aria-hidden="true" />
          </a>}
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
      {!session.cloudMode && (
        <div className="storage-note">
          <Leaf size={18} aria-hidden="true" />
          <span>Local preview. Accounts are stored in this browser.</span>
        </div>
      )}
    </>
  );

  if (!create) {
    const Icon = organizationOnly ? Building2 : UserRound;
    return (
      <div className="access-page">
        <header className="access-header">
          <Logo small />
          {!organizationOnly && (
            <a className="text-link" href={siteHref("/get")}>Get the app <ArrowRight size={16} aria-hidden="true" /></a>
          )}
        </header>
        <main className="access-sign-in" id="main">
          <section className="access-context" aria-labelledby="sign-in-context">
            <span className="access-mark"><Icon size={25} aria-hidden="true" /></span>
            <p className="access-context-title" id="sign-in-context">{organizationOnly ? "Organization portal" : "Personal and safe contact accounts"}</p>
            <p>{organizationOnly
              ? "Access the staff or administrator workspace with your organization account."
              : "Choose the account type you registered with to continue."}</p>
            {!organizationOnly && (
              <button className="text-link" type="button" onClick={() => session.go("/")}>
                <ArrowLeft size={16} aria-hidden="true" /> Back to Little Grove
              </button>
            )}
          </section>
          <section className="access-card" aria-labelledby="sign-in-heading">
            <h1 id="sign-in-heading">{organizationOnly ? "Organization sign-in" : "Sign in to Olive Branch"}</h1>
            {form}
          </section>
        </main>
      </div>
    );
  }

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
          <span className="eyebrow">A SIMPLE START</span>
          <h2>Create your account.</h2>
          <p>Your details first. Your everyday app next.</p>
          {form}
        </div>
      </main>
    </div>
  );
}
// Collects the profile details required after a new account signs in.
export function Setup() {
  const session = useSession();
  const identity = accounts.identity!;
  const existing = session.onboardingProfile;
  const personal = identity.role === "USER";
  const [step, setStep] = useState(
    personal && existing?.fullName && existing.dob && existing.address.city ? 2 : 1,
  );
  const [profile, setProfile] = useState<Profile>(
    existing ?? emptyProfile(identity.username, identity.role),
  );
  const [trigger, setTrigger] = useState("");
  const steps = personal
    ? ["About you", "Private entry"]
    : ["Your profile", "Ready"];
  // Moves the setup form to the selected step and places focus on its heading.
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
        <StepProgress step={step - 1} steps={steps} />
        <div className="setup-heading">
          <span className="eyebrow">
            {step === 1 ? "ABOUT YOU" : "PRIVATE ENTRY"}
          </span>
          <h1>
            {step === 1 ? "Set up your profile" : "Set up private entry"}
          </h1>
          <p>
            {step === 1
              ? "You can update these details in your profile at any time."
              : "Use your personal link or the olive in Little Grove’s Account tab to reach your browser PIN."}
          </p>
        </div>
        {step === 1 ? (
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
              <span />
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
