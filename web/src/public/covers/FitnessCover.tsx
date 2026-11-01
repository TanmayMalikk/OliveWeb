// Fitness cover users: shows a discreet local activity and nutrition dashboard.
import { Activity, HeartPulse, Home, Salad, Settings } from "lucide-react";
import { useMemo, useState } from "react";

type FitnessTab = "home" | "activity" | "vitals" | "nutrition";

const dailyActivity = [4200, 6100, 5400, 7900, 8300, 7200, 8650];

function Metric({ value, label }: { value: string; label: string }) {
  return (
    <div className="fitness-metric">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  );
}

function ActivityChart() {
  const maximum = Math.max(...dailyActivity);
  return (
    <div
      className="fitness-chart"
      role="img"
      aria-label="Seven-day steps chart"
    >
      {dailyActivity.map((steps, index) => (
        <div className="fitness-chart-day" key={`${steps}-${index}`}>
          <span
            style={{ height: `${Math.max(8, (steps / maximum) * 100)}%` }}
          />
          <small>{["M", "T", "W", "T", "F", "S", "S"][index]}</small>
        </div>
      ))}
    </div>
  );
}

export default function FitnessCover() {
  const [tab, setTab] = useState<FitnessTab>("home");
  const steps = 8650;
  const goal = 10000;
  const progress = useMemo(() => Math.min(100, (steps / goal) * 100), [steps]);

  return (
    <div className="fitness-page">
      <header className="fitness-heading">
        <div>
          <span className="eyebrow">OLIVE FITNESS</span>
          <h1>Fitness Tracker</h1>
        </div>
        <button
          type="button"
          className="fitness-settings"
          aria-label="Fitness settings"
        >
          <Settings aria-hidden="true" />
        </button>
      </header>

      <nav className="fitness-tabs" aria-label="Fitness pages" role="tablist">
        {(
          [
            ["home", "Home", Home],
            ["activity", "Activity", Activity],
            ["vitals", "Vitals", HeartPulse],
            ["nutrition", "Nutrition", Salad],
          ] as const
        ).map(([id, label, Icon]) => (
          <button
            type="button"
            key={id}
            role="tab"
            aria-selected={tab === id}
            onClick={() => setTab(id)}
          >
            <Icon aria-hidden="true" />
            {label}
          </button>
        ))}
      </nav>

      <div className="fitness-content">
        {tab === "home" && (
          <>
            <section className="fitness-hero">
              <p>Today’s summary</p>
              <div className="fitness-metrics">
                <Metric value={steps.toLocaleString()} label="Steps" />
                <Metric value="6.2 km" label="Distance" />
                <Metric value="385" label="Active kcal" />
              </div>
            </section>
            <section className="fitness-card">
              <div className="fitness-card-title">
                <div>
                  <span>Daily steps</span>
                  <strong>{steps.toLocaleString()}</strong>
                </div>
                <b>{Math.round(progress)}%</b>
              </div>
              <div
                className="fitness-progress"
                role="progressbar"
                aria-label="Daily step goal"
                aria-valuenow={steps}
                aria-valuemin={0}
                aria-valuemax={goal}
              >
                <span style={{ width: `${progress}%` }} />
              </div>
              <small>{goal.toLocaleString()} step goal</small>
            </section>
          </>
        )}

        {tab === "activity" && (
          <section className="fitness-card">
            <div className="fitness-card-title">
              <div>
                <span>Activity</span>
                <strong>7-day steps</strong>
              </div>
            </div>
            <ActivityChart />
          </section>
        )}

        {tab === "vitals" && (
          <section className="fitness-card fitness-empty">
            <HeartPulse aria-hidden="true" />
            <h2>Wear OS vitals</h2>
            <p>
              Connect a compatible Wear OS watch in the Android app to track
              heart-rate data.
            </p>
            <strong>— No data —</strong>
          </section>
        )}

        {tab === "nutrition" && (
          <section className="fitness-card">
            <div className="fitness-card-title">
              <div>
                <span>Nutrition</span>
                <strong>Today</strong>
              </div>
            </div>
            <div className="fitness-metrics light">
              <Metric value="1,420" label="Calories" />
              <Metric value="82 g" label="Protein" />
              <Metric value="156 g" label="Carbs" />
              <Metric value="48 g" label="Fat" />
            </div>
          </section>
        )}
      </div>
    </div>
  );
}
