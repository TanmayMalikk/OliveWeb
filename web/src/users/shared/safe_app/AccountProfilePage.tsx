// All safe-app roles: shows profile details for signed-in users.
import { Check } from "lucide-react";
import { useSession } from "../../../session/SessionProvider";
import { Button, Field, Form } from "../../../components/SharedComponents";
export default function Profile() {
  const s = useSession();
  const p = s.profile!;
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
            "language",
            "email",
            "phone",
          ] as const)
            next[key] = String(data.get(key) ?? p[key]);
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
            new Date(next.dob) > new Date()
          )
            throw new Error("Add a name, valid birth date, and city.");
          await s.saveProfile(next);
        });
      }}
    >
      <h2>The details that matter to you.</h2>
      <div className="form-grid">
        <Field
          label="Full name"
          name="fullName"
          defaultValue={p.fullName}
          required
          maxLength={120}
        />
        <Field
          label="Date of birth"
          type="date"
          name="dob"
          defaultValue={p.dob}
          required
          max={new Date().toISOString().slice(0, 10)}
        />
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
          defaultValue={p.email}
          maxLength={254}
        />
        <Field
          label="Phone (optional)"
          name="phone"
          defaultValue={p.phone}
          maxLength={40}
        />
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

