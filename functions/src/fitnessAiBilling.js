const Stripe = require("stripe");
const {HttpsError} = require("firebase-functions/v2/https");
const {defineSecret} = require("firebase-functions/params");
const {db} = require("./firebaseAdminClient");

const RETURN_URL = "https://yourolivebranch.ca/get";
const OFFER = {currency: "cad", amount: 599, interval: "week"};
const stripeKey = defineSecret("FITNESS_STRIPE_SECRET_KEY");

function isWeeklyOffer(price) {
  return price?.currency === OFFER.currency && price.unit_amount === OFFER.amount &&
    price.recurring?.interval === OFFER.interval && price.recurring.interval_count === 1;
}

function fitnessBilling(secret, priceId) {
  if (!secret || !/^price_[A-Za-z0-9]+$/.test(priceId)) {
    throw new HttpsError("failed-precondition", "Fitness Coach billing is not configured.");
  }
  const stripe = new Stripe(secret, {timeout: 10_000, maxNetworkRetries: 1});
  const ref = (uid) => db.collection("fitnessAiBilling").doc(uid);
  const customerFor = async (uid) => (await ref(uid).get()).get("customerId");

  async function subscriptions(customer) {
    if (!customer) return [];
    const matches = [];
    for await (const subscription of stripe.subscriptions.list({customer, status: "all", price: priceId})) {
      if (subscription.items.data.some((item) => item.price.id === priceId && isWeeklyOffer(item.price))) {
        matches.push(subscription);
      }
    }
    return matches;
  }
  function subscriptionPeriodEndMs(plans) {
    let latestEndSeconds = 0;
    for (const plan of plans) {
      if (plan.status !== "active") continue;
      const item = plan.items?.data?.find((entry) => entry.price?.id === priceId && isWeeklyOffer(entry.price));
      const candidates = [item?.current_period_end, plan.current_period_end]
        .filter((value) => Number.isFinite(value) && value > 0);
      if (candidates.length === 0) continue;
      let endSeconds = Math.min(...candidates);
      if (Number.isFinite(plan.cancel_at) && plan.cancel_at > 0 && plan.cancel_at < endSeconds) {
        endSeconds = plan.cancel_at;
      }
      if (endSeconds > latestEndSeconds) latestEndSeconds = endSeconds;
    }
    return latestEndSeconds > 0 ? Math.trunc(latestEndSeconds * 1000) : null;
  }
  async function status(uid) {
    const customer = await customerFor(uid);
    const plans = await subscriptions(customer);
    const active = plans.some((plan) => plan.status === "active");
    const currentPeriodEndMs = active ? subscriptionPeriodEndMs(plans) : null;
    return currentPeriodEndMs === null
      ? {active, canManage: Boolean(customer)}
      : {active, canManage: Boolean(customer), currentPeriodEndMs};
  }
  async function checkPrice() {
    const price = await stripe.prices.retrieve(priceId);
    if (!price.active || !isWeeklyOffer(price)) {
      throw new HttpsError("failed-precondition", "The weekly subscription price is not configured correctly.");
    }
  }
  async function ensureCustomer(uid) {
    let customer = await customerFor(uid);
    if (!customer) {
      const created = await stripe.customers.create({}, {idempotencyKey: "olive-fitness:" + uid});
      customer = await db.runTransaction(async (tx) => {
        const current = (await tx.get(ref(uid))).get("customerId");
        if (current) return current;
        tx.set(ref(uid), {customerId: created.id});
        return created.id;
      });
    }
    return customer;
  }
  async function checkout(uid) {
    await checkPrice();
    const customer = await ensureCustomer(uid);
    const plans = await subscriptions(customer);
    if (plans.some((p) => ["active", "trialing", "past_due", "unpaid", "paused", "incomplete"].includes(p.status))) {
      throw new HttpsError("failed-precondition", "Manage the existing subscription in billing settings.");
    }
    const sessions = await stripe.checkout.sessions.list({customer, limit: 1});
    const latest = sessions.data[0];
    if (latest?.status === "open" && latest.metadata?.fitnessPrice === priceId && latest.url) {
      return latest.url;
    }
    const session = await stripe.checkout.sessions.create({
      customer, mode: "subscription",
      line_items: [{price: priceId, quantity: 1}],
      metadata: {fitnessPrice: priceId},
      success_url: RETURN_URL, cancel_url: RETURN_URL,
    }, {idempotencyKey: "olive-fitness-checkout:" + customer + ":" + priceId + ":" + (latest?.id ?? "first")});
    return session.url;
  }
  async function paymentSheet(uid, publishableKey) {
    const mode = /^(?:sk|rk)_(test|live)_/.exec(secret)?.[1];
    if (!mode || !publishableKey.startsWith("pk_" + mode + "_")) {
      throw new HttpsError("failed-precondition", "In-app payment is not configured for this Stripe mode.");
    }
    await checkPrice();
    const customer = await ensureCustomer(uid);
    // Remove open sessions issued by older Android builds before starting in-app payment.
    for await (const session of stripe.checkout.sessions.list({customer, status: "open"})) {
      if (session.metadata?.fitnessPrice !== priceId) continue;
      try {
        await stripe.checkout.sessions.expire(session.id);
      } catch (failure) {
        if (failure.code !== "checkout_session_not_open") throw failure;
      }
    }
    const plans = await subscriptions(customer);
    if (plans.some((p) => ["active", "trialing", "past_due", "unpaid", "paused"].includes(p.status))) {
      throw new HttpsError("failed-precondition", "Manage the existing subscription in billing settings.");
    }
    const pending = plans.find((p) => p.status === "incomplete");
    const expand = ["latest_invoice.confirmation_secret"];
    const subscription = pending
      ? await stripe.subscriptions.retrieve(pending.id, {expand})
      : await stripe.subscriptions.create({
        customer,
        items: [{price: priceId, quantity: 1}],
        payment_behavior: "default_incomplete",
        payment_settings: {payment_method_types: ["card"], save_default_payment_method: "on_subscription"},
        expand,
      }, {idempotencyKey: "olive-fitness-native:" + customer + ":" + priceId + ":" + (plans[0]?.id ?? "first")});
    const clientSecret = subscription.latest_invoice?.confirmation_secret?.client_secret;
    if (subscription.status !== "incomplete" || typeof clientSecret !== "string") {
      throw new HttpsError("failed-precondition", "Check your subscription before trying to pay again.");
    }
    return {publishableKey, clientSecret};
  }
  async function portal(uid) {
    const customer = await customerFor(uid);
    if (!customer) throw new HttpsError("failed-precondition", "There is no billing account yet.");
    return (await stripe.billingPortal.sessions.create({customer, return_url: RETURN_URL})).url;
  }
  return {status, checkout, paymentSheet, portal};
}

async function closeFitnessBilling(uid) {
  const ref = db.collection("fitnessAiBilling").doc(uid);
  const customer = (await ref.get()).get("customerId");
  if (customer) {
    // This Stripe customer belongs only to Fitness Coach. Deleting it cancels recurring billing.
    try {
      await new Stripe(stripeKey.value(), {timeout: 10_000}).customers.del(customer);
    } catch (error) {
      if (error.code !== "resource_missing") {
        throw new HttpsError("unavailable", "Could not close Fitness Coach billing. Retry account deletion.");
      }
    }
    await ref.delete();
  }
  await db.collection("fitnessAiLimits").doc("user-" + uid).delete();
}

module.exports = {fitnessBilling, isWeeklyOffer, stripeKey, closeFitnessBilling};
