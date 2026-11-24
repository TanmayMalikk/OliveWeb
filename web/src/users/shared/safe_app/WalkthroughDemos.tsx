// All safe-app roles: real interactive sandbox demos for each web walkthrough feature.
import { useState, type ReactNode } from "react";
import {
  CheckCircle2,
  Heart,
  HeartHandshake,
  Lock,
  LockOpen,
  MapPin,
  MessageCircle,
  Paperclip,
  RotateCcw,
  ShieldCheck,
  Trash2,
} from "lucide-react";
import { Button, Field } from "../../../components/SharedComponents";

interface DemoProps {
  onInteracted: () => void;
}

function DemoResetBar({ onReset }: { onReset: () => void }) {
  return (
    <div style={{ display: "flex", justifyContent: "flex-end", marginTop: "0.75rem" }}>
      <Button variant="quiet" onClick={onReset} data-testid="walkthrough-demo-reset">
        <RotateCcw size={15} aria-hidden="true" /> Reset demo
      </Button>
    </div>
  );
}

function SandboxCard({ children }: { children: ReactNode }) {
  return (
    <div
      style={{
        border: "1px solid var(--border, #c5d1bc)",
        borderRadius: "12px",
        padding: "0.9rem",
        background: "var(--surface, #fffdf8)",
      }}
    >
      {children}
    </div>
  );
}

function CoverEntryDemo({ onInteracted }: DemoProps) {
  const [disguise, setDisguise] = useState<"NEWS" | "FITNESS" | "GAME">("NEWS");
  const [stage, setStage] = useState<"cover" | "pin" | "unlocked">("cover");
  const [phrase, setPhrase] = useState("");
  const [phraseError, setPhraseError] = useState<string | undefined>();
  const [phraseAttempt, setPhraseAttempt] = useState(0);
  const [pin, setPin] = useState("");
  const [pinError, setPinError] = useState<string | undefined>();
  const [pinAttempt, setPinAttempt] = useState(0);

  const reset = () => {
    setDisguise("NEWS");
    setStage("cover");
    setPhrase("");
    setPhraseError(undefined);
    setPin("");
    setPinError(undefined);
  };

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
        <span>Sample cover theme:</span>
        {(
          [
            ["NEWS", "Daily Digest"],
            ["FITNESS", "Fitness Tracker"],
            ["GAME", "Daily Sudoku"],
          ] as const
        ).map(([key, label]) => (
          <Button
            key={key}
            variant={disguise === key ? "" : "quiet"}
            onClick={() => {
              setDisguise(key);
              onInteracted();
            }}
          >
            {label}
          </Button>
        ))}
      </div>

      <SandboxCard>
        {stage === "cover" && (
          <div style={{ display: "grid", gap: "0.75rem" }}>
            <Field
              label="Sample cover search trigger"
              placeholder='Try "moon garden" or submit blank to test shake'
              value={phrase}
              error={phraseError}
              shakeTrigger={phraseAttempt}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                setPhrase(e.target.value);
                setPhraseError(undefined);
              }}
            />
            <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
              <Button
                variant="quiet"
                onClick={() => {
                  setPhrase("moon garden");
                  setPhraseError(undefined);
                  onInteracted();
                }}
              >
                Fill &ldquo;moon garden&rdquo;
              </Button>
              <Button
                onClick={() => {
                  onInteracted();
                  if (phrase.trim().toLowerCase() === "moon garden") {
                    setPhraseError(undefined);
                    setStage("pin");
                  } else {
                    setPhraseError('Enter "moon garden" to unlock the next step');
                    setPhraseAttempt((n) => n + 1);
                  }
                }}
              >
                Check trigger
              </Button>
            </div>
          </div>
        )}

        {stage === "pin" && (
          <div style={{ display: "grid", gap: "0.75rem" }}>
            <Field
              label="Sample browser PIN (enter 1234)"
              value={pin}
              error={pinError}
              shakeTrigger={pinAttempt}
              onChange={(e: React.ChangeEvent<HTMLInputElement>) => {
                setPin(e.target.value);
                setPinError(undefined);
              }}
            />
            <Button
              onClick={() => {
                onInteracted();
                if (pin.trim() === "1234") {
                  setPinError(undefined);
                  setStage("unlocked");
                } else {
                  setPinError("Wrong PIN — try 1234 (shakes on error!)");
                  setPinAttempt((n) => n + 1);
                }
              }}
            >
              <LockOpen size={16} aria-hidden="true" /> Unlock Safe Space
            </Button>
          </div>
        )}

        {stage === "unlocked" && (
          <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <CheckCircle2 size={18} aria-hidden="true" />
            <strong>PIN accepted — Private Workspace unlocked in sandbox!</strong>
          </div>
        )}
      </SandboxCard>

      <DemoResetBar onReset={reset} />
    </div>
  );
}

