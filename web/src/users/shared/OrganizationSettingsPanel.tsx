// Staff and admins: lets organization accounts update their contact profile and change their password.
import { useState } from "react";
import { Check } from "lucide-react";
import { BirthdayField } from "../../components/BirthdayField";
import {
  Button,
  Empty,
  Field,
  Form,
  shakeNamedFields,
} from "../../components/SharedComponents";
import { validEmail, validPassword } from "../../domain/DomainModels";
import { accounts, cloudMode } from "../../services/AccountService";
import { saveOrganizationProfile } from "../../services/OrganizationProfileService";
import { useSession } from "../../session/SessionProvider";
import { PasswordRecoveryCard } from "./PasswordRecoveryCard";

export function OrganizationSettingsPanel() {
  const session = useSession();
  const profile = session.profile;
  const [notice, setNotice] = useState("");
  const [passwordKey, setPasswordKey] = useState(0);

  if (!profile) {
    return (
      <Empty title="Account details unavailable">
        Sign in to manage your organization profile and password.
      </Empty>
    );
  }

  return (
    <div className="settings-grid">
      {notice && (
        <div className="status-banner status-success full" role="status">
          <span>{notice}</span>
          <Button variant="quiet" onClick={() => setNotice("")}>
            Dismiss
          </Button>
        </div>
      )}

      <section className="panel">
        <h2>Profile details</h2>
        <Form
          key={`${profile.username}:${profile.fullName}:${profile.dob}:${profile.email}:${profile.phone}`}
          onSubmit={async (data, form) => {
            await session.perform(async () => {
              const fullName = String(data.get("fullName") ?? "").trim();
              const dob = String(data.get("dob") ?? "").trim();
              const email = String(data.get("email") ?? "").trim().toLowerCase();
              const phone = String(data.get("phone") ?? "").trim();
              const invalid: string[] = [];
              if (!fullName) invalid.push("fullName");
              if (!/^\d{4}-\d{2}-\d{2}$/.test(dob)) invalid.push("dob");
              if (!validEmail(email)) invalid.push("email");
              if (invalid.length > 0) {
                shakeNamedFields(form, invalid);
                throw new Error(
                  "Enter your full name, valid birth date, and a real contact email.",
                );
              }
              if (cloudMode) {
                await saveOrganizationProfile({ fullName, dob, email, phone });
                await session.refresh();
              } else {
                await session.saveProfile({
                  ...profile,
                  fullName,
                  dob,
                  email,
                  phone,
                });
              }
              session.clearMessage();
              setNotice("Profile details updated.");
            });
          }}
        >
          <Field
            label="Full name"
            name="fullName"
            defaultValue={profile.fullName}
            required
            maxLength={120}
          />
          <BirthdayField defaultValue={profile.dob} required />
          <Field
            label="Contact email"
            name="email"
            type="email"
            defaultValue={profile.email}
            required
            maxLength={254}
          />
          <Field
            label="Phone (optional)"
            name="phone"
            defaultValue={profile.phone}
            maxLength={40}
          />
          <Button type="submit" disabled={session.busy}>
            <Check size={16} aria-hidden="true" /> Save profile
          </Button>
        </Form>
      </section>

      <section className="panel">
        <h2>Change password</h2>
        <Form
          key={passwordKey}
          onSubmit={async (data, form) => {
            await session.perform(async () => {
              const current = String(data.get("current") ?? "");
              const next = String(data.get("next") ?? "");
              const confirmation = String(data.get("confirmation") ?? "");
              if (
                !validPassword(next) ||
                next.toLowerCase().includes(profile.username.toLowerCase()) ||
                next !== confirmation
              ) {
                shakeNamedFields(form, ["next", "confirmation"]);
                throw new Error(
                  "Use matching 8–24 character passwords with a letter, number, and special character (without your username).",
                );
              }
              try {
                await accounts.changePassword(current, next);
              } catch (failure) {
                shakeNamedFields(form, ["current"]);
                throw failure;
              }
              setPasswordKey((value) => value + 1);
              session.clearMessage();
              setNotice("Password updated.");
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
          <Button type="submit" disabled={session.busy}>
            Change password
          </Button>
        </Form>
      </section>

      <PasswordRecoveryCard onNotice={setNotice} />
    </div>
  );
}
