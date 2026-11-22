
const test = require("node:test");
const assert = require("node:assert/strict");
const fixture = require("./test-support/runtime.cjs");

test("coach payload accepts only cover aggregate fields and bounds conversation and history", () => {
  const {coachInput, modelInput} = fixture().load("fitnessAi")._test;
  const input = coachInput({
    message: " How consistent am I? ",
    emergencyContacts: ["fixture"],
    profile: {name: "Fixture Account"},
    goals: {steps: 7500, calories: 2200, waterMl: -10, weightKg: 80},
    history: Array.from({length: 16}, (_, n) => ({
      date: new Date(Date.UTC(2026, 0, n + 1)).toISOString().slice(0, 10),
      steps: 5000, calories: 1900, location: "fixture", heartRate: 60,
      foodNames: n === 2 ? [" Oats ", "Oats", "", {note: "private"}] : undefined,
    })),
    conversation: Array.from({length: 10}, (_, index) => ({
      role: "user", text: index === 9 ? "x".repeat(599) + "🌿" : `Turn ${index}`, private: true,
    })),
  });
  assert.deepEqual(Object.keys(input), ["message", "conversation", "history", "goals"]);
  assert.deepEqual(input.goals, {steps: 7500, calories: 2200});
  assert.equal(input.history.length, 14);
  assert.deepEqual(input.history[0], {date: "2026-01-03", steps: 5000, calories: 1900, foodNames: ["Oats"]});
  assert.equal(input.conversation.length, 5);
  assert.deepEqual(input.conversation[0], {role: "user", text: "Turn 5"});
  assert.equal(input.conversation.at(-1).text, "x".repeat(599));
  const compact = modelInput(input);
  assert.deepEqual(compact.historyColumns, ["date", "steps", "calories", "protein", "carbohydrates", "fat", "foodNames"]);
  assert.deepEqual(compact.history[0], ["2026-01-03", 5000, 1900, null, null, null, ["Oats"]]);
  assert.equal(compact.history[1].at(-1), null);
  assert.ok(JSON.stringify(compact).length < JSON.stringify(input).length);
});

test("coach refuses requests before touching Gemini if an account has no subscription", async () => {
  const queried = [];
  const api = fixture({"./fitnessAiBilling": {
    fitnessBilling: () => ({status: async (uid) => {queried.push(uid); return {active: false};}}),
    stripeKey: {value: () => "fixture"},
  }}).load("fitnessAi");
  await assert.rejects(api.fitnessAiCoach({data: {message: "Hi"}}), {code: "unauthenticated"});
  await assert.rejects(api.fitnessAiCoach({auth: {uid: "owner"}, data: {message: "Hi", uid: "other"}}),
    {code: "permission-denied"});
  assert.deepEqual(queried, ["owner"]);
});

test("coach ignores the former account limit and enforces the project limit", async (t) => {
  const f = fixture({
    "firebase-functions/params": {
      defineSecret: () => ({value: () => "fixture"}),
      defineString: (_name, {default: value}) => ({value: () => value}),
    },
    "./fitnessAiBilling": {
      fitnessBilling: () => ({status: async () => ({active: true})}),
      stripeKey: {value: () => "fixture"},
    },
  });
  const day = new Date().toISOString().slice(0, 10);
  const accountCounter = "fitnessAiLimits/user-owner";
  const projectCounter = "fitnessAiLimits/project-" + day;
  f.records.set(accountCounter, {day, count: 12});
  f.records.set(projectCounter, {day, count: 299});
  const fetch = t.mock.method(globalThis, "fetch", async () => ({
    ok: true,
    json: async () => ({candidates: [{content: {parts: [{
      text: '{"reply":"The target is 2,000 steps.","goals":{"steps":2000}}',
    }]}}]}),
  }));
  const {fitnessAiCoach} = f.load("fitnessAi");
  const request = {auth: {uid: "owner"}, data: {message: "Set my step goal to 2,000.", goals: {steps: 8000}}};

  assert.deepEqual(await fitnessAiCoach(request), {reply: "The target is 2,000 steps.", goals: {steps: 2000}});
  assert.equal(f.records.get(accountCounter).count, 12);
  assert.equal(f.records.get(projectCounter).count, 300);
  await assert.rejects(fitnessAiCoach(request), {code: "resource-exhausted"});
  assert.equal(fetch.mock.callCount(), 1);
});

test("billing accepts only a CAD 5.99 weekly price", () => {
  const {isWeeklyOffer} = fixture().load("fitnessAiBilling");
  const price = {currency: "cad", unit_amount: 599, recurring: {interval: "week", interval_count: 1}};
  assert.equal(isWeeklyOffer(price), true);
  assert.equal(isWeeklyOffer({...price, currency: "usd"}), false);
  assert.equal(isWeeklyOffer({...price, recurring: {interval: "month", interval_count: 1}}), false);
  assert.equal(isWeeklyOffer({...price, unit_amount: 499}), false);
});

test("unsafe jumps and suggestions without a current target are discarded", () => {
  const {validatedGoalChanges} = fixture().load("fitnessAi")._test;
  assert.deepEqual(
    validatedGoalChanges({steps: 7700, calories: 500, protein: 100, waterMl: 2000},
      {steps: 7500, calories: 2200, waterMl: 2000}),
    {steps: 7700},
  );
});

