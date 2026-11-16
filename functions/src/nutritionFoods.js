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
  const barcode = request.data?.barcode;
  const page = request.data?.page ?? 0;
  if (!Number.isInteger(page) || page < 0 || page > 99) {
    throw new HttpsError("invalid-argument", "Invalid food search page.");
  }
  if (barcode !== undefined && !/^(?:[0-9]{8}|[0-9]{12,13})$/.test(String(barcode))) {
    throw new HttpsError("invalid-argument", "Enter an 8, 12 or 13 digit barcode.");
  }
  if (barcode === undefined && query.length < 2) {
    throw new HttpsError("invalid-argument", "Enter at least two characters.");
  }
  try {
    if (request.data?.action === "suggest") {
      const payload = await fatSecretRequest("food/autocomplete/v2", {expression: query, max_results: "4"});
      return {suggestions: arrayValue(payload.suggestions?.suggestion)
        .map((value) => text(value, 80)).filter(Boolean)};
    }
    if (barcode !== undefined) {
      const payload = await fatSecretRequest("food/barcode/find-by-id/v3", {
        barcode: String(barcode).padStart(13, "0"), flag_default_serving: "true",
      });
      const food = normalizeFatSecretFood(payload.food);
      return {foods: food ? [{...food, barcode: String(barcode)}] : [], hasMore: false};
    }
    const payload = await fatSecretRequest("foods/search/v6", {
      search_expression: query, max_results: "20", page_number: String(page),
      flag_default_serving: "true",
      ...(request.data?.foodType === "generic" || request.data?.foodType === "brand" ?
        {food_type: request.data.foodType} : {}),
    });
    return normalizeSearchPage(payload, page);
  } catch (error) {
    // Provider errors can contain request details; never log tokens or request headers.
    console.warn("FatSecret request failed", {code: error.providerCode || error.code || "network"});
    if (error.providerCode === 21) {
      throw new HttpsError("failed-precondition", "Food search needs server IP approval in FatSecret.");
    }
    throw new HttpsError("unavailable", "FatSecret food search is temporarily unavailable.");
  }
});

async function fatSecretRequest(path, parameters) {
  const token = await fatSecretToken();
  const response = await fetch(`https://platform.fatsecret.com/rest/${path}?${new URLSearchParams({
    ...parameters, format: "json",
  })}`, {headers: {Authorization: `Bearer ${token}`}, signal: AbortSignal.timeout(12000)});
  if (!response.ok) throw new Error(`FatSecret HTTP ${response.status}`);
  const payload = await response.json();
  if (payload.error) {
    // 211 means a valid barcode has no matching food.
    if (Number(payload.error.code) === 211 && path.includes("barcode")) return {};
    const error = new Error("FatSecret rejected request");
    error.providerCode = Number(payload.error.code);
    throw error;
  }
  return payload;
}

function normalizeSearchPage(payload, page) {
  const results = payload?.foods_search;
  if (!results || typeof results !== "object") throw new Error("Invalid food search response");
  return {
    foods: arrayValue(results.results?.food).map(normalizeFatSecretFood).filter(Boolean),
    hasMore: (page + 1) * 20 < nonNegativeNumber(results.total_results),
    page,
  };
}

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
    // Omitting scope requests the scopes enabled for this account, including Premier/barcode.
    body: "grant_type=client_credentials",
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error(`FatSecret authentication returned ${response.status}`);
  const payload = await response.json();
  if (typeof payload.access_token !== "string" || !payload.access_token) {
    throw new Error("Missing FatSecret access token");
  }
  cachedAccessToken = payload.access_token;
  cachedAccessTokenExpiry = Date.now() + Math.max(60, Number(payload.expires_in) - 60) * 1000;
  return cachedAccessToken;
}

function normalizeFatSecretFood(food) {
  const name = text(food?.food_name, 160);
  if (!name) return null;
  const servings = arrayValue(food.servings?.serving).filter((serving) =>
    serving && serving.calories !== undefined && Number.isFinite(Number(serving.calories)));
  const choices = servings.map(normalizeServing);
  const preferred = servings.findIndex((serving) => String(serving.is_default) === "1");
  const nutrition = choices[Math.max(0, preferred)] || parseFoodDescription(food.food_description);
  return {
    id: text(food.food_id, 80), name, brand: text(food.brand_name, 120),
    ...nutrition, servings: choices, barcode: "",
  };
}

function normalizeServing(serving) {
  const result = {
    servingId: text(serving.serving_id, 80),
    servingDescription: text(serving.serving_description, 180) || "1 serving",
  };
  const nutrients = {
    calories: "calories", proteinGrams: "protein", carbohydrateGrams: "carbohydrate",
    fatGrams: "fat", saturatedFatGrams: "saturated_fat", transFatGrams: "trans_fat",
    fiberGrams: "fiber", sugarGrams: "sugar", sodiumMilligrams: "sodium",
    cholesterolMilligrams: "cholesterol", potassiumMilligrams: "potassium",
  };
  for (const [key, field] of Object.entries(nutrients)) result[key] = nonNegativeNumber(serving[field]);
  return result;
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
  normalizeSearchPage,
  normalizeServing,
  cleanQuery,
  arrayValue,
  normalizeFatSecretFood,
  nonNegativeNumber,
  parseFoodDescription,
};