function QuickExitDemo({ onInteracted }: DemoProps) {
  const [idleMinutes, setIdleMinutes] = useState(2);
  const [exited, setExited] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
        <span>Idle auto-lock timer:</span>
        {[1, 2, 5].map((mins) => (
          <Button
            key={mins}
            variant={idleMinutes === mins ? "" : "quiet"}
            onClick={() => {
              setIdleMinutes(mins);
              onInteracted();
            }}
          >
            {mins} min
          </Button>
        ))}
      </div>

      <SandboxCard>
        {exited ? (
          <div style={{ display: "grid", gap: "0.6rem" }}>
            <strong>Public Cover Page (Disguised)</strong>
            <p style={{ margin: 0 }}>
              Session locked and switched to cover immediately (idle timer set to {idleMinutes} min).
            </p>
            <div>
              <Button
                variant="quiet"
                onClick={() => {
                  setExited(false);
                  onInteracted();
                }}
              >
                Re-enter sandbox
              </Button>
            </div>
          </div>
        ) : (
          <div style={{ display: "grid", gap: "0.6rem" }}>
            <strong>Simulated Safe Workspace</strong>
            <p style={{ margin: 0 }}>
              Private workspace active. Click Quick Exit to lock the sandbox session right now.
            </p>
            <div>
              <Button
                onClick={() => {
                  setExited(true);
                  onInteracted();
                }}
              >
                Quick Exit
              </Button>
            </div>
          </div>
        )}
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setIdleMinutes(2);
          setExited(false);
        }}
      />
    </div>
  );
}

function SafetyPlanDemo({ onInteracted }: DemoProps) {
  const items = [
    "ID & birth certificate copies",
    "Spare house and car keys",
    "Emergency cash & essential medications",
  ];
  const [checked, setChecked] = useState([false, false, false]);
  const [shared, setShared] = useState(false);
  const ready = checked.filter(Boolean).length;

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: "0.5rem" }}>
        <strong>Checklist: {ready} / {items.length} ready</strong>
        <span className="tag">{shared ? "Shared with Maya" : "Private"}</span>
      </div>

      <SandboxCard>
        <div style={{ display: "grid", gap: "0.5rem" }}>
          {items.map((label, idx) => (
            <label
              key={label}
              data-testid={`walkthrough-demo-plan-item-${idx}`}
              style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}
            >
              <input
                type="checkbox"
                checked={checked[idx]}
                onChange={() => {
                  setChecked((prev) => prev.map((val, i) => (i === idx ? !val : val)));
                  onInteracted();
                }}
              />
              <span>{label}</span>
            </label>
          ))}
          <hr style={{ width: "100%", opacity: 0.25 }} />
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={shared}
              onChange={(e) => {
                setShared(e.target.checked);
                onInteracted();
              }}
            />
            <span>Share safety plan with Maya</span>
          </label>
        </div>
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setChecked([false, false, false]);
          setShared(false);
        }}
      />
    </div>
  );
}

