// Mapbox renders the published style below Leaflet's private markers and route overlays.
window.oliveLoadBasemap = function (leaflet) {
  function showError() { document.title = 'olive-map-error'; }
  const token = new URLSearchParams(location.hash.slice(1)).get('mapbox');
  if (!token || !token.startsWith('pk.')) {
    showError();
    return;
  }

  const stylesheet = document.createElement('link');
  stylesheet.rel = 'stylesheet';
  stylesheet.href = 'mapbox-gl.css';
  stylesheet.onerror = showError;
  stylesheet.onload = () => {
    const script = document.createElement('script');
    script.src = 'mapbox-gl.js';
    script.onload = start;
    script.onerror = showError;
    document.head.append(script);
  };
  document.head.append(stylesheet);

  function start() {
    if (!window.mapboxgl?.supported()) {
      showError();
      return;
    }

    const container = document.createElement('div');
    container.setAttribute('aria-hidden', 'true');
    Object.assign(container.style, {
      position: 'absolute',
      inset: '0',
      zIndex: '1',
      visibility: 'hidden',
      pointerEvents: 'none',
    });
    leaflet.getContainer().prepend(container);

    let vector;
    function camera() {
      const center = leaflet.getCenter();
      return {center: [center.lng, center.lat], zoom: Math.max(0, leaflet.getZoom() - 1)};
    }
    function sync() { vector.jumpTo(camera()); }
    function resize() { vector.resize(); sync(); }
    try {
      vector = new window.mapboxgl.Map({
        ...camera(),
        container,
        accessToken: token,
        style: 'mapbox://styles/tanmaymalik/cmwn3afx4002m01sr80u4bzdl',
        projection: 'mercator',
        interactive: false,
        attributionControl: false,
        performanceMetricsCollection: false,
      });
    } catch (_) {
      container.remove();
      showError();
      return;
    }
    const credit = '<a href="https://www.mapbox.com/about/maps/">© Mapbox</a> · ' +
      '<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap</a> · ' +
      '<a href="https://apps.mapbox.com/feedback/">Improve this map</a>';
    const timeout = setTimeout(reportUnavailable, 14000);
    function reportUnavailable() {
      clearTimeout(timeout);
      leaflet.off('move zoom', sync);
      leaflet.off('resize', resize);
      leaflet.attributionControl.removeAttribution(credit);
      vector.remove();
      container.remove();
      showError();
    }
    leaflet.on('move zoom', sync);
    leaflet.on('resize', resize);
    vector.once('webglcontextlost', reportUnavailable);
    vector.once('idle', () => {
      clearTimeout(timeout);
      leaflet.attributionControl.addAttribution(credit);
      container.style.visibility = 'visible';
      document.title = 'olive-map-ready';
    });
  }
};
