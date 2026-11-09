// Builds repeatable fictional profiles for the Alberta and British Columbia load test.
const {createHash} = require("node:crypto");

const COUNTS = Object.freeze({USER: 250, SAFE_CONTACT: 100, STAFF: 10});
const TOTAL_ACCOUNTS = Object.values(COUNTS).reduce((total, count) => total + count, 0);
const TEST_TRIGGER = "help";

// Community centres are used only as starting points. Each account is moved to a repeatable
// off-centre point inside the community so the map does not stack every marker downtown.
const communities = [
  ["Edmonton", "Alberta", 53.5461, -113.4938],
  ["Calgary", "Alberta", 51.0447, -114.0719],
  ["Red Deer", "Alberta", 52.2681, -113.8112],
  ["Lethbridge", "Alberta", 49.6956, -112.8451],
  ["Medicine Hat", "Alberta", 50.0405, -110.6765],
  ["Grande Prairie", "Alberta", 55.1707, -118.7947],
  ["Fort McMurray", "Alberta", 56.7267, -111.3803],
  ["Airdrie", "Alberta", 51.2917, -114.0144],
  ["St. Albert", "Alberta", 53.6305, -113.6256],
  ["Spruce Grove", "Alberta", 53.5450, -113.9008],
  ["Leduc", "Alberta", 53.2647, -113.5529],
  ["Fort Saskatchewan", "Alberta", 53.7125, -113.2131],
  ["Camrose", "Alberta", 53.0222, -112.8286],
  ["Lloydminster", "Alberta", 53.2780, -110.0050],
  ["Wetaskiwin", "Alberta", 52.9695, -113.3761],
  ["Lacombe", "Alberta", 52.4683, -113.7360],
  ["Cochrane", "Alberta", 51.1894, -114.4679],
  ["Okotoks", "Alberta", 50.7255, -113.9749],
  ["Chestermere", "Alberta", 51.0382, -113.8425],
  ["Brooks", "Alberta", 50.5642, -111.8989],
  ["Vancouver", "British Columbia", 49.2827, -123.1207],
  ["Victoria", "British Columbia", 48.4284, -123.3656],
  ["Surrey", "British Columbia", 49.1913, -122.8490],
  ["Burnaby", "British Columbia", 49.2488, -122.9805],
  ["Richmond", "British Columbia", 49.1666, -123.1336],
  ["Coquitlam", "British Columbia", 49.2838, -122.7932],
  ["Langley", "British Columbia", 49.1044, -122.6604],
  ["Abbotsford", "British Columbia", 49.0504, -122.3045],
  ["Chilliwack", "British Columbia", 49.1579, -121.9515],
  ["Kelowna", "British Columbia", 49.8880, -119.4960],
  ["Kamloops", "British Columbia", 50.6745, -120.3273],
  ["Nanaimo", "British Columbia", 49.1659, -123.9401],
  ["Prince George", "British Columbia", 53.9171, -122.7497],
  ["Vernon", "British Columbia", 50.2670, -119.2720],
  ["Penticton", "British Columbia", 49.4991, -119.5937],
  ["Courtenay", "British Columbia", 49.6841, -124.9904],
  ["Campbell River", "British Columbia", 50.0244, -125.2475],
  ["Cranbrook", "British Columbia", 49.5120, -115.7694],
  ["Dawson Creek", "British Columbia", 55.7596, -120.2377],
  ["Fort St. John", "British Columbia", 56.2465, -120.8476],
];

const firstNames = [
  "Avery", "Morgan", "Riley", "Jordan", "Casey", "Taylor", "Cameron", "Quinn", "Parker", "Rowan",
  "Skyler", "Reese", "Hayden", "Emerson", "Finley", "Dakota", "Charlie", "Robin", "Sage", "River",
  "Alex", "Jamie", "Drew", "Kendall", "Harper", "Micah", "Blair", "Ellis", "Lane", "Shawn",
];
const lastNames = [
  "Maple", "Cedar", "Aurora", "Harbour", "Prairie", "Glacier",
  "Summit", "Willow", "Meadow", "Forest", "Brook", "Stone",
];
const coverTypes = ["FITNESS", "NEWS", "GAME"];

// Turns a stable label into a number from zero to one so reruns produce the same test data.
function fraction(label) {
  const hash = createHash("sha256").update(label).digest();
  return hash.readUInt32BE(0) / 0xffffffff;
}

