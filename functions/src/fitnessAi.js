const {onCall, HttpsError} = require("firebase-functions/v2/https");
const {defineSecret, defineString} = require("firebase-functions/params");
const {db} = require("./firebaseAdminClient");
const {fitnessBilling, stripeKey} = require("./fitnessAiBilling");
const knowledge = require("./fitnessAiKnowledge");

const geminiKey = defineSecret("FITNESS_GEMINI_API_KEY");
const stripePrice = defineString("FITNESS_STRIPE_WEEKLY_PRICE_ID", {default: ""});
const stripePublishableKey = defineString("FITNESS_STRIPE_PUBLISHABLE_KEY", {default: ""});
const billingOptions = {enforceAppCheck: true, secrets: [stripeKey]};
const historyColumns = ["date", "steps", "calories", "protein", "carbohydrates", "fat"];
const goalRanges = {
  steps: [500, 100000], calories: [500, 10000], protein: [10, 1000],
  carbohydrates: [10, 2000], fat: [10, 1000], waterMl: [250, 10000],
};

function signIn(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to use Fitness Coach.");
  return request.auth.uid;
}
const billing = () => fitnessBilling(stripeKey.value(), stripePrice.value());
exports.fitnessAiBilling = onCall(billingOptions, async (request) => {
  const uid = signIn(request);
  try {
    switch (request.data?.action) {
      case "status": return await billing().status(uid);
      case "checkout": return {url: await billing().checkout(uid)};
      case "paymentSheet": return await billing().paymentSheet(uid, stripePublishableKey.value().trim());
      case "portal": return {url: await billing().portal(uid)};
      default: throw new HttpsError("invalid-argument", "Unknown billing action.");
    }
  } catch (failure) {
    if (failure instanceof HttpsError) throw failure;
    throw new HttpsError("unavailable", "Fitness Coach billing is temporarily unavailable.");
  }
});

function validatedGoals(value) {
  const output = {};
  for (const [key, [min, max]] of Object.entries(goalRanges)) {
    const n = value?.[key];
    if (Number.isInteger(n) && n >= min && n <= max) output[key] = n;
  }
  return output;
}

// Step targets can be set directly. Keep coaching suggestions for other goals modest.
function validatedGoalChanges(suggested, current) {
  return Object.fromEntries(Object.entries(validatedGoals(suggested)).filter(([name, target]) => {
    const baseline = current[name];
    return Number.isInteger(baseline) && target !== baseline &&
      (name === "steps" || Math.abs(target - baseline) <= Math.max(1, Math.floor(baseline / 10)));
  }));
}

function coachInput(data) {
  const message = typeof data?.message === "string" ? data.message.trim() : "";
  if (!message || message.length > 1200) {
    throw new HttpsError("invalid-argument", "Enter a fitness question under 1,200 characters.");
  }
  const conversation = (Array.isArray(data.conversation) ? data.conversation : []).slice(-4)
    .filter((turn) => ["user", "model"].includes(turn?.role) && typeof turn.text === "string")
    .map(({role, text}) => ({role, text: text.slice(0, 600).replace(/[\uD800-\uDBFF]$/, "")}));
  const history = (Array.isArray(data.history) ? data.history : []).slice(-14)
    .filter((day) => /^\d{4}-\d{2}-\d{2}$/.test(day?.date ?? ""))
    .map((day) => {
      const result = {date: day.date};
      for (const metric of historyColumns.slice(1)) {
        const number = day[metric];
        if (Number.isFinite(number) && number >= 0 && number <= 100_000) result[metric] = number;
      }
      return result;
    });
  return {message, conversation, history, goals: validatedGoals(data.goals)};
}

function modelInput(input) {
  const {history, ...request} = input;
  if (!history.length) return request;
  return {
    ...request,
    historyColumns,
    history: history.map((day) => historyColumns.map((column) => day[column] ?? null)),
  };
}

async function reserveRequest() {
  const day = new Date().toISOString().slice(0, 10);
  const ref = db.collection("fitnessAiLimits").doc("project-" + day);
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    const count = snapshot.get("day") === day ? snapshot.get("count") : 0;
    if (count >= 300) throw new HttpsError("resource-exhausted", "Fitness Coach reached its daily limit.");
    tx.set(ref, {day, count: count + 1});
  });
}

exports.fitnessAiCoach = onCall({enforceAppCheck: true, timeoutSeconds: 45, secrets: [stripeKey, geminiKey]}, async (request) => {
  const uid = signIn(request);
  const input = coachInput(request.data);
  try {
    if (!(await billing().status(uid)).active) {
      throw new HttpsError("permission-denied", "An active Fitness Coach subscription is required.");
    }
    await reserveRequest();
    const endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite:generateContent";
    const schema = {type: "object", properties: {
      reply: {type: "string"},
      goals: {type: "object", properties: Object.fromEntries(
        Object.keys(goalRanges).map((key) => [key, {type: ["integer", "null"]}]),
      )},
    }, required: ["reply", "goals"]};
    const response = await fetch(endpoint, {
      method: "POST", signal: AbortSignal.timeout(20_000),
      headers: {"content-type": "application/json", "x-goog-api-key": geminiKey.value()},
      body: JSON.stringify({
        systemInstruction: {parts: [{text: knowledge}]},
        contents: [{role: "user", parts: [{text: JSON.stringify(modelInput(input))}]}],
        generationConfig: {
          maxOutputTokens: 320, thinkingConfig: {thinkingLevel: "MINIMAL"},
          responseMimeType: "application/json", responseJsonSchema: schema,
        },
      }),
    });
    if (response.status === 429) throw new HttpsError("resource-exhausted", "Fitness Coach is busy. Try again shortly.");
    if (!response.ok) throw Error("Gemini request failed");
    const result = await response.json();
    const text = result.candidates?.[0]?.content?.parts?.find((part) => part.text && !part.thought)?.text;
    const output = JSON.parse(text);
    const reply = typeof output.reply === "string" ? output.reply.trim().slice(0, 1800) : "";
    if (!reply) throw Error("Empty coaching response");
    const goals = validatedGoalChanges(output.goals, input.goals);
    return {reply, goals};
  } catch (failure) {
    if (failure instanceof HttpsError) throw failure;
    throw new HttpsError("unavailable", "Fitness Coach is temporarily unavailable.");
  }
});

exports._test = {coachInput, validatedGoalChanges, modelInput};
