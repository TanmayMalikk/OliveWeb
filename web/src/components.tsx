import {
  useEffect,
  useId,
  useRef,
  type ReactNode,
  type FormEvent,
} from "react";
import {
  ArrowUpRight,
  ArrowRight,
  X,
  Leaf,
  Check,
  Eye,
  EyeOff,
} from "lucide-react";
import { useState } from "react";
export function Branch({ className = "" }: { className?: string }) {
  return (
    <svg
      className={className}
      viewBox="0 0 320 180"
      fill="none"
      aria-hidden="true"
    >
      <path
        d="M20 154C86 134 139 95 280 33"
        stroke="currentColor"
        strokeWidth="2.5"
      />
      <path
        d="M71 131C42 130 38 99 39 89C64 96 79 111 71 131ZM105 114C78 105 82 75 84 64C107 77 119 96 105 114ZM143 92C117 81 124 50 131 40C148 56 157 75 143 92ZM181 72C161 57 171 30 180 23C195 39 194 57 181 72ZM218 54C209 34 229 12 238 10C243 32 233 46 218 54ZM73 131C82 151 110 153 127 143C112 129 90 124 73 131ZM117 107C129 130 157 128 172 117C153 103 133 100 117 107ZM158 87C172 106 198 103 211 90C191 79 172 80 158 87ZM200 64C217 79 243 73 254 59C234 49 214 53 200 64Z"
        fill="currentColor"
        fillOpacity=".7"
      />
      <ellipse
        cx="149"
        cy="114"
        rx="9"
        ry="13"
        transform="rotate(-28 149 114)"
        fill="currentColor"
      />
      <ellipse
        cx="105"
        cy="85"
        rx="8"
        ry="11"
        transform="rotate(20 105 85)"
        fill="currentColor"
      />
    </svg>
  );
}
export function Logo({ small = false }: { small?: boolean }) {
  return (
    <a
      href="/"
      className={`brand ${small ? "small" : ""}`}
      aria-label="Olive Branch home"
    >
      <Leaf aria-hidden="true" strokeWidth={1.2} />
      <span>
        olive<span className="brand-second">branch</span>
        <i>®</i>
      </span>
    </a>
  );
}
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
export function Forward({ children }: { children: ReactNode }) {
  return (
    <>
      {children}
      <ArrowRight size={18} aria-hidden="true" />
    </>
  );
}
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
export function External({
  href,
  children,
}: {
  href: string;
  children: ReactNode;
}) {
  return (
    <a
      className="text-link"
      href={href}
      target="_blank"
      rel="noopener noreferrer"
    >
      {children}
      <ArrowUpRight size={16} aria-hidden="true" />
    </a>
  );
}
