// All safe-app roles: non-overlapping OliveGreen coachmark popup with red highlight and step action context.
import { createContext, useContext, useEffect, useLayoutEffect, useRef, useState } from "react";
import { ArrowRight } from "lucide-react";
import type { Role } from "../../../domain/DomainModels";
import {
  computeWebCoachmarkPlacement,
  nextWalkthroughTourStepId,
  walkthroughTourStepsForRole,
  type WebCoachmarkRect,
} from "./WalkthroughCatalog";

export interface WalkthroughActionState {
  stepId: string | null;
  action: string | null;
}

export const WalkthroughActionContext = createContext<WalkthroughActionState>({
  stepId: null,
  action: null,
});

export function useWalkthroughAction(): WalkthroughActionState {
  return useContext(WalkthroughActionContext);
}

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
  const cardRef = useRef<HTMLElement>(null);
  const [targetRect, setTargetRect] = useState<WebCoachmarkRect | null>(null);
  const [cardSize, setCardSize] = useState<{ width: number; height: number }>({
    width: 368,
    height: 210,
  });

  const steps = walkthroughTourStepsForRole(role);
  const activeIndex = activeStepId
    ? steps.findIndex((step) => step.id === activeStepId)
    : -1;
  const step = activeIndex >= 0 ? steps[activeIndex] : undefined;

  useLayoutEffect(() => {
    if (!step) {
      setTargetRect(null);
      return;
    }
    const measure = () => {
      const targetEl = step.targetSelector
        ? document.querySelector(step.targetSelector)
        : null;
      if (targetEl) {
        const r = targetEl.getBoundingClientRect();
        const top = Math.max(0, r.top);
        const bottom = Math.min(window.innerHeight, r.bottom);
        if (r.width > 0 && bottom > top) {
          setTargetRect({
            left: r.left,
            top,
            right: r.right,
            bottom,
            width: r.width,
            height: bottom - top,
          });
        } else {
          setTargetRect(null);
        }
      } else {
        setTargetRect(null);
      }
      if (cardRef.current) {
        const c = cardRef.current.getBoundingClientRect();
        if (c.width > 0 && c.height > 0) {
          setCardSize({ width: c.width, height: c.height });
        }
      }
    };
    measure();
    const raf = window.requestAnimationFrame(measure);
    const timer1 = window.setTimeout(measure, 120);
    const timer2 = window.setTimeout(measure, 280);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => {
      window.cancelAnimationFrame(raf);
      window.clearTimeout(timer1);
      window.clearTimeout(timer2);
      window.removeEventListener("resize", measure);
      window.removeEventListener("scroll", measure, true);
    };
  }, [step?.id, step?.targetSelector]);

  useEffect(() => {
    if (!step?.targetSelector) return;
    const targetEl = document.querySelector(step.targetSelector);
    if (!targetEl) return;
    const r = targetEl.getBoundingClientRect();
    if (r.top < 0 || r.bottom > window.innerHeight) {
      targetEl.scrollIntoView?.({ block: "center", behavior: "instant" as ScrollBehavior });
    }
  }, [step?.id]);

  if (!step) return null;
  const prevId = nextWalkthroughTourStepId(steps, step.id, -1);
  const nextId = nextWalkthroughTourStepId(steps, step.id, 1);

  const placementStyle: React.CSSProperties = targetRect
    ? (() => {
        const pos = computeWebCoachmarkPlacement({
          targetRect,
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
          cardWidth: cardSize.width,
          cardHeight: cardSize.height,
          safeBottom:
            step.targetSelector === ".floating-quick-exit" || step.opensPopup
              ? window.innerHeight - 16
              : window.innerHeight - 84,
        });
        return {
          position: "fixed",
          left: `${Math.round(pos.left)}px`,
          top: `${Math.round(pos.top)}px`,
        };
      })()
    : {
        position: "fixed",
        left: "50%",
        transform: "translateX(-50%)",
        bottom: "5.25rem",
      };

  return (
    <>
      {targetRect && (
        <div
          aria-hidden="true"
          data-testid="walkthrough-target-highlight"
          style={{
            position: "fixed",
            left: `${Math.max(2, targetRect.left - 4)}px`,
            top: `${Math.max(2, targetRect.top - 4)}px`,
            width: `${targetRect.width + 8}px`,
            height: `${targetRect.height + 8}px`,
            border: "3px solid #dc2626",
            background: "rgba(220, 38, 38, 0.14)",
            borderRadius: "12px",
            pointerEvents: "none",
            zIndex: 1199,
          }}
        />
      )}
      <aside
        ref={cardRef}
        role="dialog"
        aria-modal="false"
        aria-label={step.title}
        data-testid="walkthrough-active-feature"
        style={{
          ...placementStyle,
          zIndex: 1200,
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

        <strong style={{ fontSize: "1.15rem", lineHeight: 1.25 }}>
          {step.title}
        </strong>

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
    </>
  );
}
