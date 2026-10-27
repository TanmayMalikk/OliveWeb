"use strict";

const {deleteApp, initializeApp} = require("firebase-admin/app");
const {FieldValue, getFirestore} = require("firebase-admin/firestore");

const OCR_FIELDS = ["ocr_text", "ocr_confidence", "ocr_successful"];

function valueAfter(flag, args) {
  const index = args.indexOf(flag);
  if (index < 0 || !args[index + 1] || args[index + 1].startsWith("--")) {
    throw new Error(`${flag} requires a value`);
  }
  return args[index + 1];
}

function parseArgs(args) {
  const projectId = valueAfter("--project", args);
  const apply = args.includes("--apply");
  if (apply && valueAfter("--confirm-project", args) !== projectId) {
    throw new Error("--confirm-project must exactly match --project before deletion");
  }
  return {projectId, apply};
}

function hasPlaintextOcr(data) {
  return OCR_FIELDS.some((field) => Object.prototype.hasOwnProperty.call(data, field));
}

async function purge({projectId, apply}) {
  const app = initializeApp({projectId}, `ocr-purge-${Date.now()}`);
  try {
    const firestore = getFirestore(app);
    const snapshot = await firestore.collection("files").select(...OCR_FIELDS).get();
    const affected = snapshot.docs.filter((document) => hasPlaintextOcr(document.data()));
    console.log(`Found ${affected.length} file record(s) containing plaintext OCR fields.`);
    if (!apply) {
      console.log("Dry run only. No data was changed.");
      return affected.length;
    }

    const writer = firestore.bulkWriter();
    for (const document of affected) {
      writer.update(document.ref, Object.fromEntries(
        OCR_FIELDS.map((field) => [field, FieldValue.delete()]),
      ));
    }
    await writer.close();
    console.log(`Removed plaintext OCR fields from ${affected.length} file record(s).`);
    return affected.length;
  } finally {
    await deleteApp(app);
  }
}

if (require.main === module) {
  purge(parseArgs(process.argv.slice(2))).catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

module.exports = {hasPlaintextOcr, parseArgs, purge};
