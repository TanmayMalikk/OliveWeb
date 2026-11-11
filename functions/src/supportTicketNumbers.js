// Support tickets: reserves one organization-wide number for each new ticket.
const {db} = require("./firebaseAdminClient");

const counterReference = db.collection("systemCounters").doc("supportTickets");

// The first ticket receives 0. Firestore retries this transaction if two requests arrive together.
async function reserveSupportTicketNumber(transaction) {
  const snapshot = await transaction.get(counterReference);
  const stored = snapshot.get("nextNumber");
  const nextNumber = Number.isSafeInteger(stored) && stored >= 0 ? stored : 0;
  transaction.set(counterReference, {nextNumber: nextNumber + 1}, {merge: true});
  return nextNumber;
}

module.exports = {reserveSupportTicketNumber};
