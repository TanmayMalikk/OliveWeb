// Tests removal of old plaintext OCR fields from user documents.
"use strict";

const assert = require("node:assert/strict");
const test = require("node:test");
const {hasPlaintextOcr, parseArgs} = require("./scripts/purgePlaintextOcr");

test("plaintext OCR detection checks every legacy field without inspecting values", () => {
  assert.equal(hasPlaintextOcr({ocr_text: "private"}), true);
  assert.equal(hasPlaintextOcr({ocr_confidence: 0.8}), true);
  assert.equal(hasPlaintextOcr({ocr_successful: false}), true);
  assert.equal(hasPlaintextOcr({encrypted_metadata: "ciphertext"}), false);
});

test("purge is dry-run by default and requires explicit matching confirmation", () => {
  assert.deepEqual(parseArgs(["--project", "olive-test"]), {projectId: "olive-test", apply: false});
  assert.throws(
    () => parseArgs(["--project", "olive-test", "--apply", "--confirm-project", "other"]),
    /must exactly match/,
  );
  assert.deepEqual(
    parseArgs(["--project", "olive-test", "--apply", "--confirm-project", "olive-test"]),
    {projectId: "olive-test", apply: true},
  );
});