function NotesIncidentsDemo({ onInteracted }: DemoProps) {
  const [kind, setKind] = useState<"Note" | "Incident">("Incident");
  const [pinned, setPinned] = useState(false);
  const [editCount, setEditCount] = useState(1);
  const [showHistory, setShowHistory] = useState(false);
  const [trashed, setTrashed] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        {(["Note", "Incident"] as const).map((k) => (
          <Button
            key={k}
            variant={kind === k ? "" : "quiet"}
            onClick={() => {
              setKind(k);
              onInteracted();
            }}
          >
            {k}
          </Button>
        ))}
      </div>

      <SandboxCard>
        {trashed ? (
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
            <span>Entry moved to trash. Click Undo to restore it.</span>
            <Button
              variant="quiet"
              onClick={() => {
                setTrashed(false);
                onInteracted();
              }}
            >
              Undo
            </Button>
          </div>
        ) : (
          <div style={{ display: "grid", gap: "0.6rem" }}>
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
              <strong>
                {kind === "Incident" ? "Oct 12 · Front hallway incident" : "Emergency bag reminder"}
              </strong>
              {pinned && <span className="tag">Pinned</span>}
            </div>
            <p style={{ margin: 0 }}>
              {kind === "Incident"
                ? "18:30 · 104 St NW, Edmonton · Saved photo & timestamped notes."
                : "Kept spare charger and ID copies at my desk locker."}
            </p>
            <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
              <Button
                variant="quiet"
                onClick={() => {
                  setPinned((v) => !v);
                  onInteracted();
                }}
              >
                {pinned ? "Unpin" : "Pin"}
              </Button>
              <Button
                variant="quiet"
                onClick={() => {
                  setEditCount((c) => c + 1);
                  setShowHistory(true);
                  onInteracted();
                }}
              >
                Add sample edit
              </Button>
              <Button
                variant="quiet"
                onClick={() => {
                  setShowHistory((v) => !v);
                  onInteracted();
                }}
              >
                Edit history ({editCount})
              </Button>
              <Button
                variant="quiet"
                onClick={() => {
                  setTrashed(true);
                  onInteracted();
                }}
              >
                <Trash2 size={15} aria-hidden="true" /> Move to trash
              </Button>
            </div>
            {showHistory && (
              <ol style={{ margin: 0, paddingLeft: "1.25rem" }}>
                <li>Initial entry created</li>
                {Array.from({ length: editCount - 1 }, (_, i) => (
                  <li key={i}>Updated location and details</li>
                ))}
              </ol>
            )}
          </div>
        )}
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setKind("Incident");
          setPinned(false);
          setEditCount(1);
          setShowHistory(false);
          setTrashed(false);
        }}
      />
    </div>
  );
}

function DocumentsDemo({ onInteracted }: DemoProps) {
  const [encrypted, setEncrypted] = useState(false);
  const [shared, setShared] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div>
        <Button
          onClick={() => {
            setEncrypted(true);
            onInteracted();
          }}
        >
          <Lock size={16} aria-hidden="true" /> Simulate local encryption &amp; upload
        </Button>
      </div>

      <SandboxCard>
        <div style={{ display: "grid", gap: "0.5rem" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
            <strong>Lease_Agreement.pdf</strong>
            <span className="tag">{encrypted ? "Encrypted (AES-256)" : "Pending encryption"}</span>
          </div>
          {encrypted && (
            <>
              <p style={{ margin: 0 }}>Preview snippet: Lease Agreement · Unit 4B · Notice dated Nov 18</p>
              <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
                <input
                  type="checkbox"
                  checked={shared}
                  onChange={(e) => {
                    setShared(e.target.checked);
                    onInteracted();
                  }}
                />
                <span>Share encrypted file with Maya</span>
              </label>
            </>
          )}
          <small>Voice notes remain inside chat threads and are excluded from Document storage.</small>
        </div>
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setEncrypted(false);
          setShared(false);
        }}
      />
    </div>
  );
}

function MessagingVoiceDemo({ onInteracted }: DemoProps) {
  const [inviteVisible, setInviteVisible] = useState(false);
  const [voiceAdded, setVoiceAdded] = useState(false);
  const [draft, setDraft] = useState("");
  const [sentMessage, setSentMessage] = useState<string | null>(null);
  const [unsent, setUnsent] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        <Button
          variant="quiet"
          onClick={() => {
            setInviteVisible(true);
            onInteracted();
          }}
        >
          <Paperclip size={15} aria-hidden="true" /> Generate 48h invite code
        </Button>
        <Button
          variant="quiet"
          onClick={() => {
            setVoiceAdded(true);
            onInteracted();
          }}
        >
          Add chat-only voice note
        </Button>
      </div>

      {inviteVisible && (
        <div>
          <span className="tag">Invite code: 4F8K-92M1 · Valid 48 hours</span>
        </div>
      )}

      <SandboxCard>
        <div style={{ display: "grid", gap: "0.5rem" }}>
          <p style={{ margin: 0 }}>Maya: Checking in — let me know if you need a ride.</p>
          {voiceAdded && (
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
              <span>Voice message · 0:08</span>
              <span className="tag">Chat only</span>
            </div>
          )}
          {sentMessage && (
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}>
              <span>{unsent ? "Message unsent" : `You: ${sentMessage}`}</span>
              {!unsent && (
                <Button
                  variant="quiet"
                  onClick={() => {
                    setUnsent(true);
                    onInteracted();
                  }}
                >
                  Unsend
                </Button>
              )}
            </div>
          )}
        </div>
      </SandboxCard>

      <div style={{ display: "flex", gap: "0.5rem", alignItems: "flex-end", flexWrap: "wrap" }}>
        <div style={{ flex: "1 1 220px" }}>
          <Field
            label="Sample encrypted message"
            value={draft}
            placeholder="Type a message..."
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => setDraft(e.target.value)}
          />
        </div>
        <Button
          onClick={() => {
            setSentMessage(draft.trim() || "Safe at library until 5pm.");
            setDraft("");
            setUnsent(false);
            onInteracted();
          }}
        >
          <MessageCircle size={15} aria-hidden="true" /> Send
        </Button>
      </div>

      <DemoResetBar
        onReset={() => {
          setInviteVisible(false);
          setVoiceAdded(false);
          setDraft("");
          setSentMessage(null);
          setUnsent(false);
        }}
      />
    </div>
  );
}

