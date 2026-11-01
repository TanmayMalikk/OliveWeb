// Fitness cover users: logs meals and displays activity synced from Android and Wear OS.
import { Activity, ArrowLeft, HeartPulse, Home, Plus, Salad, Search, Settings } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { Button, Field, Form } from "../../components/SharedComponents";
import { useSession } from "../../session/SessionProvider";
import {
  loadFitness, logFood, searchFoods, type FitnessDay, type FitnessSummary, type Food,
} from "../../services/FitnessService";

type FitnessTab = "home" | "activity" | "vitals" | "nutrition";
function Metric({ value, label }: { value: string; label: string }) {
  return <div className="fitness-metric"><strong>{value}</strong><span>{label}</span></div>;
}
function Bars({ days, field, label }: { days: FitnessDay[]; field: keyof FitnessDay; label: string }) {
  const values = days.map((day) => Number(day[field]) || 0);
  const maximum = Math.max(1, ...values);
  return <div className="fitness-history-chart" role="img" aria-label={`${label}, last 14 days`}>
    {days.map((day, index) => <div className="fitness-history-day" key={day.date}
      title={`${day.date}: ${values[index].toFixed(field === "steps" ? 0 : 1)}`}>
      <span style={{ height: `${Math.max(3, values[index] / maximum * 100)}%` }} />
      <small>{new Date(`${day.date}T12:00:00`).toLocaleDateString([], { weekday: "narrow" })}</small>
    </div>)}
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
  if (!summary) return <div className="fitness-page"><p role="status">Loading fitness summary…</p></div>;
  const progress = Math.min(100, today.steps / Math.max(1, summary.stepGoal) * 100);
  const add = async (food: Food, servings = 1) => {
    setSummary(await logFood(summary, food, servings));
    setFeedback(`${food.name} added to today.`);
  };
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
  return <div className="fitness-page">
    <header className="fitness-heading"><div><span className="eyebrow">OLIVE FITNESS</span><h1>Fitness Tracker</h1></div>
      <button type="button" className="fitness-settings" aria-label={settingsOpen ? "Return to Fitness Tracker" : "Fitness settings"}
        onClick={() => { setSettingsOpen((open) => !open); setFeedback(""); }}>
        {settingsOpen ? <ArrowLeft /> : <Settings />}</button></header>
    {!settingsOpen && <nav className="fitness-tabs" aria-label="Fitness pages" role="tablist">
      {([ ["home", "Home", Home], ["activity", "Activity", Activity], ["vitals", "Vitals", HeartPulse], ["nutrition", "Nutrition", Salad] ] as const)
        .map(([id, label, Icon]) => <button type="button" key={id} role="tab" aria-selected={tab === id}
          onClick={() => setTab(id)}><Icon />{label}</button>)}
    </nav>}
    {feedback && <p className="fitness-feedback" role="status">{feedback}</p>}
    <div className="fitness-content">
      {settingsOpen ? <section className="fitness-card fitness-settings-panel">
        <span className="eyebrow">FITNESS SETTINGS</span>
        <h2>Preferences</h2>
        <div className="fitness-setting-row"><div><strong>Activity sync</strong>
          <span>Steps and health summaries come from your connected Android app.</span></div>
          <span className="fitness-sync-state">{summary.updatedAt ? "Synced" : "Waiting"}</span></div>
        <div className="fitness-setting-row"><div><strong>Theme</strong>
          <span>Olive light</span></div><span className="fitness-colour-dot" aria-hidden="true" /></div>
        <div className="fitness-entry-setting">
          <button type="button" className="fitness-private-entry" aria-label="olive icon" onClick={tapOlive}>
            <span className="fitness-olive-mark" aria-hidden="true" />
          </button>
        </div>
      </section> : <>
      {tab === "home" && <>
        <section className="fitness-hero"><p>Today’s summary</p><div className="fitness-metrics">
          <Metric value={today.steps.toLocaleString()} label="Steps" />
          <Metric value={`${today.distanceKm.toFixed(1)} km`} label="Distance" />
          <Metric value={Math.round(today.caloriesBurned).toString()} label="Active kcal" />
        </div></section>
        <section className="fitness-card"><div className="fitness-card-title"><div><span>Daily steps</span>
          <strong>{today.steps.toLocaleString()} / {summary.stepGoal.toLocaleString()}</strong></div><b>{Math.round(progress)}%</b></div>
          <div className="fitness-progress" role="progressbar" aria-label="Daily step goal" aria-valuenow={today.steps}
            aria-valuemin={0} aria-valuemax={summary.stepGoal}><span style={{ width: `${progress}%` }} /></div>
          <small>{summary.updatedAt ? `Synced ${new Date(summary.updatedAt).toLocaleString()}` : "Open the Android fitness cover to sync activity."}</small>
        </section>
      </>}
      {tab === "activity" && <section className="fitness-card"><div className="fitness-card-title"><div>
        <span>Activity history</span><strong>Last 14 days</strong></div></div>
        <h3>Steps</h3><Bars days={summary.days} field="steps" label="Steps" />
        <h3>Calories burned</h3><Bars days={summary.days} field="caloriesBurned" label="Calories burned" />
      </section>}
      {tab === "vitals" && <section className="fitness-card"><div className="fitness-card-title"><div>
        <span>Heart rate</span><strong>Wear OS history</strong></div></div>
        {summary.vitals.length ? <div className="fitness-vitals-grid">{summary.vitals.slice(-7).reverse().map((row) =>
          <article key={row.date}><time>{new Date(`${row.date}T12:00:00`).toLocaleDateString([], { month: "short", day: "numeric" })}</time>
            <strong>{row.lowestBpm}–{row.peakBpm} bpm</strong><span>Average {row.averageBpm} · Resting {row.restingBpm ?? "—"}</span></article>)}</div>
          : <div className="fitness-empty"><HeartPulse /><h2>— No data —</h2>
            <p>Connect a Wear OS watch to Android. Heart-rate history appears after the phone syncs.</p></div>}
      </section>}
      {tab === "nutrition" && <div className="fitness-nutrition-grid">
        <section className="fitness-card"><div className="fitness-card-title"><div><span>Nutrition</span>
          <strong>{Math.round(today.caloriesEaten)} / {summary.calorieGoal} kcal</strong></div></div>
          <div className="fitness-metrics light"><Metric value={`${Math.round(today.proteinGrams)} g`} label="Protein" />
            <Metric value={`${Math.round(today.carbohydrateGrams)} g`} label="Carbs" />
            <Metric value={`${Math.round(today.fatGrams)} g`} label="Fat" /></div></section>
        <section className="fitness-card"><h2>Find a food</h2>{foodSearchReady ? <Form onSubmit={async (data) => {
          setSearching(true); setFeedback("");
          try { setResults(await searchFoods(String(data.get("query")))); }
          catch (error) { setFeedback(error instanceof Error ? error.message : "Food search failed."); }
          finally { setSearching(false); }
        }}><Field label="Food or brand" name="query" minLength={2} maxLength={80} required />
          <Button type="submit" disabled={searching}><Search size={17} />{searching ? "Searching…" : "Search"}</Button></Form>
          : <p className="help-note">Food search becomes available after the website’s Firebase App Check site key is configured. Manual calorie and macro entry works now.</p>}
          {results.map((food) => <article className="fitness-food-result" key={food.id}><div><strong>{food.name}</strong>
            <span>{food.brand || food.servingDescription}</span><small>{Math.round(food.calories)} kcal · P {food.proteinGrams} g · C {food.carbohydrateGrams} g · F {food.fatGrams} g</small></div>
            <Button variant="outline" onClick={() => void add(food)}><Plus size={16} /> Add</Button></article>)}</section>
        <section className="fitness-card"><h2>Manual entry</h2><Form className="fitness-manual-form" onSubmit={async (data) => {
          await add(manualFood(data));
        }}><Field label="Food name" name="name" required maxLength={160} />
          <Field label="Calories" name="calories" type="number" min="0" max="10000" required />
          <Field label="Protein (g)" name="protein" type="number" min="0" max="1000" step="0.1" required />
          <Field label="Carbs (g)" name="carbs" type="number" min="0" max="1000" step="0.1" required />
          <Field label="Fat (g)" name="fat" type="number" min="0" max="1000" step="0.1" required />
          <Button type="submit"><Plus size={17} /> Add meal</Button></Form></section>
      </div>}
      </>}
    </div>
  </div>;
}
