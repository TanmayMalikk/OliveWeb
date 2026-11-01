// Fitness cover users: provides a desktop dashboard for synced activity, vitals, and nutrition.
import {
  Activity, ArrowLeft, CalendarDays, Flame, Footprints, Gauge, HeartPulse,
  Home, Plus, Salad, Search, Settings, Sparkles, Utensils,
} from "lucide-react";
import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Button, Field, Form } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import {
  loadFitness, logFood, searchFoods, type FitnessDay, type FitnessSummary, type Food,
} from "../../services/FitnessService";

type FitnessTab = "home" | "activity" | "vitals" | "nutrition";
const navigation = [
  ["home", "Overview", Home],
  ["activity", "Activity", Activity],
  ["vitals", "Vitals", HeartPulse],
  ["nutrition", "Nutrition", Salad],
] as const;

// Shows the metric view for web fitness cover cover. It uses the saved health data to update the fitness screen.
function Metric({ icon: Icon, value, label, note }: {
  icon: typeof Activity; value: string; label: string; note?: string;
}) {
  return <article className="fitness-stat-card">
    <span className="fitness-stat-icon" aria-hidden="true"><Icon /></span>
    <div><span>{label}</span><strong>{value}</strong>{note && <small>{note}</small>}</div>
  </article>;
}

// Web fitness cover cover: Shows the history chart view. It uses the saved health data to update the fitness screen.
function HistoryChart({ days, field, label, suffix = "" }: {
  days: FitnessDay[]; field: keyof FitnessDay; label: string; suffix?: string;
}) {
  const values = days.map((day) => Number(day[field]) || 0);
  const maximum = Math.max(1, ...values);
  const ticks = [maximum, maximum * 0.75, maximum * 0.5, maximum * 0.25, 0];
  return <div className="fitness-chart-wrap" role="img" aria-label={`${label}, last 14 days`}>
    <div className="fitness-chart-axis" aria-hidden="true">
      {ticks.map((tick) => <span key={tick}>{Math.round(tick).toLocaleString()}</span>)}
    </div>
    <div className="fitness-history-chart">
      {days.map((day, index) => <div className="fitness-history-day" key={day.date}
        title={`${day.date}: ${values[index].toFixed(field === "steps" ? 0 : 1)}${suffix}`}>
        <span style={{ height: `${Math.max(2, values[index] / maximum * 100)}%` }} />
        <small>{new Date(`${day.date}T12:00:00`).toLocaleDateString([], { month: "short", day: "numeric" })}</small>
      </div>)}
    </div>
  </div>;
}

const manualFood = (data: FormData): Food => ({
  id: crypto.randomUUID(), name: String(data.get("name") || "Food").trim(),
  brand: "Manual entry", servingDescription: "1 serving",
  calories: Number(data.get("calories")) || 0,
  proteinGrams: Number(data.get("protein")) || 0,
  carbohydrateGrams: Number(data.get("carbs")) || 0,
  fatGrams: Number(data.get("fat")) || 0,
});