function LocationDemo({ onInteracted }: DemoProps) {
  const [checked, setChecked] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        <Button
          onClick={() => {
            setChecked(true);
            onInteracted();
          }}
        >
          <MapPin size={15} aria-hidden="true" /> Preview one-time map check
        </Button>
      </div>
      <SandboxCard>
        <p style={{ margin: 0 }}>
          {checked
            ? "Sandbox pin: Edmonton, AB (53.5461, -113.4938). Maya is sharing live location with you (12 min away)."
            : "Map preview paused. Trigger a simulated lookup without touching browser geolocation."}
        </p>
      </SandboxCard>
      <DemoResetBar onReset={() => setChecked(false)} />
    </div>
  );
}

function StaffHelpDemo({ onInteracted }: DemoProps) {
  const [status, setStatus] = useState<"idle" | "pending" | "assigned">("idle");
  const [replied, setReplied] = useState(false);
  const [friendAdded, setFriendAdded] = useState(false);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <SandboxCard>
        {status === "idle" && (
          <Button
            onClick={() => {
              setStatus("pending");
              onInteracted();
            }}
          >
            <HeartHandshake size={16} aria-hidden="true" /> Request staff help
          </Button>
        )}
        {status === "pending" && (
          <div style={{ display: "grid", gap: "0.6rem" }}>
            <p style={{ margin: 0 }}>Searching for an available organization staff member…</p>
            <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
              <Button
                onClick={() => {
                  setStatus("assigned");
                  onInteracted();
                }}
              >
                Simulate staff assigned
              </Button>
              <Button
                variant="quiet"
                onClick={() => {
                  setStatus("idle");
                  onInteracted();
                }}
              >
                Cancel request
              </Button>
            </div>
          </div>
        )}
        {status === "assigned" && (
          <div style={{ display: "grid", gap: "0.6rem" }}>
            <strong>Ticket #104 · Assigned to Alex (Staff)</strong>
            <p style={{ margin: 0 }}>Alex: Hi, I am here to help. What would you like to plan today?</p>
            {replied && <p style={{ margin: 0 }}><strong>You:</strong> Could we review my backup shelter plan?</p>}
            <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
              {!replied && (
                <Button
                  onClick={() => {
                    setReplied(true);
                    onInteracted();
                  }}
                >
                  Send sample reply
                </Button>
              )}
            </div>
            <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
              <input
                type="checkbox"
                checked={friendAdded}
                onChange={(e) => {
                  setFriendAdded(e.target.checked);
                  onInteracted();
                }}
              />
              <span>Add accepted friend (Maya) to support ticket</span>
            </label>
          </div>
        )}
      </SandboxCard>
      <DemoResetBar
        onReset={() => {
          setStatus("idle");
          setReplied(false);
          setFriendAdded(false);
        }}
      />
    </div>
  );
}

function HotlinesContactsDemo({ onInteracted }: DemoProps) {
  const [only247, setOnly247] = useState(false);
  const [saved, setSaved] = useState<Record<number, boolean>>({});
  const [safeStay, setSafeStay] = useState(true);
  const lines = [
    { id: 0, name: "Alberta 24/7 Family Violence Info Line", h24: true },
    { id: 1, name: "Edmonton Crisis Shelter Intake (24/7)", h24: true },
    { id: 2, name: "Legal Aid Alberta Advice Clinic", h24: false },
  ].filter((l) => !only247 || l.h24);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap", alignItems: "center" }}>
        <Button
          variant={!only247 ? "" : "quiet"}
          onClick={() => {
            setOnly247(false);
            onInteracted();
          }}
        >
          All
        </Button>
        <Button
          variant={only247 ? "" : "quiet"}
          onClick={() => {
            setOnly247(true);
            onInteracted();
          }}
        >
          24/7 only
        </Button>
        <span className="tag">Saved ({Object.values(saved).filter(Boolean).length})</span>
      </div>

      <SandboxCard>
        <div style={{ display: "grid", gap: "0.5rem" }}>
          {lines.map((line) => (
            <div
              key={line.id}
              style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: "0.5rem" }}
            >
              <span>{line.name}</span>
              <Button
                variant="quiet"
                onClick={() => {
                  setSaved((prev) => ({ ...prev, [line.id]: !prev[line.id] }));
                  onInteracted();
                }}
              >
                <Heart size={15} aria-hidden="true" /> {saved[line.id] ? "Saved" : "Save"}
              </Button>
            </div>
          ))}
          <hr style={{ width: "100%", opacity: 0.25 }} />
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={safeStay}
              onChange={(e) => {
                setSafeStay(e.target.checked);
                onInteracted();
              }}
            />
            <span>Maya (Trusted sister) — Safe to stay with</span>
          </label>
        </div>
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setOnly247(false);
          setSaved({});
          setSafeStay(true);
        }}
      />
    </div>
  );
}

