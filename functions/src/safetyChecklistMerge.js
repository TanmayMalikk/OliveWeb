// Three-way checklist merging keeps independent row and property edits together.
const {isDeepStrictEqual: equal} = require("node:util");

function checklist(value) {
  try {
    const data = JSON.parse(value);
    if (data?.oliveSafetyChecklist !== 1 || !Array.isArray(data.items) || data.items.length > 80) return null;
    const ids = new Set();
    for (const item of data.items) {
      if (!item || typeof item.id !== "string" || !item.id || ids.has(item.id) ||
          typeof item.text !== "string" || typeof item.checked !== "boolean") return null;
      ids.add(item.id);
    }
    return data.items;
  } catch (_) {
    return null;
  }
}

function mergeChecklist(baseValue, localValue, remoteValue) {
  const [base, local, remote] = [baseValue, localValue, remoteValue].map(checklist);
  if (!base || !local || !remote) return null;
  const original = new Map(base.map(item => [item.id, item]));
  const mine = new Map(local.map(item => [item.id, item]));
  const theirs = new Map(remote.map(item => [item.id, item]));
  const order = [...new Set([...remote.map(item => item.id), ...local.map(item => item.id)])];
  const localRows = [], remoteRows = [];
  let conflicted = false;
  for (const id of order) {
    const before = original.get(id), a = mine.get(id), b = theirs.get(id);
    let localRow, remoteRow;
    if (equal(a, before)) localRow = remoteRow = b;
    else if (equal(b, before) || equal(a, b)) localRow = remoteRow = a;
    else if (!a || !b) {
      conflicted = true;
      localRow = a;
      remoteRow = b;
    } else {
      localRow = {};
      remoteRow = {};
      for (const key of new Set([...Object.keys(before || {}), ...Object.keys(a), ...Object.keys(b)])) {
        let localProperty, remoteProperty;
        if (equal(a[key], before?.[key])) localProperty = remoteProperty = b[key];
        else if (equal(b[key], before?.[key]) || equal(a[key], b[key])) localProperty = remoteProperty = a[key];
        else if (["attachmentIds", "excludedAttachmentIds"].includes(key) &&
            Array.isArray(a[key]) && Array.isArray(b[key]) && (!before?.[key] || Array.isArray(before[key]))) {
          const old = new Set(before?.[key] || []);
          const removed = new Set([...old].filter(value => !a[key].includes(value) || !b[key].includes(value)));
          localProperty = remoteProperty = [...new Set([...b[key], ...a[key]])].filter(value => !removed.has(value));
        } else {
          conflicted = true;
          localProperty = a[key];
          remoteProperty = b[key];
        }
        if (localProperty !== undefined) localRow[key] = localProperty;
        if (remoteProperty !== undefined) remoteRow[key] = remoteProperty;
      }
    }
    if (localRow) localRows.push(localRow);
    if (remoteRow) remoteRows.push(remoteRow);
  }
  const encode = items => JSON.stringify({oliveSafetyChecklist: 1, items});
  const result = {localValue: encode(localRows), remoteValue: encode(remoteRows), conflicted};
  if (localRows.length > 80 || remoteRows.length > 80 || result.localValue.length > 4000 || result.remoteValue.length > 4000) return null;
  return result;
}

module.exports = {mergeChecklist};
