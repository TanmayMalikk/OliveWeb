// Food cover users: shows the food checkout screen.
import { useState } from "react";
import { Button, Field, Form, Modal } from "../../components/SharedComponents";

// Web food checkout screen cover: returns the money.
const money = (cents: number) =>
  new Intl.NumberFormat("en-CA", { style: "currency", currency: "CAD" }).format(
    cents / 100,
  );
export default function Checkout({
  total,
  close,
  onDeclined,
}: {
  total: number;
  close: () => void;
  onDeclined: () => Promise<void>;
}) {
  const [useShipping, setUseShipping] = useState(true);
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  return (
    <Modal title="A little good, delivered." onClose={close}>
      <p className="help-note">
        Demo checkout. No order is placed and no payment is charged. Use test
        details only.
      </p>
      <Form
        onSubmit={async () => {
          setSubmitting(true);
          // If checkout fails, catch the error so the app can keep running and explain the problem.
          try {
            await onDeclined();
            setMessage(
              "Payment declined. No payment was taken. Your payment details have been cleared.",
            );
            document.querySelector<HTMLFormElement>("dialog form")?.reset();
          } catch {
            setMessage("Payment declined. No payment was taken.");
          } finally {
            setSubmitting(false);
          }
        }}
      >
        <div className="form-grid">
          <Field
            label="Delivery street"
            name="street"
            autoComplete="shipping street-address"
            required
            maxLength={160}
          />
          <Field label="City" name="city" required maxLength={160} />
          <Field
            label="Province / state"
            name="province"
            required
            maxLength={160}
          />
          <Field label="Postal code" name="postal" required maxLength={20} />
        </div>
        <h3>Payment details</h3>
        <Field label="Name on card" name="holder" required autoComplete="off" />
        <Field
          label="Card number"
          name="card"
          inputMode="numeric"
          pattern="[0-9 ]{12,23}"
          required
          autoComplete="off"
        />
        <div className="form-grid">
          <Field
            label="Expiry MM/YY"
            name="expiry"
            pattern="(0[1-9]|1[0-2])/[0-9]{2}"
            required
            autoComplete="off"
          />
          <Field
            label="Security code"
            name="cvc"
            type="password"
            inputMode="numeric"
            pattern="[0-9]{3,4}"
            maxLength={4}
            required
            autoComplete="off"
          />
        </div>
        <label className="checkbox-row">
          <input
            type="checkbox"
            checked={useShipping}
            onChange={(e) => setUseShipping(e.target.checked)}
          />
          Use shipping address for billing
        </label>
        {!useShipping && (
          <Field
            label="Billing address"
            name="billing"
            required
            maxLength={500}
          />
        )}
        <p role="status">{message}</p>
        <Button type="submit" disabled={submitting}>
          Try demo payment · {money(total)}
        </Button>
      </Form>
    </Modal>
  );
}

