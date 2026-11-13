import { useState } from "react";
import { ExternalLink, Phone } from "lucide-react";
import { Empty } from "../../../components/SharedComponents";
import resources from "../../../domain/hotline-resources.json";
import { useSession } from "../../../session/SessionProvider";

const regions: Record<string, string> = {
  AB: "Alberta",
  BC: "British Columbia",
  MB: "Manitoba",
  NB: "New Brunswick",
  NL: "Newfoundland and Labrador",
  NS: "Nova Scotia",
  NT: "Northwest Territories",
  NU: "Nunavut",
  ON: "Ontario",
  PE: "Prince Edward Island",
  QC: "Quebec",
  SK: "Saskatchewan",
  YT: "Yukon",
};

const searchText = (value: string) =>
  value.normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().trim();

export default function HotlinesPage() {
  const { profile } = useSession();
  const [region, setRegion] = useState(() => {
    const address = searchText(profile?.address?.province || profile?.region || "");
    return Object.keys(regions).find((code) =>
      address === searchText(code) || address === searchText(regions[code]),
    ) ?? "";
  });
  const [city, setCity] = useState("");
  const [group, setGroup] = useState("");
  const [search, setSearch] = useState("");

  const regionalResources = resources.filter((resource) => !region || resource.region === region);
  const cities = [...new Set(regionalResources.map((resource) => resource.city))]
    .sort((first, second) => first.localeCompare(second));
  const term = searchText(search);
  const digits = /^[+\d() -]+$/.test(term) ? term.replace(/\D/g, "") : "";
  const visible = regionalResources.filter((resource) =>
    (!city || resource.city === city) &&
    (!group || resource.group === group) &&
    (!term || searchText([
      resource.name, resource.description, resource.city, regions[resource.region], resource.phone,
    ].join(" ")).includes(term) || (digits && resource.phone.replace(/\D/g, "").includes(digits))),
  );

  return (
    <section className="safe-feature-page">
      <div className="panel">
        <h2>Support hotlines and shelters</h2>
        <div className="toolbar-actions">
          <a className="button outline" href="tel:911"><Phone size={17} /> Emergency services (911)</a>
          <a className="button outline" href="tel:211"><Phone size={17} /> 211 Canada</a>
          <a className="button outline" href="https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html" target="_blank" rel="noreferrer">
            <ExternalLink size={17} /> Government of Canada help directory
          </a>
          <a className="button outline" href="https://sheltersafe.ca/" target="_blank" rel="noreferrer">
            <ExternalLink size={17} /> ShelterSafe
          </a>
        </div>
      </div>
      <div className="panel form-grid">
        <div className="field">
          <label htmlFor="hotlines-search">Search resources</label>
          <input id="hotlines-search" type="search" value={search} onChange={(event) => setSearch(event.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="hotlines-region">Province or territory</label>
          <select id="hotlines-region" value={region} onChange={(event) => { setRegion(event.target.value); setCity(""); }}>
            <option value="">All provinces and territories</option>
            {Object.entries(regions).map(([code, name]) => <option key={code} value={code}>{name}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="hotlines-city">City</label>
          <select id="hotlines-city" value={city} onChange={(event) => setCity(event.target.value)}>
            <option value="">All cities</option>
            {cities.map((name) => <option key={name}>{name}</option>)}
          </select>
        </div>
        <div className="field">
          <label htmlFor="hotlines-type">Resource type</label>
          <select id="hotlines-type" value={group} onChange={(event) => setGroup(event.target.value)}>
            <option value="">Hotlines and shelters</option>
            <option>Hotlines</option>
            <option>Shelters</option>
          </select>
        </div>
      </div>
      {visible.length ? (
        <div className="safe-resource-grid">
          {visible.map((resource) => (
            <article className="panel" key={JSON.stringify([resource.region, resource.city, resource.group, resource.name])}>
              <h3>{resource.name}</h3>
              <p>{resource.group === "Hotlines" ? "Hotline" : "Shelter"} · {resource.city}, {regions[resource.region]}</p>
              <p>{resource.description}</p>
              <div className="toolbar-actions">
                <a className="button outline" href={`tel:${resource.phone.replace(/[^+\d]/g, "")}`}>
                  <Phone size={17} /> {resource.phone}
                </a>
                <a className="button outline" href={resource.website} target="_blank" rel="noreferrer" aria-label={`Visit website for ${resource.name}`}>
                  <ExternalLink size={17} /> Website
                </a>
              </div>
            </article>
          ))}
        </div>
      ) : <Empty title="No matching resources">Try another city, province, or search term.</Empty>}
    </section>
  );
}
