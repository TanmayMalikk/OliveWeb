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
