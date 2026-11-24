// All safe-app roles: handles web account sign-in and registration.
import { useState } from "react";
import { BirthdayField } from "../../../components/BirthdayField";
import { siteHref } from "../../../sites/SiteRouting";
import { ArrowLeft, ArrowRight, Building2, Leaf, UserRound, UserRoundPlus } from "lucide-react";
import {
  Button,
  Field,
  Form,
  Logo,
  Forward,
  StepProgress,
  shakeNamedFields,
} from "../../../components/SharedComponents";
import {
  emptyProfile,
  validEmail,
  validUsername,
  validPassword,
  validPin,
  type Profile,
  type Role,
} from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { accounts, triggerHash } from "../../../services/AccountService";
import { InviteCodeEntry } from "../safe_app/FriendInviteControls";
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
        key={create ? "create-account" : "sign-in"}
        onSubmit={async (data, form) => {
          const username = String(data.get("username"));
          const password = String(data.get("password"));
          await session.perform(async () => {
            if (create && !validUsername(username)) {
              shakeNamedFields(form, ["username"]);
              throw new Error(
                "Use 8–24 lowercase letters, numbers, or underscores.",
              );
            }
            if (create && !validPassword(password)) {
              shakeNamedFields(form, ["password"]);
              throw new Error(
                "Use 8–24 characters with a letter, number, and special character.",
              );
            }
            if (create && password !== data.get("confirmation")) {
              shakeNamedFields(form, ["password", "confirmation"]);
              throw new Error("Passwords do not match.");
            }
            try {
              await session.authenticate(username, password, role, create);
            } catch (failure) {
              const details = failure instanceof Error ? failure.message.toLowerCase() : "";
              if (create && details.includes("username")) {
                shakeNamedFields(form, ["username"]);
              } else if (details.includes("password")) {
                shakeNamedFields(form, ["password"]);
              } else {
                shakeNamedFields(form, ["username", "password"]);
              }
              throw failure;
            }
          });
        }}
      >
        {create ? (
          <fieldset className="signup-account-types">
            <legend>Account type</legend>
            <div className="signup-role-options">
              <label className="signup-role">
                <input type="radio" name="signup-role" checked={role === "USER"}
                  onChange={() => setRole("USER")} />
                <span><strong>Personal account</strong><small>Use your own Safe Space.</small></span>
              </label>
              <label className="signup-role">
                <input type="radio" name="signup-role" checked={role === "SAFE_CONTACT"}
                  onChange={() => setRole("SAFE_CONTACT")} />
                <span><strong>Safe contact</strong><small>Support someone who shares a safety plan with you.</small></span>
              </label>
            </div>
          </fieldset>
        ) : (
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
        )}
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
          maxLength={create ? 24 : 254}
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
      {!organizationOnly && !create && (
        <div className="account-utility-links">
          <button className="text-link" type="button" onClick={() => {
            session.clearMessage();
            session.go("/account/link");
          }}>
            Replace a lost personal link <ArrowRight size={15} aria-hidden="true" />
          </button>
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

  const Icon = create ? UserRoundPlus : organizationOnly ? Building2 : UserRound;
  const contextTitle = create
    ? "What comes next"
    : organizationOnly ? "Organization portal" : "Personal and safe contact accounts";
  const heading = create
    ? "Create your account"
    : organizationOnly ? "Organization sign-in" : "Sign in to Olive Branch";

  return (
    <div className="access-page">
      <header className="access-header">
        <Logo small />
        {!organizationOnly && (
          <a className="text-link" href={siteHref("/get")}>
            Get the app <ArrowRight size={16} aria-hidden="true" />
          </a>
        )}
      </header>
      <main className={create ? "access-sign-in access-sign-up" : "access-sign-in"} id="main">
        <section className="access-context" aria-labelledby="account-context">
          <span className="access-mark"><Icon size={25} aria-hidden="true" /></span>
          <p className="access-context-title" id="account-context">{contextTitle}</p>
          <p>{create
            ? "Set up your profile after signup. Personal accounts also choose a browser PIN and get a private entry link."
            : organizationOnly
              ? "Access the staff or administrator workspace with your organization account."
              : "Choose the account type you registered with to continue."}</p>
          {!organizationOnly && (
            <button className="text-link" type="button" onClick={() => session.go("/")}>
              <ArrowLeft size={16} aria-hidden="true" /> Back to Little Grove
            </button>
          )}
        </section>
        <section className="access-card" aria-labelledby="account-heading">
          <h1 id="account-heading">{heading}</h1>
          {form}
        </section>
      </main>
    </div>
  );
}
// Collects the profile details required after a new account signs in.
function AffectedPeopleFields({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const [people, setPeople] = useState(() => value.split("\n").filter(Boolean).map((line) => {
    const match = /^(.*) \(([0-9]{1,3})\)$/.exec(line);
    return match ? { name: match[1], age: match[2] } : { name: line, age: "" };
  }));
  const rows = people.length ? people : [{ name: "", age: "" }];
  function update(next: typeof rows) {
    const saved = next.filter(({ name }) => name.trim()).map(({ name, age }) =>
      name.trim() + (age ? ` (${age})` : ""),
    ).join("\n");
    if (saved.length <= 500) {
      setPeople(next);
      onChange(saved);
    }
  }
  return <fieldset className="field full">
    <legend>People affected (optional)</legend>
    {rows.map((person, index) => (
      <div className="field" key={index}>
        <Field label={`Person ${index + 1} name`} value={person.name}
          onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
            update(rows.map((row, i) => i === index ? { ...row, name: event.target.value } : row))}
        />
        <Field label={`Person ${index + 1} age (optional)`} type="number" min={0} max={130}
          value={person.age} onChange={(event: React.ChangeEvent<HTMLInputElement>) => {
            const age = event.target.value;
            if (!age || (/^[0-9]{1,3}$/.test(age) && Number(age) <= 130)) {
              update(rows.map((row, i) => i === index ? { ...row, age } : row));
            }
          }}
        />
        {rows.length > 1 && <button type="button" className="button outline"
          onClick={() => update(rows.filter((_, i) => i !== index))}
        >Remove person {index + 1}</button>}
      </div>
    ))}
    <button type="button" className="button outline" disabled={!rows.at(-1)?.name.trim() || value.length >= 500}
      onClick={() => update([...rows, { name: "", age: "" }])}
    >Add another person</button>
  </fieldset>;
}

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
        {step === 1 && ["USER", "SAFE_CONTACT"].includes(identity.role) && <InviteCodeEntry />}
        {step === 1 ? (
          <Form
            className="setup-form"
            onSubmit={async (data, form) => {
              await session.perform(async () => {
                const email = profile.email.trim().toLowerCase();
                if (email && !validEmail(email)) {
                  shakeNamedFields(form, ["email"]);
                  throw new Error("Enter a valid email address.");
                }
                if (email && !profile.emailCommunicationConsent) {
                  shakeNamedFields(form, ["emailCommunicationConsent"]);
                  throw new Error("Please agree to receive email communications, or clear the email field.");
                }
                const next = {
                  ...profile,
                  dob: String(data.get("dob") ?? ""),
                  email,
                  emailCommunicationConsent: Boolean(email && profile.emailCommunicationConsent),
                };
                if (!next.fullName.trim() || !next.dob || !next.address.city.trim()) {
                  const missing = [
                    !next.fullName.trim() ? "fullName" : "",
                    !next.dob ? "dob" : "",
                    !next.address.city.trim() ? "city" : "",
                  ].filter(Boolean);
                  shakeNamedFields(form, missing);
                  throw new Error("Add your name, a valid birth date, and city.");
                }
                if (personal) {
                  setProfile(next);
                  setStep(2);
                } else await session.completeSetup(next, "");
              });
            }}
          >
            <div className="form-grid">
              <Field
                label="Full name"
                name="fullName"
                value={profile.fullName}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) =>
                  update("fullName", e.target.value)
                }
                autoComplete="name"
                required
                maxLength={120}
              />
              <BirthdayField defaultValue={profile.dob} required />
              <Field
                label="City"
                name="city"
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
              <Field
                label="Email (optional)"
                name="email"
                type="email"
                autoComplete="email"
                value={profile.email}
                onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                  const nextEmail = e.target.value;
                  setProfile((p) => ({
                    ...p,
                    email: nextEmail,
                    emailCommunicationConsent: nextEmail.trim() ? Boolean(p.emailCommunicationConsent) : false,
                  }));
                }}
                maxLength={254}
              />
              {profile.email.trim().length > 0 && (
                <label className="checkbox-field full">
                  <input
                    type="checkbox"
                    name="emailCommunicationConsent"
                    checked={Boolean(profile.emailCommunicationConsent)}
                    onChange={(e) =>
                      setProfile((p) => ({ ...p, emailCommunicationConsent: e.target.checked }))
                    }
                  />
                  <span>I agree to receive email notifications and communications from Olive Branch.</span>
                </label>
              )}
              {personal && (
                <>
                  <AffectedPeopleFields value={profile.affectedNames} onChange={(value) => update("affectedNames", value)} />
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
                    <span>If you choose to share, describe what is happening, such as physical, emotional, or financial harm.</span>
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
            onSubmit={async (data, form) => {
              await session.perform(async () => {
                const pin = String(data.get("pin"));
                if (!validPin(pin) || pin !== data.get("confirmation")) {
                  shakeNamedFields(form, ["pin", "confirmation"]);
                  throw new Error("Enter matching PINs with 4–6 digits.");
                }
                if (!profile.triggerHash && trigger.trim().length < 3) {
                  shakeNamedFields(form, ["trigger"]);
                  throw new Error("Choose a trigger with 3–64 characters.");
                }
                const p = {
                  ...profile,
                  triggerHash: trigger
                    ? await triggerHash(trigger)
                    : profile.triggerHash,
                };
                try {
                  await session.completeSetup(p, pin);
                } catch (failure) {
                  shakeNamedFields(form, ["pin", "confirmation"]);
                  throw failure;
                }
              });
            }}
          >
            {!profile.triggerHash && (
              <Field
                label="Trigger word or phrase"
                name="trigger"
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
