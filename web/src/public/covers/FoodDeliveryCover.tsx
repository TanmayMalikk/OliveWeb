// Food cover users: shows the food delivery cover.
import { useEffect, useState } from "react";
import {
  Heart,
  Plus,
  Minus,
  Search,
  MapPin,
  ShoppingBag,
  ArrowRight,
  ArrowLeft,
  Star,
  Clock,
  SlidersHorizontal,
} from "lucide-react";
import menu from "../../data/FoodMenu.json";
import Checkout from "./FoodCheckoutScreen";
import { useSession } from "../../session/SessionProvider";
import {
  Branch,
  Button,
  Empty,
  Modal,
  Forward,
} from "../../components/SharedComponents";
import {
  readCover,
  writeCover,
  pendingCheckout,
} from "../../services/CoverStateStore";
type Dish = (typeof menu)[number];
type FoodState = {
  cart: Record<string, number>;
  favorites: string[];
  ingredients: Record<string, string[]>;
};
const empty: FoodState = { cart: {}, favorites: [], ingredients: {} };
const money = (cents: number) =>
  new Intl.NumberFormat("en-CA", { style: "currency", currency: "CAD" }).format(
    cents / 100,
  );
export default function Food() {
  const session = useSession();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState("All");
  const [tab, setTab] = useState("Discover");
  const [selected, setSelected] = useState<Dish | null>(null);
  const [checkout, setCheckout] = useState(false);
  const [state, setState] = useState<FoodState>(empty);
  const [loaded, setLoaded] = useState(false);
  const [storageError, setStorageError] = useState("");
  const storageId = `${session.enrollment?.uid ?? "preview"}:food`;
  useEffect(() => {
    let active = true;
    void readCover(storageId, empty)
      .then((value) => {
        if (active) {
          setState(value);
          setLoaded(true);
        }
      })
      .catch(() => {
        if (active) {
          setStorageError("Saved menu preferences could not be loaded.");
          setLoaded(true);
        }
      });
    return () => {
      active = false;
    };
  }, [storageId]);
  useEffect(() => {
    if (loaded)
      void writeCover(storageId, state).catch(() =>
        setStorageError("Unable to save menu preferences in this browser."),
      );
  }, [state, loaded, storageId]);
  const total = menu.reduce(
    (n, d) => n + d.priceCents * (state.cart[d.id] ?? 0),
    0,
  );
  const count = Object.values(state.cart).reduce((n, q) => n + q, 0);
  function quantity(id: string, delta: number) {
    setState((old) => {
      const next = Math.min(99, Math.max(0, (old.cart[id] ?? 0) + delta));
      const cart = { ...old.cart, [id]: next };
      if (next === 0) delete cart[id];
      return { ...old, cart };
    });
  }
  function favorite(id: string) {
    setState((old) => ({
      ...old,
      favorites: old.favorites.includes(id)
        ? old.favorites.filter((v) => v !== id)
        : [...old.favorites, id],
    }));
  }
  function search(value: string) {
    setQuery(value);
    void session.searchTrigger(value).then((found) => {
      if (found) setQuery("");
    });
  }
  const results = menu.filter(
    (d) =>
      (tab !== "Saved" || state.favorites.includes(d.id)) &&
      (category === "All" || d.diet === category || d.category === category) &&
      `${d.name} ${d.category} ${d.description}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  return (
    <div className="food-page">
      <div className="food-top">
        <div className="food-wordmark">
          olive<span>FROM OUR KITCHEN, WITH LOVE.</span>
        </div>
        <div className="delivery-location">
          <MapPin size={18} />
          <span>
            DELIVERING A LITTLE GOOD
            <br />
            <strong>
              {session.enrollment
                ? "Your neighbourhood"
                : "Choose your neighbourhood"}
            </strong>
          </span>
        </div>
        <Button variant="outline" onClick={() => setTab("Cart")}>
          <ShoppingBag size={18} /> Your bag{" "}
          <span className="badge">{count}</span>
        </Button>
      </div>
      <nav className="cover-tabs" aria-label="Olive sections">
        {["Discover", "Saved", "Cart"].map((t) => (
          <button
            key={t}
            aria-current={tab === t ? "page" : undefined}
            onClick={() => {
              setTab(t);
              setSelected(null);
            }}
          >
            {t}
            {t === "Cart" && count > 0 ? ` (${count})` : ""}
          </button>
        ))}
      </nav>
      {storageError && (
        <p role="status" className="form-error">
          {storageError}
        </p>
      )}
      {tab === "Cart" ? (
        <section className="cart-layout">
          <div>
            <span className="eyebrow">A LITTLE GOOD, TO GO</span>
            <h1>Your bag.</h1>
            {!count ? (
              <Empty
                title="Something delicious belongs here."
                action={
                  <Button onClick={() => setTab("Discover")}>
                    Explore the menu <ArrowRight size={17} />
                  </Button>
                }
              >
                Find your next favourite in our kitchen.
              </Empty>
            ) : (
              menu
                .filter((d) => state.cart[d.id])
                .map((d) => (
                  <div className="cart-row" key={d.id}>
                    <img src={`/images/menu_${d.id}.jpg`} alt="" />
                    <div>
                      <h3>{d.name}</h3>
                      <small>{money(d.priceCents)} each</small>
                    </div>
                    <Quantity
                      dish={d}
                      value={state.cart[d.id]}
                      change={(delta) => quantity(d.id, delta)}
                    />
                    <strong>{money(d.priceCents * state.cart[d.id])}</strong>
                  </div>
                ))
            )}
          </div>
          <aside className="order-summary panel">
            <h2>A good choice.</h2>
            <div>
              <span>Subtotal</span>
              <strong>{money(total)}</strong>
            </div>
            <p>Prepared fresh by Olive Kitchen.</p>
            <Button disabled={!count} onClick={() => setCheckout(true)}>
              <Forward>Continue to checkout</Forward>
            </Button>
            <button className="text-link" onClick={() => setTab("Discover")}>
              <ArrowLeft size={15} /> Keep exploring
            </button>
          </aside>
        </section>
      ) : (
        <>
          {tab === "Discover" && (
            <section className="food-hero">
              <div>
                <span className="eyebrow">HOMEMADE BY OLIVE KITCHEN</span>
                <h1>
                  Good food.
                  <br />
                  <em>Better together.</em>
                </h1>
                <p>
                  Fresh ingredients. Familiar favourites.
                  <br />A little something to make your day.
                </p>
                <button
                  className="text-link"
                  onClick={() =>
                    document
                      .getElementById("menu")
                      ?.scrollIntoView({ behavior: "smooth" })
                  }
                >
                  Find your favourite <ArrowRight size={18} />
                </button>
              </div>
              <div className="food-hero-image">
                <img
                  src="/images/menu_bowl.jpg"
                  alt="Garden harvest bowl with fresh vegetables"
                />
                <span className="food-stamp">
                  FRESHLY MADE
                  <br />
                  <Star size={18} />
                  <br />
                  FEEL-GOOD FOOD
                </span>
                <Branch />
              </div>
            </section>
          )}
          <section id="menu">
            <div className="menu-heading">
              <div>
                <span className="eyebrow">
                  {tab === "Saved"
                    ? "YOUR FAMILIAR FAVOURITES"
                    : "A LITTLE OF WHAT YOU LOVE"}
                </span>
                <h2>
                  {tab === "Saved"
                    ? "Saved for another day."
                    : "What sounds good?"}
                </h2>
              </div>
              <label className="search-field">
                <Search size={19} />
                <span className="sr-only">Search Olive’s menu</span>
                <input
                  value={query}
                  onChange={(e) => search(e.target.value)}
                  placeholder="Find something delicious…"
                  autoComplete="off"
                  maxLength={100}
                />
                <SlidersHorizontal size={17} />
              </label>
            </div>
            <div className="filter-row" aria-label="Filter dishes">
              {[
                "All",
                "Vegetarian",
                "Vegan",
                "Meat",
                "Bowls",
                "Pizza",
                "Burgers",
                "Wraps",
                "Pasta",
                "Salads",
                "Soups",
                "Plates",
              ].map((c) => (
                <button
                  key={c}
                  aria-pressed={category === c}
                  onClick={() => setCategory(c)}
                >
                  {c}
                </button>
              ))}
            </div>
            <div className="menu-grid">
              {results.map((d) => (
                <article className="dish-card" key={d.id}>
                  <div className="dish-photo">
                    <button
                      onClick={() => setSelected(d)}
                      aria-label={`Customize ${d.name}`}
                    >
                      <img
                        src={`/images/menu_${d.id}.jpg`}
                        alt={d.name}
                        loading="lazy"
                      />
                    </button>
                    <button
                      className={`favorite ${state.favorites.includes(d.id) ? "saved" : ""}`}
                      aria-label={`${state.favorites.includes(d.id) ? "Unsave" : "Save"} ${d.name}`}
                      aria-pressed={state.favorites.includes(d.id)}
                      onClick={() => favorite(d.id)}
                    >
                      <Heart
                        size={20}
                        fill={
                          state.favorites.includes(d.id)
                            ? "currentColor"
                            : "none"
                        }
                      />
                    </button>
                    <span className="dish-diet">{d.diet}</span>
                  </div>
                  <div className="dish-meta">
                    <span>
                      <Star size={13} />
                      {d.rating}
                    </span>
                    <span>
                      <Clock size={13} />
                      {d.minutes} min
                    </span>
                  </div>
                  <button className="dish-name" onClick={() => setSelected(d)}>
                    {d.name}
                  </button>
                  <p>{d.description}</p>
                  <div className="dish-bottom">
                    <strong>{money(d.priceCents)}</strong>
                    {state.cart[d.id] ? (
                      <Quantity
                        dish={d}
                        value={state.cart[d.id]}
                        change={(delta) => quantity(d.id, delta)}
                      />
                    ) : (
                      <button
                        className="add-dish"
                        aria-label={`Add ${d.name} to bag`}
                        onClick={() => quantity(d.id, 1)}
                      >
                        <Plus size={18} />
                      </button>
                    )}
                  </div>
                </article>
              ))}
            </div>
            {!results.length && (
              <Empty
                title={
                  tab === "Saved"
                    ? "Your favourites start here."
                    : "No dishes found."
                }
              >
                {tab === "Saved"
                  ? "Tap a heart on the menu to save a dish."
                  : "Try another search or filter."}
              </Empty>
            )}
          </section>
        </>
      )}
      {selected && (
        <Modal title={selected.name} onClose={() => setSelected(null)}>
          <img
            className="modal-food"
            src={`/images/menu_${selected.id}.jpg`}
            alt={selected.name}
          />
          <p>{selected.description}</p>
          <h3>Make it your own</h3>
          <div className="ingredient-list">
            {selected.ingredients.map((ingredient) => (
              <label key={ingredient}>
                <input
                  type="checkbox"
                  checked={(
                    state.ingredients[selected.id] ?? selected.ingredients
                  ).includes(ingredient)}
                  onChange={() =>
                    setState((old) => {
                      const values =
                        old.ingredients[selected.id] ?? selected.ingredients;
                      return {
                        ...old,
                        ingredients: {
                          ...old.ingredients,
                          [selected.id]: values.includes(ingredient)
                            ? values.filter((v) => v !== ingredient)
                            : [...values, ingredient],
                        },
                      };
                    })
                  }
                />
                {ingredient}
              </label>
            ))}
          </div>
          <Button
            onClick={() => {
              quantity(selected.id, 1);
              setSelected(null);
            }}
          >
            Add to bag · {money(selected.priceCents)} <Plus size={17} />
          </Button>
        </Modal>
      )}
      {checkout && (
        <Checkout
          total={total}
          close={() => setCheckout(false)}
          onDeclined={async () => {
            if (session.enrollment)
              await pendingCheckout(session.enrollment.uid, {
                id: crypto.randomUUID(),
                timestamp: Date.now(),
                totalCents: total,
                error: "Payment declined. No payment was taken.",
                foods: menu
                  .filter((d) => state.cart[d.id])
                  .map((d) => ({ food: d.name, quantity: state.cart[d.id] })),
              });
          }}
        />
      )}
    </div>
  );
}
function Quantity({
  dish,
  value,
  change,
}: {
  dish: Dish;
  value: number;
  change: (delta: number) => void;
}) {
  return (
    <div className="quantity">
      <button aria-label={`Remove one ${dish.name}`} onClick={() => change(-1)}>
        <Minus size={15} />
      </button>
      <span aria-live="polite">{value}</span>
      <button
        aria-label={`Add one ${dish.name}`}
        onClick={() => change(1)}
        disabled={value >= 99}
      >
        <Plus size={15} />
      </button>
    </div>
  );
}