test("Stripe status recognizes only the configured plan and portal access uses the bound customer", async () => {
  const calls = [];
  const price = {id: "price_weekly", currency: "cad", unit_amount: 599, recurring: {interval: "week", interval_count: 1}};
  let plans = [{status: "past_due", items: {data: [{price}]}},
    {status: "active", items: {data: [{price: {...price, id: "price_unrelated"}}]}}];
  const f = fixture({stripe: class {
    subscriptions = {list: ({customer}) => {calls.push(customer); return plans;}};
    billingPortal = {sessions: {create: async ({customer}) => {
      calls.push(customer);
      return {url: "https://billing.stripe.com/fixture"};
    }}};
  }});
  f.records.set("fitnessAiBilling/member", {customerId: "cus_member"});
  const billing = f.load("fitnessAiBilling").fitnessBilling("fixture", price.id);
  assert.deepEqual(await billing.status("member"), {active: false, canManage: true});
  plans = [{status: "active", items: {data: [{price}]}}];
  assert.deepEqual(await billing.status("member"), {active: true, canManage: true});
  assert.equal(await billing.portal("member"), "https://billing.stripe.com/fixture");
  await assert.rejects(billing.portal("another_account"), {code: "failed-precondition"});
  assert.deepEqual(calls, ["cus_member", "cus_member", "cus_member"]);
});

test("in-app checkout resumes the unpaid subscription and does not grant access until it is active", async () => {
  const price = {id: "price_weekly", active: true, currency: "cad", unit_amount: 599,
    recurring: {interval: "week", interval_count: 1}};
  const calls = [];
  let plans = [];
  const invoice = {confirmation_secret: {client_secret: "pi_fixture_secret_test"}};
  const f = fixture({stripe: class {
    prices = {retrieve: async () => price};
    checkout = {sessions: {
      list: () => [{id: "cs_legacy", metadata: {fitnessPrice: price.id}}],
      expire: async (id) => {calls.push(["expire", id]);},
    }};
    subscriptions = {
      list: () => plans,
      create: async (params) => {
        calls.push(["create", params]);
        const pending = {id: "sub_pending", status: "incomplete", items: {data: [{price}]}, latest_invoice: invoice};
        plans = [pending];
        return pending;
      },
      retrieve: async (id) => {calls.push(["retrieve", id]); return plans[0];},
    };
  }});
  f.records.set("fitnessAiBilling/member", {customerId: "cus_member"});
  const billing = f.load("fitnessAiBilling").fitnessBilling("sk_test_fixture", price.id);
  await assert.rejects(billing.paymentSheet("member", "pk_live_fixture"), {code: "failed-precondition"});
  assert.deepEqual(await billing.paymentSheet("member", "pk_test_fixture"), {
    publishableKey: "pk_test_fixture", clientSecret: "pi_fixture_secret_test",
  });
  assert.deepEqual(calls[0], ["expire", "cs_legacy"]);
  assert.equal(calls[1][1].customer, "cus_member");
  assert.equal(calls[1][1].payment_behavior, "default_incomplete");
  assert.equal(calls[1][1].payment_settings.save_default_payment_method, "on_subscription");
  assert.equal((await billing.status("member")).active, false);
  await billing.paymentSheet("member", "pk_test_fixture");
  assert.equal(calls.filter(([action]) => action === "create").length, 1);
  assert.deepEqual(calls.at(-1), ["retrieve", "sub_pending"]);
  plans[0].status = "active";
  assert.equal((await billing.status("member")).active, true);
  await assert.rejects(billing.paymentSheet("member", "pk_test_fixture"), {code: "failed-precondition"});
});

test("Stripe status returns currentPeriodEndMs from item or plan period end capped by cancel_at", async () => {
  const price = {id: "price_weekly", currency: "cad", unit_amount: 599, recurring: {interval: "week", interval_count: 1}};
  let plans = [];
  const f = fixture({stripe: class {
    subscriptions = {list: () => plans};
  }});
  f.records.set("fitnessAiBilling/member", {customerId: "cus_member"});
  const billing = f.load("fitnessAiBilling").fitnessBilling("fixture", price.id);

  // Inactive plans omit currentPeriodEndMs.
  plans = [{status: "canceled", current_period_end: 2_000_000_000, items: {data: [{price, current_period_end: 2_000_000_000}]}}];
  assert.deepEqual(await billing.status("member"), {active: false, canManage: true});

  // Stripe v23 item-level current_period_end is converted to milliseconds.
  plans = [{status: "active", items: {data: [{price, current_period_end: 1_800_000_000}]}}];
  assert.deepEqual(await billing.status("member"), {active: true, canManage: true, currentPeriodEndMs: 1_800_000_000_000});

  // Falls back to top-level plan.current_period_end and caps by earlier cancel_at; picks latest across active plans.
  plans = [
    {status: "active", current_period_end: 1_800_001_000, cancel_at: 1_800_000_500, items: {data: [{price}]}},
    {status: "active", cancel_at: 1_800_002_000, items: {data: [{price, current_period_end: 1_800_001_200}]}},
  ];
  assert.deepEqual(await billing.status("member"), {active: true, canManage: true, currentPeriodEndMs: 1_800_001_200_000});
});
