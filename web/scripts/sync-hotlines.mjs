import { readFileSync, readdirSync, writeFileSync } from "node:fs";

const androidDirectory = new URL("../../app/src/main/assets/hotlines/provinces/", import.meta.url);
const webDirectory = new URL("../src/domain/hotline-resources.json", import.meta.url);

const provinces = readdirSync(androidDirectory).sort().map((file) => {
  const region = /^Resources([A-Z]{2})\.json$/.exec(file)?.[1];
  if (!region) throw new Error(`Unexpected Android hotline asset: ${file}`);
  const cities = JSON.parse(readFileSync(new URL(file, androidDirectory), "utf8"));
  return Object.entries(cities).flatMap(([city, groups]) =>
    Object.entries(groups).flatMap(([group, entries]) =>
      Object.entries(entries).map(([name, { Desc, Phone, url }]) => {
        if (!["Hotlines", "Shelters"].includes(group) || !/^[0-9+() -]+$/.test(Phone)) {
          throw new Error(`Invalid hotline entry: ${name}`);
        }
        const website = new URL(/^https?:\/\//i.test(url)
          ? url.replace(/^http:/i, "https:")
          : `https://${url}`);
        if (website.protocol !== "https:" || !website.hostname || website.username || website.password) {
          throw new Error(`Invalid hotline website: ${name}`);
        }
        return { region, city, group, name, description: Desc, phone: Phone, website: website.href };
      }),
    ),
  );
});
const resources = provinces.flat();
const output = `[\n${resources.map((entry) => `  ${JSON.stringify(entry)}`).join(",\n")}\n]\n`;
if (process.argv.includes("--check")) {
  if (readFileSync(webDirectory, "utf8") !== output) {
    throw new Error("Web hotline directory is out of date. Run node web/scripts/sync-hotlines.mjs.");
  }
} else {
  writeFileSync(webDirectory, output);
}
console.log(`${resources.length} hotline and shelter entries from ${provinces.length} regions.`);
