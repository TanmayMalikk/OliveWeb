// Fitness cover users: keeps FatSecret credentials on the server and returns normalized food results.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");

const fatSecretClientId = defineSecret("FATSECRET_CLIENT_ID");
const fatSecretClientSecret = defineSecret("FATSECRET_CLIENT_SECRET");
let cachedAccessToken = null;
let cachedAccessTokenExpiry = 0;

exports.nutritionFoodSearch = onCall({
  secrets: [fatSecretClientId, fatSecretClientSecret],
  enforceAppCheck: true,
}, async (request) => {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in before searching foods.");
  const query = cleanQuery(request.data?.query);
  if (query.length < 2) throw new HttpsError("invalid-argument", "Enter at least two characters.");

  try {
    const token = await fatSecretToken();
    const form = new URLSearchParams({
      method: "foods.search",
      search_expression: query,
      max_results: "20",
      format: "json",
    });
    const response = await fetch("https://platform.fatsecret.com/rest/server.api", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: form,
    });
    if (!response.ok) throw new Error(`FatSecret search returned ${response.status}`);
    const payload = await response.json();
    const rawFoods = arrayValue(payload?.foods?.food);
    return {foods: rawFoods.map(normalizeFatSecretFood).filter(Boolean)};
  } catch (error) {
    console.error("FatSecret food search failed", error);
    throw new HttpsError("unavailable", "FatSecret food search is temporarily unavailable.");
  }
});

async function fatSecretToken() {
  if (cachedAccessToken && Date.now() < cachedAccessTokenExpiry) return cachedAccessToken;
  const credentials = Buffer.from(
      `${fatSecretClientId.value()}:${fatSecretClientSecret.value()}`,
  ).toString("base64");
  const response = await fetch("https://oauth.fatsecret.com/connect/token", {
    method: "POST",
    headers: {
      Authorization: `Basic ${credentials}`,
      "Content-Type": "application/x-www-form-urlencoded",
    },
    body: "grant_type=client_credentials&scope=basic",
  });
  if (!response.ok) throw new Error(`FatSecret authentication returned ${response.status}`);
  const payload = await response.json();
  cachedAccessToken = payload.access_token;
  cachedAccessTokenExpiry = Date.now() + Math.max(60, Number(payload.expires_in) - 60) * 1000;
  return cachedAccessToken;
}

function normalizeFatSecretFood(food) {
  const name = text(food?.food_name, 160);
  if (!name) return null;
  const nutrition = parseFoodDescription(food?.food_description);
  return {
    id: text(food.food_id, 80),
    name,
    brand: text(food.brand_name, 120),
    servingDescription: nutrition.servingDescription,
    calories: nutrition.calories,
    proteinGrams: nutrition.proteinGrams,
    carbohydrateGrams: nutrition.carbohydrateGrams,
    fatGrams: nutrition.fatGrams,
    barcode: "",
  };
}

function parseFoodDescription(value) {
  const description = text(value, 500);
  const servingDescription = description.match(/^Per\s+(.+?)\s+-\s+/i)?.[1] || "1 serving";
  return {
    servingDescription,
    calories: descriptionNumber(description, "Calories"),
    fatGrams: descriptionNumber(description, "Fat"),
    carbohydrateGrams: descriptionNumber(description, "Carbs"),
    proteinGrams: descriptionNumber(description, "Protein"),
  };
}

function descriptionNumber(description, label) {
  const match = description.match(new RegExp(`${label}:\\s*([0-9]+(?:\\.[0-9]+)?)`, "i"));
  return nonNegativeNumber(match?.[1]);
}

function cleanQuery(value) {
  return typeof value === "string" ? value.trim().replace(/\s+/g, " ").slice(0, 80) : "";
}

function arrayValue(value) {
  if (Array.isArray(value)) return value;
  return value ? [value] : [];
}

function text(value, limit) {
  return typeof value === "string" ? value.trim().slice(0, limit) : "";
}

function nonNegativeNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : 0;
}

exports._test = {
  cleanQuery,
  arrayValue,
  normalizeFatSecretFood,
  nonNegativeNumber,
  parseFoodDescription,
};
