// Runs before application modules. A document that loads ads never mounts the private session.
(() => {
  const bootstrap = document.currentScript;
  if (!bootstrap || bootstrap.dataset.publicAds !== "true") return;
  function redirectLegacyEntry() {
    if (location.pathname === "/" && /^[A-Za-z0-9_-]{43}$/.test(location.hash.slice(1))) {
      document.documentElement.dataset.olivePrivateRedirect = "true";
      location.replace("/entry" + location.hash);
      return true;
    }
    return false;
  }
  // Legacy bookmarks can arrive either on load or as same-document hash navigation.
  if (redirectLegacyEntry()) return;
  window.addEventListener("hashchange", redirectLegacyEntry);
  if (location.hash) return;
  document.documentElement.dataset.olivePublicAds = "true";
  if (document.querySelector("script[data-olive-adsense]")) return;
  const script = document.createElement("script");
  script.async = true;
  script.src = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-3216123362065615";
  script.crossOrigin = "anonymous";
  script.nonce = bootstrap.nonce;
  script.dataset.oliveAdsense = "true";
  document.head.appendChild(script);
})();