export default function FitnessCover() {
  const session = useSession();
  const foodSearchReady = Boolean(import.meta.env.VITE_FIREBASE_APPCHECK_SITE_KEY);
  const [tab, setTab] = useState<FitnessTab>("home");
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [summary, setSummary] = useState<FitnessSummary | null>(null);
  const [feedback, setFeedback] = useState("");
  const [results, setResults] = useState<Food[]>([]);
  const [searching, setSearching] = useState(false);
  const lastOliveTap = useRef(0);

  useEffect(() => {
    void loadFitness().then(setSummary).catch(() => setFeedback("Activity could not be loaded."));
  }, []);

  const today = useMemo(() => {
    const date = new Date().toISOString().slice(0, 10);
    return summary?.days.find((day) => day.date === date) ?? {
      date, steps: 0, distanceKm: 0, caloriesBurned: 0, caloriesEaten: 0,
      proteinGrams: 0, carbohydrateGrams: 0, fatGrams: 0,
    };
  }, [summary]);

  if (!summary) return <main id="main" className="fitness-loading"><span /><p role="status">Loading your fitness dashboard…</p></main>;

  const progress = Math.min(100, today.steps / Math.max(1, summary.stepGoal) * 100);
  const recentDays = summary.days.slice(-14);
  const week = recentDays.slice(-7);
  const weekSteps = week.reduce((total, day) => total + day.steps, 0);
  const weekCalories = week.reduce((total, day) => total + day.caloriesBurned, 0);
  // Web fitness cover cover: Adds the new item.
  const add = async (food: Food, servings = 1) => {
    setSummary(await logFood(summary, food, servings));
    setFeedback(`${food.name} added to today.`);
  };
  // returns the tap olive for web fitness cover cover.
  const tapOlive = () => {
    const now = Date.now();
    if (now - lastOliveTap.current <= 1500) {
      lastOliveTap.current = 0;
      setFeedback("");
      void session.perform(session.beginEntry);
      return;
    }
    lastOliveTap.current = now;
    setFeedback("Tap the olive once more to continue.");
  };
  // Starts tab for web fitness cover cover.
  const showTab = (next: FitnessTab) => {
    setTab(next);
    setSettingsOpen(false);
    setFeedback("");
  };

  return <main id="main" className="fitness-page">
    <aside className="fitness-sidebar">
      <div className="fitness-brand"><span className="fitness-brand-mark" aria-hidden="true">O</span>
        <div><strong>Olive Fitness</strong><small>Your health dashboard</small></div></div>
      <nav className="fitness-navigation" aria-label="Fitness pages" role="tablist">
        {navigation.map(([id, label, Icon]) => <button type="button" key={id} role="tab"
          aria-selected={!settingsOpen && tab === id} onClick={() => showTab(id)}>
          <Icon aria-hidden="true" /><span>{label}</span>
        </button>)}
      </nav>
      <div className="fitness-sidebar-sync"><span className={summary.updatedAt ? "online" : ""} />
        <div><strong>{summary.updatedAt ? "Activity synced" : "Waiting for sync"}</strong>
          <small>{summary.updatedAt ? new Date(summary.updatedAt).toLocaleString() : "Open the Android cover"}</small></div>
      </div>
    </aside>

    <section className="fitness-workspace">
      <header className="fitness-heading">
        <div><span className="eyebrow">
          {settingsOpen ? "SETTINGS" : navigation.find(([id]) => id === tab)?.[1]?.toUpperCase()}
        </span>
          <h1>Fitness Tracker</h1></div>
        <div className="fitness-heading-actions"><time><CalendarDays />
          {new Date().toLocaleDateString([], { weekday: "long", month: "long", day: "numeric" })}</time>
          <button type="button" className="fitness-settings"
            aria-label={settingsOpen ? "Return to Fitness Tracker" : "Fitness settings"}
            onClick={() => { setSettingsOpen((open) => !open); setFeedback(""); }}>
            {settingsOpen ? <ArrowLeft /> : <Settings />}</button></div>
      </header>

      <nav className="fitness-mobile-tabs" aria-label="Fitness pages">
        {navigation.map(([id, label, Icon]) => <button type="button" key={id}
          aria-current={!settingsOpen && tab === id ? "page" : undefined} onClick={() => showTab(id)}>
          <Icon aria-hidden="true" /><span>{label}</span></button>)}
      </nav>

      {feedback && <p className="fitness-feedback" role="status">{feedback}</p>}
      <div className="fitness-content">
        {settingsOpen ? <section className="fitness-card fitness-settings-panel">
          <div className="fitness-section-heading"><div><span className="eyebrow">FITNESS SETTINGS</span>
            <h2>Preferences</h2></div><p>Manage how this browser displays your synced health information.</p></div>
          <div className="fitness-setting-row"><div><strong>Activity sync</strong>
            <span>Steps and health summaries come from your connected Android app.</span></div>
            <span className="fitness-sync-state">{summary.updatedAt ? "Synced" : "Waiting"}</span></div>
          <div className="fitness-setting-row"><div><strong>Theme</strong><span>Olive light</span></div>
            <span className="fitness-colour-dot" aria-hidden="true" /></div>
          <div className="fitness-entry-setting"><button type="button" className="fitness-private-entry"
            aria-label="olive icon" onClick={tapOlive}><span className="fitness-olive-mark" aria-hidden="true" /></button></div>
        </section> : <>

        {tab === "home" && <>
          <section className="fitness-welcome"><div><span className="eyebrow">TODAY AT A GLANCE</span>
            <h2>Your daily movement, all in one place.</h2><p>Activity from Android and Wear OS appears here after it syncs.</p></div>
            <Sparkles aria-hidden="true" /></section>
          <div className="fitness-dashboard-grid">
            <section className="fitness-card fitness-steps-card">
              <div className="fitness-card-title"><div><span>Daily steps</span><strong>Keep moving</strong></div>
                <Footprints aria-hidden="true" /></div>
              <div className="fitness-step-visual">
                <div className="fitness-step-ring" style={{ "--fitness-progress": `${progress * 3.6}deg` } as CSSProperties}>
                  <div><strong>{today.steps.toLocaleString()}</strong><span>of {summary.stepGoal.toLocaleString()} steps</span></div>
                </div>
                <div className="fitness-goal-copy"><strong>{Math.round(progress)}% complete</strong>
                  <span>{Math.max(0, summary.stepGoal - today.steps).toLocaleString()} steps left today</span></div>
              </div>
            </section>
            <div className="fitness-stat-grid">
              <Metric icon={Gauge} value={`${today.distanceKm.toFixed(1)} km`} label="Distance" note="Today" />
              <Metric icon={Flame} value={Math.round(today.caloriesBurned).toLocaleString()} label="Active calories" note="kcal burned" />
              <Metric icon={Utensils} value={Math.round(today.caloriesEaten).toLocaleString()} label="Calories eaten" note={`of ${summary.calorieGoal.toLocaleString()} kcal`} />
              <Metric icon={HeartPulse} value={summary.vitals.at(-1)?.averageBpm ? `${summary.vitals.at(-1)?.averageBpm} bpm` : "—"}
                label="Average heart rate" note={summary.watchConnected ? "Latest watch reading" : "Wear OS not connected"} />
            </div>
            <section className="fitness-card fitness-wide-card"><div className="fitness-card-title"><div>
              <span>Movement trend</span><strong>Last 14 days</strong></div>
              <button type="button" onClick={() => showTab("activity")}>View activity</button></div>
              {recentDays.length ? <HistoryChart days={recentDays} field="steps" label="Steps" />
                : <div className="fitness-empty compact"><Footprints /><h3>No activity yet</h3><p>Synced steps will build your history chart.</p></div>}
            </section>
            <section className="fitness-card fitness-week-card"><span>This week</span><strong>{weekSteps.toLocaleString()}</strong>
              <p>total steps</p><div><Flame /><span>{Math.round(weekCalories).toLocaleString()} active kcal</span></div></section>
            <section className="fitness-card fitness-macro-card"><div className="fitness-card-title"><div>
              <span>Today’s nutrition</span><strong>{Math.round(today.caloriesEaten)} kcal</strong></div>
              <button type="button" onClick={() => showTab("nutrition")}>Log food</button></div>
              <MacroProgress summary={summary} day={today} />
            </section>
          </div>
        </>}

        {tab === "activity" && <div className="fitness-page-grid">
          <section className="fitness-card fitness-wide-card"><div className="fitness-section-heading"><div>
            <span className="eyebrow">ACTIVITY HISTORY</span><h2>Movement over time</h2></div>
            <p>{weekSteps.toLocaleString()} steps in the last 7 days</p></div>
            {recentDays.length ? <><h3>Steps</h3><HistoryChart days={recentDays} field="steps" label="Steps" />
              <h3>Calories burned</h3><HistoryChart days={recentDays} field="caloriesBurned" label="Calories burned" suffix=" kcal" /></>
              : <div className="fitness-empty" role="img" aria-label="Steps, last 14 days"><Activity /><h2>— No activity data —</h2>
                <p>Use the Android or Wear OS fitness cover to begin syncing activity.</p></div>}
          </section>
          <section className="fitness-card fitness-recent-card"><div className="fitness-card-title"><div>
            <span>Daily details</span><strong>Recent activity</strong></div></div>
            <div className="fitness-day-list">{recentDays.slice().reverse().map((day) => <article key={day.date}>
              <time>{new Date(`${day.date}T12:00:00`).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })}</time>
              <strong>{day.steps.toLocaleString()} steps</strong><span>{day.distanceKm.toFixed(1)} km · {Math.round(day.caloriesBurned)} kcal</span>
            </article>)}</div></section>
        </div>}

        {tab === "vitals" && <div className="fitness-page-grid">
          <section className="fitness-card fitness-wide-card"><div className="fitness-section-heading"><div>
            <span className="eyebrow">WEAR OS VITALS</span><h2>Heart-rate history</h2></div>
            <p>{summary.watchConnected ? "Watch connected" : "Waiting for a watch"}</p></div>
            {summary.vitals.length ? <div className="fitness-vitals-grid">{summary.vitals.slice(-14).reverse().map((row) =>
              <article key={row.date}><time>{new Date(`${row.date}T12:00:00`).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric" })}</time>
                <strong>{row.lowestBpm}–{row.peakBpm} bpm</strong><span>Average {row.averageBpm} · Resting {row.restingBpm ?? "—"}</span></article>)}</div>
              : <div className="fitness-empty"><HeartPulse /><h2>— No data —</h2>
                <p>Connect a Wear OS watch to Android. Heart-rate history appears after the phone syncs.</p></div>}
          </section>
          <section className="fitness-card fitness-vital-summary"><span>Latest summary</span>
            <HeartPulse aria-hidden="true" /><strong>{summary.vitals.at(-1)?.averageBpm ? `${summary.vitals.at(-1)?.averageBpm} bpm` : "—"}</strong>
            <p>Average heart rate</p><small>{summary.vitals.at(-1) ? `${summary.vitals.at(-1)?.lowestBpm} low · ${summary.vitals.at(-1)?.peakBpm} peak` : "No watch readings yet"}</small>
          </section>
        </div>}

        {tab === "nutrition" && <div className="fitness-nutrition-layout">
          <section className="fitness-card fitness-nutrition-summary"><div className="fitness-section-heading"><div>
            <span className="eyebrow">TODAY’S NUTRITION</span><h2>{Math.round(today.caloriesEaten)} of {summary.calorieGoal} kcal</h2></div>
            <p>WORK IN PROGRESS</p></div><MacroProgress summary={summary} day={today} /></section>
          <section className="fitness-card fitness-food-search"><h2>Find a food</h2>
            <p>Search packaged foods and common meals, then add them to today.</p>
            {foodSearchReady ? <Form onSubmit={async (data) => {
              setSearching(true); setFeedback("");
              try { setResults(await searchFoods(String(data.get("query")))); }
              catch (error) { setFeedback(error instanceof Error ? error.message : "Food search failed."); }
              finally { setSearching(false); }
            }}><Field label="Food or brand" name="query" minLength={2} maxLength={80} required />
              <Button type="submit" disabled={searching}><Search size={17} />{searching ? "Searching…" : "Search foods"}</Button></Form>
              : <p className="help-note">Food search becomes available after Firebase App Check is configured. Manual entry works now.</p>}
            <div className="fitness-results">{results.map((food) => <article className="fitness-food-result" key={food.id}><div>
              <strong>{food.name}</strong><span>{food.brand || food.servingDescription}</span>
              <small>{Math.round(food.calories)} kcal · P {food.proteinGrams} g · C {food.carbohydrateGrams} g · F {food.fatGrams} g</small></div>
              <Button variant="outline" onClick={() => void add(food)}><Plus size={16} /> Add</Button></article>)}</div>
          </section>
          <section className="fitness-card fitness-manual-card"><h2>Quick manual entry</h2>
            <p>Add nutrition information when a food is not in search.</p>
            <Form className="fitness-manual-form" onSubmit={async (data) => { await add(manualFood(data)); }}>
              <Field label="Food name" name="name" required maxLength={160} />
              <Field label="Calories" name="calories" type="number" min="0" max="10000" required />
              <Field label="Protein (g)" name="protein" type="number" min="0" max="1000" step="0.1" required />
              <Field label="Carbs (g)" name="carbs" type="number" min="0" max="1000" step="0.1" required />
              <Field label="Fat (g)" name="fat" type="number" min="0" max="1000" step="0.1" required />
              <Button type="submit"><Plus size={17} /> Add meal</Button>
            </Form>
          </section>
        </div>}
        </>}
      </div>
    </section>
  </main>;
}

// Web fitness cover cover: Shows the macro progress view. It uses the saved health data to update the fitness screen.
function MacroProgress({ summary, day }: { summary: FitnessSummary; day: FitnessDay }) {
  return <div className="fitness-macro-list large">
    {[["Protein", day.proteinGrams, summary.proteinGoal], ["Carbohydrates", day.carbohydrateGrams, summary.carbohydrateGoal],
      ["Fat", day.fatGrams, summary.fatGoal]].map(([label, value, goal]) => <div key={label as string}>
        <span>{label}<b>{Math.round(value as number)} / {goal} g</b></span>
        <i><em style={{ width: `${Math.min(100, (value as number) / Math.max(1, goal as number) * 100)}%` }} /></i>
      </div>)}
  </div>;
}