function ActivitySettingsDemo({ onInteracted }: DemoProps) {
  const [filter, setFilter] = useState<"all" | "settings" | "triggers" | "pin">("all");
  const [passkey, setPasskey] = useState(true);
  const logs = [
    { cat: "settings", text: "Settings · Idle timeout changed to 2 min" },
    { cat: "triggers", text: "Triggers · Cover phrase accepted" },
    { cat: "pin", text: "PIN · Unlocked Safe Space" },
    { cat: "settings", text: "Settings · Passkey recovery factor enrolled" },
  ].filter((l) => filter === "all" || l.cat === filter);

  return (
    <div style={{ display: "grid", gap: "0.75rem" }}>
      <div style={{ display: "flex", gap: "0.5rem", flexWrap: "wrap" }}>
        {(
          [
            ["all", "All"],
            ["settings", "Settings"],
            ["triggers", "Triggers"],
            ["pin", "PIN"],
          ] as const
        ).map(([key, label]) => (
          <Button
            key={key}
            variant={filter === key ? "" : "quiet"}
            onClick={() => {
              setFilter(key);
              onInteracted();
            }}
          >
            {label}
          </Button>
        ))}
      </div>

      <SandboxCard>
        <div style={{ display: "grid", gap: "0.5rem" }}>
          <ul style={{ margin: 0, paddingLeft: "1.25rem" }}>
            {logs.map((entry, idx) => (
              <li key={idx}>{entry.text}</li>
            ))}
          </ul>
          <hr style={{ width: "100%", opacity: 0.25 }} />
          <label style={{ display: "flex", alignItems: "center", gap: "0.5rem", cursor: "pointer" }}>
            <input
              type="checkbox"
              checked={passkey}
              onChange={(e) => {
                setPasskey(e.target.checked);
                onInteracted();
              }}
            />
            <span>
              <ShieldCheck size={15} aria-hidden="true" /> Passkey Account Recovery Key enrolled
            </span>
          </label>
          <small>Safeguard: if only 1 passkey remains, removal is blocked to protect recovery access.</small>
        </div>
      </SandboxCard>

      <DemoResetBar
        onReset={() => {
          setFilter("all");
          setPasskey(true);
        }}
      />
    </div>
  );
}

export function WalkthroughInteractiveDemo({
  topicId,
  onPracticeCompleted,
}: {
  topicId: string;
  onPracticeCompleted: () => void;
}) {
  switch (topicId) {
    case "cover_entry":
      return <CoverEntryDemo onInteracted={onPracticeCompleted} />;
    case "quick_exit":
      return <QuickExitDemo onInteracted={onPracticeCompleted} />;
    case "safety_plan":
      return <SafetyPlanDemo onInteracted={onPracticeCompleted} />;
    case "notes_incidents":
      return <NotesIncidentsDemo onInteracted={onPracticeCompleted} />;
    case "documents":
      return <DocumentsDemo onInteracted={onPracticeCompleted} />;
    case "messaging_voice":
      return <MessagingVoiceDemo onInteracted={onPracticeCompleted} />;
    case "location":
      return <LocationDemo onInteracted={onPracticeCompleted} />;
    case "staff_help":
      return <StaffHelpDemo onInteracted={onPracticeCompleted} />;
    case "hotlines_contacts":
      return <HotlinesContactsDemo onInteracted={onPracticeCompleted} />;
    case "activity_settings":
      return <ActivitySettingsDemo onInteracted={onPracticeCompleted} />;
    default:
      return null;
  }
}
