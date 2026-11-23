// All safe-app roles: shows profile details for signed-in users.
import { useEffect, useState } from "react";
import { Check } from "lucide-react";
import { BirthdayField } from "../../../components/BirthdayField";
import { validEmail } from "../../../domain/DomainModels";
import { useSession } from "../../../session/SessionProvider";
import { Button, Field, Form } from "../../../components/SharedComponents";
export default function Profile() {
  const s = useSession();
  const p = s.profile!;
  const [email, setEmail] = useState(p.email);
  const [emailConsent, setEmailConsent] = useState(Boolean(p.emailCommunicationConsent));
  useEffect(() => {
    setEmail(p.email);
    setEmailConsent(Boolean(p.emailCommunicationConsent));
  }, [p.email, p.emailCommunicationConsent]);
  return (
    <Form
      className="panel profile-form"
      onSubmit={async (data) => {
        await s.perform(async () => {
          const next = { ...p };
          for (const key of [
            "fullName",
            "dob",
            "affectedNames",
            "abuserName",
            "situation",
            "phone",
          ] as const)
            next[key] = String(data.get(key) ?? p[key]);
          const cleanEmail = email.trim().toLowerCase();
          if (cleanEmail && !validEmail(cleanEmail)) {
            throw new Error("Enter a valid email address.");
          }
          next.email = cleanEmail;
          next.emailCommunicationConsent = Boolean(cleanEmail && emailConsent);
          for (const key of [
            "line1",
            "line2",
            "city",
            "province",
            "postalCode",
            "country",
          ] as const)
            next.address = {
              ...next.address,
              [key]: String(data.get(key) ?? p.address[key]),
            };
          next.region = next.address.city;
          if (
            !next.fullName.trim() ||
            !next.address.city.trim() ||
            !next.dob
          )
            throw new Error("Add a name, valid birth date, and city.");
          await s.saveProfile(next);
        });
      }}
    >
      <h2>Personal details</h2>
      <div className="form-grid">
        <Field
          label="Full name"
          name="fullName"
          defaultValue={p.fullName}
          required
          maxLength={120}
        />
        <BirthdayField defaultValue={p.dob} required />
        <Field
          label="People affected"
          name="affectedNames"
          defaultValue={p.affectedNames}
          maxLength={500}
        />
        <Field
          label="Person causing harm"
          name="abuserName"
          defaultValue={p.abuserName}
          maxLength={120}
        />
        <Field
          label="Email (optional)"
          name="email"
          type="email"
          value={email}
          onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
            const value = e.target.value;
            setEmail(value);
            if (!value.trim()) setEmailConsent(false);
          }}
          maxLength={254}
        />
        <Field
          label="Phone (optional)"
          name="phone"
          defaultValue={p.phone}
          maxLength={40}
        />
        {email.trim().length > 0 && (
          <label className="checkbox-field full">
            <input
              type="checkbox"
              name="emailCommunicationConsent"
              checked={emailConsent}
              onChange={(e) => setEmailConsent(e.target.checked)}
            />
            <span>I agree to receive email notifications and communications from Olive Branch.</span>
          </label>
        )}
      </div>
      <label className="field">
        <span>Your situation (optional)</span>
        <textarea
          name="situation"
          defaultValue={p.situation}
          maxLength={2000}
        />
      </label>
      <h3>Your location</h3>
      <div className="form-grid">
        {(
          [
            ["line1", "Street"],
            ["line2", "Apartment / unit"],
            ["city", "City"],
            ["province", "Province / state"],
            ["postalCode", "Postal code"],
            ["country", "Country"],
          ] as const
        ).map(([key, label]) => (
          <Field
            key={key}
            label={label}
            name={key}
            defaultValue={p.address[key]}
            required={key === "city"}
            maxLength={160}
          />
        ))}
      </div>
      <Button type="submit" disabled={s.busy}>
        Save profile <Check size={17} />
      </Button>
    </Form>
  );
}

