// Tests the FatSecret response cleanup without making network requests.
const test = require("node:test");
const assert = require("node:assert/strict");
const {cleanQuery, normalizeFatSecretFood, nonNegativeNumber, parseFoodDescription} =
  require("./src/nutritionFoods")._test;

test("food queries are bounded and repeated spaces are removed", () => {
  assert.equal(cleanQuery("  maple   oatmeal  "), "maple oatmeal");
  assert.equal(cleanQuery(42), "");
  assert.equal(cleanQuery("x".repeat(100)).length, 80);
});

test("FatSecret food results are normalized to one safe serving", () => {
  const result = normalizeFatSecretFood({
    food_id: "123",
    food_name: "Oatmeal",
    brand_name: "Example",
    food_description: "Per 1 bowl - Calories: 180kcal | Fat: 3g | Carbs: 32g | Protein: 6.5g",
  });
  assert.equal(result.name, "Oatmeal");
  assert.equal(result.servingDescription, "1 bowl");
  assert.equal(result.calories, 180);
  assert.equal(result.proteinGrams, 6.5);
});

test("Basic FatSecret descriptions expose serving calories and all macros", () => {
  const result = parseFoodDescription(
      "Per 100g - Calories: 22kcal | Fat: 0.34g | Carbs: 3.28g | Protein: 3.09g",
  );
  assert.deepEqual(result, {
    servingDescription: "100g",
    calories: 22,
    fatGrams: 0.34,
    carbohydrateGrams: 3.28,
    proteinGrams: 3.09,
  });
});

test("Malformed Basic descriptions produce safe zero nutrition", () => {
  assert.deepEqual(parseFoodDescription("unknown"), {
    servingDescription: "1 serving",
    calories: 0,
    fatGrams: 0,
    carbohydrateGrams: 0,
    proteinGrams: 0,
  });
});

test("invalid nutrient values cannot become negative or non-finite", () => {
  assert.equal(nonNegativeNumber(-4), 0);
  assert.equal(nonNegativeNumber("not a number"), 0);
  assert.equal(nonNegativeNumber("12.5"), 12.5);
});
const {normalizeSearchPage, normalizeServing} = require('./src/nutritionFoods')._test;

test('Premier results preserve serving nutrients and the provider default', () => {
  const result = normalizeFatSecretFood({food_id: '12', food_name: 'Oats', servings: {serving: [
    {serving_id: 'a', serving_description: '100 g', calories: '400', protein: '12', sodium: '5'},
    {serving_id: 'b', serving_description: '1 cup', calories: '150', protein: '4', fiber: '3', is_default: '1'},
  ]}});
  assert.equal(result.calories, 150);
  assert.equal(result.fiberGrams, 3);
  assert.equal(result.servings[0].calories, 400);
  assert.equal(result.servings[0].sodiumMilligrams, 5);
  assert.equal(result.servings[1].servingDescription, '1 cup');
});

test('Premier search handles singleton foods, empty results and final pages', () => {
  assert.equal(normalizeSearchPage({foods_search: {total_results: '21', results: {
    food: {food_id: '1', food_name: 'Apple'},
  }}}, 0).hasMore, true);
  assert.equal(normalizeSearchPage({foods_search: {total_results: '21', results: {}}}, 1).hasMore, false);
  assert.deepEqual(normalizeSearchPage({foods_search: {total_results: '0'}}, 0).foods, []);
  assert.throws(() => normalizeSearchPage({error: {code: 21}}, 0));
});

test('Detailed nutrients reject invalid values and preserve milligram units', () => {
  const result = normalizeServing({calories: 'Infinity', sugar: '-1', potassium: '123.4', sodium: '12'});
  assert.equal(result.calories, 0);
  assert.equal(result.sugarGrams, 0);
  assert.equal(result.potassiumMilligrams, 123.4);
  assert.equal(result.sodiumMilligrams, 12);
});

test('Callable requests Premier scopes and detects HTTP-200 IP rejection', async (t) => {
  const originalFetch = global.fetch;
  const oldId = process.env.FATSECRET_CLIENT_ID;
  const oldSecret = process.env.FATSECRET_CLIENT_SECRET;
  process.env.FATSECRET_CLIENT_ID = 'test-id';
  process.env.FATSECRET_CLIENT_SECRET = 'test-secret';
  t.after(() => {
    global.fetch = originalFetch;
    if (oldId === undefined) delete process.env.FATSECRET_CLIENT_ID;
    else process.env.FATSECRET_CLIENT_ID = oldId;
    if (oldSecret === undefined) delete process.env.FATSECRET_CLIENT_SECRET;
    else process.env.FATSECRET_CLIENT_SECRET = oldSecret;
  });
  const calls = [];
  global.fetch = async (url, options) => {
    calls.push({url: String(url), body: options.body});
    return {ok: true, json: async () => calls.length === 1 ?
      {access_token: 'fake-token', expires_in: 3600} : {error: {code: 21, message: 'Invalid IP'}}};
  };
  const handler = require('./src/nutritionFoods').nutritionFoodSearch.run;
  await assert.rejects(handler({auth: {uid: 'test'}, data: {query: 'apple'}}),
    {code: 'failed-precondition'});
  assert.equal(calls[0].body, 'grant_type=client_credentials');
  assert.match(calls[1].url, /foods\/search\/v6/);
  assert.match(calls[1].url, /page_number=0/);
  await assert.rejects(handler({auth: null, data: {query: 'apple'}}), {code: 'unauthenticated'});
  await assert.rejects(handler({auth: {uid: 'test'}, data: {barcode: '123'}}), {code: 'invalid-argument'});
  await assert.rejects(handler({auth: {uid: 'test'}, data: {query: 'apple', page: 100}}), {code: 'invalid-argument'});

  global.fetch = async (url) => {
    assert.match(String(url), /food\/autocomplete\/v2/);
    return {ok: true, json: async () => ({suggestions: {suggestion: ['apple', 'apple pie']}})};
  };
  assert.deepEqual((await handler({auth: {uid: 'test'}, data: {query: 'app', action: 'suggest'}})).suggestions,
    ['apple', 'apple pie']);
  global.fetch = async (url) => {
    assert.match(String(url), /barcode\/find-by-id\/v3/);
    assert.match(String(url), /barcode=0012345678905/);
    return {ok: true, json: async () => ({food: {food_id: '2', food_name: 'Cereal',
      servings: {serving: {serving_id: '3', serving_description: '1 bowl', calories: '130'}}}})};
  };
  const found = await handler({auth: {uid: 'test'}, data: {barcode: '012345678905'}});
  assert.equal(found.foods[0].calories, 130);
  assert.equal(found.foods[0].barcode, '012345678905');
  global.fetch = async () => ({ok: true, json: async () => ({error: {code: 211}})});
  assert.deepEqual((await handler({auth: {uid: 'test'}, data: {barcode: '012345678905'}})).foods, []);
  await assert.rejects(handler({auth: {uid: 'test'}, data: {action: 'detail', foodId: '2:3'}}),
    {code: 'invalid-argument'});
  global.fetch = async (url) => {
    const detail = new URL(url);
    assert.equal(detail.pathname, '/rest/food/v6');
    assert.equal(detail.searchParams.get('food_id'), '2');
    return {ok: true, json: async () => ({food: {food_id: '2', food_name: 'Cereal',
      servings: {serving: {serving_id: '3', serving_description: '1 bowl', calories: '130'}}}})};
  };
  const detail = await handler({auth: {uid: 'test'}, data: {action: 'detail', foodId: '2'}});
  assert.equal(detail.foods[0].servings[0].servingId, '3');
});