// Picks a point 600 to 1,900 metres away from the listed city centre.
function offCentreCoordinates(index, city, centreLatitude, centreLongitude) {
  const angle = fraction(`angle:${index}:${city}`) * Math.PI * 2;
  const distanceMetres = 600 + fraction(`distance:${index}:${city}`) * 1_300;
  const latitude = centreLatitude + Math.sin(angle) * distanceMetres / 110_574;
  const longitude = centreLongitude + Math.cos(angle) * distanceMetres /
    (111_320 * Math.cos(centreLatitude * Math.PI / 180));
  return [Number(latitude.toFixed(6)), Number(longitude.toFixed(6))];
}

// Creates a unique Canadian-style postal code for display-only synthetic addresses.
function postalCode(index) {
  const letters = "ABCEGHJKLMNPRSTVWXYZ";
  const first = letters[index % letters.length];
  const second = Math.floor(index / letters.length) % 10;
  const third = letters[(index * 3 + 4) % letters.length];
  const fourth = (index + 3) % 10;
  const fifth = letters[(index * 5 + 7) % letters.length];
  const sixth = (index + 7) % 10;
  return `${first}${second}${third} ${fourth}${fifth}${sixth}`;
}

// Gives each generated account a unique identity, address, and off-centre map point.
function fixture(index, username, role) {
  const [city, province, centreLatitude, centreLongitude] = communities[index % communities.length];
  const [latitude, longitude] = offCentreCoordinates(index, city, centreLatitude, centreLongitude);
  const fullName = `${firstNames[index % firstNames.length]} ` +
    `${lastNames[Math.floor(index / firstNames.length) % lastNames.length]} Test`;
  return {
    username,
    role,
    fullName,
    dob: `${1980 + (index % 22)}-${String((index % 12) + 1).padStart(2, "0")}-` +
      `${String((index % 27) + 1).padStart(2, "0")}`,
    language: index % 7 === 0 ? "French" : "English",
    region: `${city}, ${province}`,
    phone: `+1 555 ${String(100 + Math.floor(index / 10)).slice(-3)} ${String(index).padStart(4, "0")}`,
    address: {
      line1: `${101 + index} Synthetic Test Lane`,
      line2: "Fictional load-test account",
      city,
      province,
      postalCode: postalCode(index),
      country: "Canada",
    },
    affectedNames: role === "USER" ? `Synthetic household ${index + 1}` : "",
    abuserName: role === "USER" ? `Fictional person ${index + 1}` : "",
    situation: role === "USER" ?
      "Fictional load-test profile. This does not describe a real person or emergency." : "",
    coverType: coverTypes[index % coverTypes.length],
    email: role === "STAFF" ? `${username}@yourolivebranch.ca` : "",
    latitude,
    longitude,
  };
}

const users = Array.from({length: COUNTS.USER}, (_, index) =>
  fixture(index, `loaduser${String(index + 1).padStart(3, "0")}`, "USER"));
const safeContacts = Array.from({length: COUNTS.SAFE_CONTACT}, (_, index) =>
  fixture(index + COUNTS.USER, `loadsafe${String(index + 1).padStart(3, "0")}`, "SAFE_CONTACT"));
const staff = Array.from({length: COUNTS.STAFF}, (_, index) =>
  fixture(index + COUNTS.USER + COUNTS.SAFE_CONTACT,
      `loadstaff${String(index + 1).padStart(2, "0")}`, "STAFF"));
const accounts = [...users, ...safeContacts, ...staff];

// Stops scripts immediately if a future edit accidentally creates duplicate fixture data.
if (accounts.length !== TOTAL_ACCOUNTS ||
    new Set(accounts.map(account => account.username)).size !== TOTAL_ACCOUNTS ||
    new Set(accounts.map(account => account.fullName)).size !== TOTAL_ACCOUNTS ||
    new Set(accounts.map(account => `${account.latitude},${account.longitude}`)).size !== TOTAL_ACCOUNTS) {
  throw new Error(`The load-test fixture must contain ${TOTAL_ACCOUNTS} unique accounts, names, and locations.`);
}

const triggerHash = createHash("sha256")
    .update(TEST_TRIGGER.replaceAll(/\s/g, "").toLowerCase())
    .digest("hex");

module.exports = {accounts, communities, COUNTS, TOTAL_ACCOUNTS, triggerHash, TEST_TRIGGER};
