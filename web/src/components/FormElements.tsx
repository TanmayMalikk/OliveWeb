// All app roles: draws shared web buttons, fields, and dialogs.
import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Check, Eye, EyeOff, Leaf, X } from "lucide-react";

// Web form elements: Shows the button view.
export function Button({
  children,
  onClick,
  type = "button",
  variant = "",
  disabled = false,
  ...props
}: {
  children: ReactNode;
  onClick?: () => void;
  type?: "button" | "submit";
  variant?: string;
  disabled?: boolean;
  [key: string]: unknown;
}) {
  return (
    <button
      {...props}
      type={type}
      className={`button ${variant}`}
      onClick={onClick}
      disabled={disabled}
    >
      {children}
    </button>
  );
}
// Web form elements: Shows the field view.
export function Field({
  label,
  type = "text",
  hint,
  error,
  ...props
}: {
  label: string;
  type?: string;
  hint?: string;
  error?: string;
  [key: string]: unknown;
}) {
  const id = useId();
  const [visible, setVisible] = useState(false);
  const secret = type === "password";
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <span className="input-wrap">
        <input
          {...props}
          id={id}
          type={secret && visible ? "text" : type}
          aria-invalid={Boolean(error)}
          aria-describedby={hint || error ? `${id}-hint` : undefined}
        />
        {secret && (
          <button
            type="button"
            className="icon-button"
            aria-label={`${visible ? "Hide" : "Show"} ${label.toLowerCase()}`}
            onClick={() => setVisible(!visible)}
          >
            {visible ? <EyeOff size={18} /> : <Eye size={18} />}
          </button>
        )}
      </span>
      {(hint || error) && (
        <small id={`${id}-hint`} className={error ? "error" : ""}>
          {error || hint}
        </small>
      )}
    </div>
  );
}
// Web form elements: Shows the modal view.
export function Modal({
  title,
  children,
  onClose,
  wide = false,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  // Update title id when information used by this screen changes.
  useEffect(() => {
    const dialog = ref.current;
    const previous = document.activeElement as HTMLElement | null;
    dialog?.showModal();
    return () => {
      dialog?.close();
      previous?.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={`modal ${wide ? "wide" : ""}`}
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
    >
      <div className="modal-top">
        <h2 id={titleId}>{title}</h2>
        <button
          className="icon-button"
          onClick={onClose}
          aria-label="Close dialog"
        >
          <X />
        </button>
      </div>
      {children}
    </dialog>
  );
}
// Web form elements: Shows the empty view.
export function Empty({
  title,
  children,
  action,
}: {
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-icon">
        <Leaf size={28} strokeWidth={1.2} />
      </span>
      <h3>{title}</h3>
      <p>{children}</p>
      {action}
    </div>
  );
}
// Web form elements: Shows the form view.
export function Form({
  onSubmit,
  children,
  className = "",
}: {
  onSubmit: (data: FormData) => Promise<void> | void;
  children: ReactNode;
  className?: string;
}) {
  return (
    <form
      className={className}
      onSubmit={(e: FormEvent<HTMLFormElement>) => {
        e.preventDefault();
        void onSubmit(new FormData(e.currentTarget));
      }}
    >
      {children}
    </form>
  );
}
// Shows the step progress view for web form elements.
export function StepProgress({
  step,
  steps,
}: {
  step: number;
  steps: string[];
}) {
  return (
    <ol
      className="step-progress"
      aria-label={`Step ${step + 1} of ${steps.length}: ${steps[step]}`}
    >
      {steps.map((label, i) => (
        <li
          key={label}
          className={i <= step ? "active" : ""}
          aria-current={i === step ? "step" : undefined}
        >
          <span>{i < step ? <Check size={14} /> : i + 1}</span>
          <small>{label}</small>
        </li>
      ))}
    </ol>
  );
}
