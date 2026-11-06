// Builds clearly fictional Canadian profiles for manual multi-account testing.
const {createHash} = require("node:crypto");

const locations = [
  "Edmonton", "Calgary", "Red Deer", "Lethbridge", "Medicine Hat", "Grande Prairie",
  "Fort McMurray", "Airdrie", "Spruce Grove", "St. Albert", "Leduc", "Fort Saskatchewan",
  "Camrose", "Lloydminster", "Wetaskiwin", "Lacombe", "Sylvan Lake", "Olds", "Didsbury",
  "Cochrane", "Okotoks", "High River", "Chestermere", "Strathmore", "Brooks", "Taber",
  "Cardston", "Pincher Creek", "Canmore", "Banff", "Jasper", "Hinton", "Edson", "Whitecourt",
  "Drayton Valley", "Rocky Mountain House", "Stettler", "Ponoka", "Blackfalds", "Innisfail",
  "Beaumont", "Devon", "Morinville", "Athabasca", "Westlock", "Barrhead", "Peace River",
  "Slave Lake", "Cold Lake", "Bonnyville", "Vegreville", "Drumheller", "Hanna",
].map(city => [city, "Alberta"]);

const firstNames = [
  "Avery", "Morgan", "Riley", "Jordan", "Casey", "Taylor", "Cameron", "Quinn", "Parker",
  "Rowan", "Skyler", "Reese", "Hayden", "Emerson", "Finley", "Dakota", "Charlie", "Robin",
];
const lastNames = ["Maple", "Cedar", "Aurora", "Harbour", "Prairie", "Glacier"];
const coverTypes = ["NEWS", "FITNESS", "GAME"];
const TEST_TRIGGER = "help";

// Approximate community-centre points spread the test markers across Alberta without using home coordinates.
const coordinates = [
  [53.5461, -113.4938], [51.0447, -114.0719], [52.2681, -113.8112], [49.6956, -112.8451],
  [50.0405, -110.6765], [55.1707, -118.7947], [56.7267, -111.3803], [51.2917, -114.0144],
  [53.5450, -113.9008], [53.6305, -113.6256], [53.2647, -113.5529], [53.7125, -113.2131],
  [53.0222, -112.8286], [53.2780, -110.0050], [52.9695, -113.3761], [52.4683, -113.7360],
  [52.3083, -114.0964], [51.7928, -114.1067], [51.6619, -114.1367], [51.1894, -114.4679],
  [50.7255, -113.9749], [50.5801, -113.8719], [51.0382, -113.8425], [51.0378, -113.4000],
  [50.5642, -111.8989], [49.7870, -112.1460], [49.1997, -113.3019], [49.4867, -113.9503],
  [51.0890, -115.3597], [51.1784, -115.5708], [52.8737, -118.0814], [53.4001, -117.5857],
  [53.5835, -116.4356], [54.1425, -115.6840], [53.2222, -114.9769], [52.3744, -114.9188],
  [52.3236, -112.7047], [52.6768, -113.5815], [52.3833, -113.7932], [52.0274, -113.9508],
  [53.3520, -113.4150], [53.3635, -113.7286], [53.8021, -113.6497], [54.7190, -113.2854],
  [54.1522, -113.8510], [54.1242, -114.4021], [56.2358, -117.2894], [55.2834, -114.7690],
  [54.4643, -110.1826], [54.2668, -110.7350], [53.4937, -112.0523], [51.4636, -112.7195],
  [51.6434, -111.9250],
];

// Creates a valid Canadian-style postal code that is unique inside this fixture set.
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

// Gives every account a different fake identity and address without using real household data.
function fixture(index, username, role) {
  const [city, province] = locations[index];
  const [latitude, longitude] = coordinates[index];
  return {
    username,
    role,
    fullName: `${firstNames[index % firstNames.length]} ${lastNames[Math.floor(index / firstNames.length)]} Test`,
    dob: `${1980 + (index % 22)}-${String((index % 12) + 1).padStart(2, "0")}-${String((index % 27) + 1).padStart(2, "0")}`,
    language: index % 7 === 0 ? "French" : "English",
    region: `${city}, ${province}`,
    phone: `+1 555 010 ${String(index + 1).padStart(4, "0")}`,
    address: {
      line1: `${101 + index} Test Maple Lane`, line2: "Synthetic account",
      city, province, postalCode: postalCode(index), country: "Canada",
    },
    affectedNames: role === "USER" ? `Synthetic household ${index + 1}` : "",
    abuserName: role === "USER" ? `Fictional person ${index + 1}` : "",
    situation: role === "USER" ? "Fictional load-test profile. This does not describe a real person or emergency." : "",
    coverType: coverTypes[index % coverTypes.length],
    email: role === "STAFF" ? `${username}@yourolivebranch.ca` : "",
    latitude,
    longitude,
  };
}

const accounts = [
  ...Array.from({length: 40}, (_, index) => fixture(index, `loaduser${String(index + 1).padStart(2, "0")}`, "USER")),
  ...Array.from({length: 8}, (_, index) => fixture(index + 40, `loadstaff${String(index + 1).padStart(2, "0")}`, "STAFF")),
  ...Array.from({length: 5}, (_, index) => fixture(index + 48, `loadsafe${String(index + 1).padStart(2, "0")}`, "SAFE_CONTACT")),
];

if (accounts.length !== 53 || locations.length !== 53 || coordinates.length !== 53 ||
    new Set(accounts.map(account => account.username)).size !== 53) {
  throw new Error("The load-test fixture must contain exactly 53 unique accounts and locations.");
}

const triggerHash = createHash("sha256").update(TEST_TRIGGER.replaceAll(/\s/g, "").toLowerCase()).digest("hex");

module.exports = {accounts, triggerHash, TEST_TRIGGER};
