// Fitness cover users: loads Android activity summaries and saves browser meal logs to the same account.
import { accounts, cloudMode, firebase } from "./AccountService";

export type FitnessDay = {
  date: string; steps: number; distanceKm: number; caloriesBurned: number;
  caloriesEaten: number; proteinGrams: number; carbohydrateGrams: number; fatGrams: number;
};
export type FitnessVitals = {
  date: string; peakBpm: number; lowestBpm: number; restingBpm: number | null;
  averageBpm: number; sampleCount: number; lastUpdatedAt: number;
};
export type FitnessSummary = {
  updatedAt: number; stepGoal: number; calorieGoal: number; proteinGoal: number;
  carbohydrateGoal: number; fatGoal: number; watchConnected: boolean;
  days: FitnessDay[]; vitals: FitnessVitals[];
};
export type Food = {
  id: string; name: string; brand: string; servingDescription: string;
  calories: number; proteinGrams: number; carbohydrateGrams: number; fatGrams: number;
};

const emptySummary = (): FitnessSummary => ({
  updatedAt: 0, stepGoal: 8000, calorieGoal: 2000, proteinGoal: 120,
  carbohydrateGoal: 250, fatGoal: 65, watchConnected: false, days: [], vitals: [],
});
function safeNumber(value: unknown, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : fallback;
}
function normalize(value: Partial<FitnessSummary>): FitnessSummary {
  return {
    ...emptySummary(), ...value, updatedAt: safeNumber(value.updatedAt),
    days: Array.isArray(value.days) ? value.days.slice(-14).map((day) => ({
      date: String(day.date ?? ""), steps: Math.round(safeNumber(day.steps)),
      distanceKm: safeNumber(day.distanceKm), caloriesBurned: safeNumber(day.caloriesBurned),
      caloriesEaten: safeNumber(day.caloriesEaten), proteinGrams: safeNumber(day.proteinGrams),
      carbohydrateGrams: safeNumber(day.carbohydrateGrams), fatGrams: safeNumber(day.fatGrams),
    })) : [],
    vitals: Array.isArray(value.vitals) ? value.vitals.slice(-14).map((row) => ({
      date: String(row.date ?? ""), peakBpm: Math.round(safeNumber(row.peakBpm)),
      lowestBpm: Math.round(safeNumber(row.lowestBpm)),
      restingBpm: row.restingBpm == null ? null : Math.round(safeNumber(row.restingBpm)),
      averageBpm: Math.round(safeNumber(row.averageBpm)), sampleCount: Math.round(safeNumber(row.sampleCount)),
      lastUpdatedAt: safeNumber(row.lastUpdatedAt),
    })) : [],
  };
}
function localKey() { return `olive-fitness:${accounts.identity?.uid ?? "guest"}`; }

export async function loadFitness(): Promise<FitnessSummary> {
  const local = localStorage.getItem(localKey());
  let value = local ? normalize(JSON.parse(local)) : emptySummary();
  if (!cloudMode || !accounts.identity) return value;
  const { storage } = await firebase();
  const { getBytes, ref } = await import("firebase/storage");
  try {
    const bytes = await getBytes(ref(storage, `accounts/${accounts.identity.uid}/fitness/summary.json`), 128 * 1024);
    value = normalize(JSON.parse(new TextDecoder().decode(bytes)));
    localStorage.setItem(localKey(), JSON.stringify(value));
  } catch (error) {
    if ((error as { code?: string }).code !== "storage/object-not-found") throw error;
  }
  return value;
}
export async function saveFitness(value: FitnessSummary) {
  const normalized = normalize({ ...value, updatedAt: Date.now() });
  localStorage.setItem(localKey(), JSON.stringify(normalized));
  if (cloudMode && accounts.identity) {
    const { storage } = await firebase();
    const { ref, uploadBytes } = await import("firebase/storage");
    await uploadBytes(ref(storage, `accounts/${accounts.identity.uid}/fitness/summary.json`),
      new TextEncoder().encode(JSON.stringify(normalized)),
      { contentType: "application/json", customMetadata: { schemaVersion: "1" } });
  }
  return normalized;
}
export async function logFood(summary: FitnessSummary, food: Food, servings: number) {
  const multiplier = Math.min(20, Math.max(0.1, servings));
  const date = new Date().toISOString().slice(0, 10);
  const current: FitnessDay = summary.days.find((day) => day.date === date) ?? {
    date, steps: 0, distanceKm: 0, caloriesBurned: 0, caloriesEaten: 0,
    proteinGrams: 0, carbohydrateGrams: 0, fatGrams: 0,
  };
  const updated = {
    ...current,
    caloriesEaten: current.caloriesEaten + food.calories * multiplier,
    proteinGrams: current.proteinGrams + food.proteinGrams * multiplier,
    carbohydrateGrams: current.carbohydrateGrams + food.carbohydrateGrams * multiplier,
    fatGrams: current.fatGrams + food.fatGrams * multiplier,
  };
  return saveFitness({ ...summary, days: [...summary.days.filter((day) => day.date !== date), updated]
    .sort((a, b) => a.date.localeCompare(b.date)).slice(-14) });
}
export async function searchFoods(search: string): Promise<Food[]> {
  if (!cloudMode || !accounts.identity) throw new Error("Sign in before searching foods.");
  const query = search.trim().replace(/\s+/g, " ").slice(0, 80);
  if (query.length < 2) throw new Error("Enter at least two characters.");
  const { functions } = await firebase();
  const { httpsCallable } = await import("firebase/functions");
  const result = await httpsCallable<{ query: string }, { foods: Food[] }>(functions, "nutritionFoodSearch")({ query });
  return result.data.foods;
}
