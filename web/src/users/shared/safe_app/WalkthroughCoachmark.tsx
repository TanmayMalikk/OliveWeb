// All safe-app roles: floating OliveGreen coachmark popup with directional arrows over live web pages.
import { ArrowDownRight, ArrowRight, ArrowUp } from "lucide-react";
import type { Role } from "../../../domain/DomainModels";
import {
  nextWalkthroughTourStepId,
  walkthroughTourStepsForRole,
} from "./WalkthroughCatalog";

export function WalkthroughCoachmark({
  role,
  activeStepId,
  onAdvance,
  onOpenContents,
  onSkip,
}: {
  role: Role | undefined;
  activeStepId: string | null;
  onAdvance: (nextStepId: string | null) => void;
  onOpenContents: () => void;
  onSkip: () => void;
}) {
  if (!activeStepId) return null;
  const steps = walkthroughTourStepsForRole(role);
  const activeIndex = steps.findIndex((step) => step.id === activeStepId);
  if (activeIndex < 0) return null;
  const step = steps[activeIndex];
  const prevId = nextWalkthroughTourStepId(steps, step.id, -1);
  const nextId = nextWalkthroughTourStepId(steps, step.id, 1);

  const placementStyle: React.CSSProperties =
    step.anchor === "bottom-right"
      ? {
          position: "fixed",
          right: "1rem",
          bottom: "5.25rem",
        }
      : {
          position: "fixed",
          left: "50%",
          transform: "translateX(-50%)",
          bottom: "5.25rem",
        };

  return (
    <aside
      role="dialog"
      aria-modal="false"
      aria-label={step.title}
      data-testid="walkthrough-active-feature"
      style={{
        ...placementStyle,
        zIndex: 45,
        width: "min(368px, calc(100vw - 2rem))",
        background: "var(--olive, #284b3c)",
        color: "#ffffff",
        border: "2px solid rgba(255, 255, 255, 0.9)",
        borderRadius: "16px",
        boxShadow: "0 14px 34px rgba(0, 0, 0, 0.28)",
        padding: "1rem 1.2rem",
        display: "grid",
        gap: "0.65rem",
      }}
    >
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
        <span style={{ fontSize: "0.85rem", fontWeight: 600, opacity: 0.92 }}>
          Step {activeIndex + 1} of {steps.length}
        </span>
        <button
          type="button"
          data-testid="walkthrough-tour-skip"
          onClick={onSkip}
          style={{
            background: "transparent",
            border: "none",
            color: "#ffffff",
            fontWeight: 600,
            cursor: "pointer",
            padding: "0.25rem 0.5rem",
          }}
        >
          Skip
        </button>
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: "0.65rem" }}>
        <strong style={{ flex: 1, fontSize: "1.15rem", lineHeight: 1.25 }}>
          {step.title}
        </strong>
        {step.arrow === "down-right" ? (
          <ArrowDownRight size={28} aria-hidden="true" data-testid="walkthrough-tour-arrow" />
        ) : (
          <ArrowUp size={28} aria-hidden="true" data-testid="walkthrough-tour-arrow" />
        )}
      </div>

      <p style={{ margin: 0, fontSize: "0.94rem", lineHeight: 1.4, color: "#ffffff" }}>
        {step.body}
      </p>

      <div style={{ display: "flex", justifyContent: "flex-end", gap: "0.5rem", flexWrap: "wrap" }}>
        <button
          type="button"
          data-testid="walkthrough-tour-toc"
          onClick={onOpenContents}
          style={{
            background: "transparent",
            border: "none",
            color: "#ffffff",
            fontWeight: 600,
            cursor: "pointer",
            padding: "0.45rem 0.7rem",
          }}
        >
          Contents
        </button>
        {prevId && (
          <button
            type="button"
            data-testid="walkthrough-tour-prev"
            onClick={() => onAdvance(prevId)}
            style={{
              background: "transparent",
              border: "none",
              color: "#ffffff",
              fontWeight: 600,
              cursor: "pointer",
              padding: "0.45rem 0.7rem",
            }}
          >
            Back
          </button>
        )}
        <button
          type="button"
          data-testid="walkthrough-tour-next"
          onClick={() => onAdvance(nextId)}
          style={{
            background: "#ffffff",
            color: "var(--olive, #284b3c)",
            border: "none",
            borderRadius: "999px",
            fontWeight: 700,
            cursor: "pointer",
            padding: "0.5rem 1rem",
            display: "inline-flex",
            alignItems: "center",
            gap: "0.35rem",
          }}
        >
          {nextId ? (
            <>
              Next <ArrowRight size={16} aria-hidden="true" />
            </>
          ) : (
            "Done"
          )}
        </button>
      </div>
    </aside>
  );
}
