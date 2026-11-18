// Cover users: shared Start / Pause / Retry overlay card for Little Grove games.
import { type ReactNode } from "react";
import { Play, RotateCcw, Trophy } from "lucide-react";
import { Button } from "../../components/SharedComponents";

export function GameOverlay({
  tone = "ready",
  title,
  subtitle,
  score,
  scoreLabel = "Score",
  best,
  bestLabel = "Best",
  isNewBest = false,
  actionLabel,
  actionAriaLabel,
  onAction,
  secondaryLabel,
  onSecondary,
  controlsHint,
  icon,
  asButtonSurface = false,
  children,
}: {
  tone?: "ready" | "paused" | "over" | "won";
  title: string;
  subtitle: string;
  score?: number | string;
  scoreLabel?: string;
  best?: number | string | null;
  bestLabel?: string;
  isNewBest?: boolean;
  actionLabel?: string;
  actionAriaLabel?: string;
  onAction?: () => void;
  secondaryLabel?: string;
  onSecondary?: () => void;
  controlsHint?: string;
  icon?: ReactNode;
  asButtonSurface?: boolean;
  children?: ReactNode;
}) {
  const isEnd = tone === "over" || tone === "won";
  const ActionIcon = tone === "over" ? RotateCcw : Play;

  return (
    <div
      className={`game-stage-overlay game-stage-overlay-${tone}`}
      onPointerDown={asButtonSurface ? undefined : (event) => event.stopPropagation()}
    >
      <div className="game-overlay-card">
        {icon && <div className="game-overlay-emblem" aria-hidden="true">{icon}</div>}
        <strong className="game-overlay-title">{title}</strong>
        <p className="game-overlay-subtitle">{subtitle}</p>
        {(score !== undefined || (best !== undefined && best !== null)) && (
          <div className="game-overlay-stats">
            {score !== undefined && (
              <div>
                <span>{scoreLabel}</span>
                <strong>{score}</strong>
              </div>
            )}
            {best !== undefined && best !== null && (
              <div>
                <span>{bestLabel}</span>
                <strong>
                  {best}
                  {isNewBest && (
                    <em className="game-overlay-record">
                      <Trophy size={12} aria-hidden="true" /> Best
                    </em>
                  )}
                </strong>
              </div>
            )}
          </div>
        )}
        {children}
        {controlsHint && <small className="game-overlay-hint">{controlsHint}</small>}
        {!asButtonSurface && actionLabel && onAction && (
          <div className="game-overlay-actions">
            <Button
              onClick={onAction}
              aria-label={actionAriaLabel ?? actionLabel}
            >
              <ActionIcon size={16} aria-hidden="true" /> {actionLabel}
            </Button>
            {secondaryLabel && onSecondary && (
              <Button variant="quiet" onClick={onSecondary}>
                {isEnd ? <RotateCcw size={15} aria-hidden="true" /> : null}
                {secondaryLabel}
              </Button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
