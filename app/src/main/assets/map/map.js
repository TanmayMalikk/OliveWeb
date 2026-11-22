// Safe-app users and safe contacts: Google-Maps-style native 60fps Mapbox GL v3 map engine.
const mapEl = document.getElementById('map');
const token = new URLSearchParams(location.hash.slice(1)).get('mapbox');
const EARTH_RADIUS_M = 6371000;

let mbMap = null;
let mapReady = false;
let styleLoaded = false;
let lastResizeHeight = 0;

let points = [];
const markers = Object.create(null);
const markerAnimations = Object.create(null);
const markerTargets = Object.create(null);
const markerCoords = Object.create(null);
const trails = Object.create(null);
const supportMarkers = Object.create(null);

let followEnabled = false;
let followUid = null;
let followFocusPending = false;
let headingUp = false;
let navigatingActive = false;
let userHeadingDeg = null;
let lastReportedBearing = 0;
let cameraLoopFrame = 0;

let selectionMarker = null;
let selectionEnabled = false;
let detailsOnlyForSelectedPoint = false;
let selectedPointUid = null;

let pendingPointsCall = null;
let pendingServicesCall = null;
let pendingRouteCall = null;
let pendingSelectionCall = null;
let pendingFollowCall = null;

function reportError() {
  document.title = 'olive-map-error';
}

function haversineMeters(lat1, lng1, lat2, lng2) {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLng = (lng2 - lng1) * rad;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2;
  return EARTH_RADIUS_M * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
}

function toLatLngObj(v) {
  if (!v) return { lat: 0, lng: 0 };
  if (Array.isArray(v)) return { lat: v[0], lng: v[1] };
  return { lat: v.lat, lng: v.lng };
}

function normalizeDeg(deg) {
  if (!Number.isFinite(deg)) return 0;
  return ((deg % 360) + 360) % 360;
}

function shortestDelta(fromDeg, toDeg) {
  let d = (normalizeDeg(toDeg) - normalizeDeg(fromDeg)) % 360;
  if (d > 180) d -= 360;
  if (d <= -180) d += 360;
  return d;
}

// Preserved map helper surface used by Android instrumentation tests.
const map = {
  getCenter() {
    if (!mbMap) return { lat: 53.5461, lng: -113.4938 };
    const c = mbMap.getCenter();
    return { lat: c.lat, lng: c.lng };
  },
  distance(a, b) {
    const p1 = toLatLngObj(a);
    const p2 = toLatLngObj(b);
    return haversineMeters(p1.lat, p1.lng, p2.lat, p2.lng);
  }
};

function updateOwnHeadingCones() {
  const currentBearing = mbMap ? mbMap.getBearing() : 0;
  points.forEach(point => {
    if (!point.own) return;
    const uid = point.uid || point.name;
    const marker = markers[uid];
    if (!marker) return;
    const el = marker.getElement();
    const cone = el?.querySelector('.olive-heading-cone');
    if (!cone) return;
    if (userHeadingDeg === null || !Number.isFinite(userHeadingDeg)) {
      cone.style.display = 'none';
    } else {
      cone.style.display = 'block';
      const rel = userHeadingDeg - currentBearing;
      cone.style.transform = 'rotate(' + rel.toFixed(1) + 'deg)';
    }
  });
}

function updateHeadingCameraLoop() {
  cameraLoopFrame = 0;
  if (!mbMap || !headingUp) return;
  if (mbMap.isZooming && mbMap.isZooming()) {
    cameraLoopFrame = requestAnimationFrame(updateHeadingCameraLoop);
    return;
  }
  const curBearing = mbMap.getBearing();
  const targetBearing = userHeadingDeg !== null && Number.isFinite(userHeadingDeg) ? userHeadingDeg : curBearing;
  const bDelta = shortestDelta(curBearing, targetBearing);
  const bearingMoving = Math.abs(bDelta) >= 0.15;
  const nextBearing = bearingMoving ? curBearing + bDelta * 0.2 : targetBearing;

  const curPitch = mbMap.getPitch();
  const targetPitch = navigatingActive ? 42 : 0;
  const pitchMoving = Math.abs(targetPitch - curPitch) >= 0.25;
  const nextPitch = pitchMoving ? curPitch + (targetPitch - curPitch) * 0.16 : targetPitch;

  let centerMoved = false;
  let nextCenter = null;
  if (followEnabled && followUid && markerCoords[followUid]) {
    const pos = markerCoords[followUid];
    const c = mbMap.getCenter();
    if (haversineMeters(c.lat, c.lng, pos.lat, pos.lng) >= 0.4) {
      nextCenter = [pos.lng, pos.lat];
      centerMoved = true;
    }
  }

  let nextZoom = null;
  if (followEnabled && followFocusPending) {
    const z = mbMap.getZoom();
    const targetZ = navigatingActive ? 16.5 : Math.max(z, 15.5);
    if (Math.abs(targetZ - z) < 0.05) {
      followFocusPending = false;
    } else {
      nextZoom = z + (targetZ - z) * 0.18;
    }
  }

  if (bearingMoving || pitchMoving || centerMoved || nextZoom !== null) {
    const jumpOpts = { bearing: nextBearing, pitch: nextPitch };
    if (nextCenter) jumpOpts.center = nextCenter;
    if (nextZoom !== null) jumpOpts.zoom = nextZoom;
    mbMap.jumpTo(jumpOpts);
    updateOwnHeadingCones();
  }

  const markerAnimating = !!(followEnabled && followUid && markerAnimations[followUid]);
  if (headingUp && (bearingMoving || pitchMoving || followFocusPending || markerAnimating)) {
    cameraLoopFrame = requestAnimationFrame(updateHeadingCameraLoop);
  }
}

function startHeadingCameraLoop() {
  if (!cameraLoopFrame && headingUp && mbMap) {
    cameraLoopFrame = requestAnimationFrame(updateHeadingCameraLoop);
  }
}

function stopHeadingCameraLoop() {
  if (cameraLoopFrame) {
    cancelAnimationFrame(cameraLoopFrame);
    cameraLoopFrame = 0;
  }
}

function markerDotClass(point) {
  return point.blue
    ? 'olive-own-live-dot'
    : (point.live ? 'olive-live-dot' : (point.lastKnown ? 'olive-last-known-dot' : 'olive-contact-dot'));
}

function markerStyleKey(point) {
  return (point.own ? 'own:' : '') +
    (point.blue ? 'blue' : (point.live ? 'live' : (point.lastKnown ? 'last-known' : 'contact')));
}

function markerSizePx(point) {
  const closeSize = (point.live || point.lastKnown) ? 26 : 24;
  const zoom = mbMap ? mbMap.getZoom() : 12;
  const zoomProgress = Math.max(0, Math.min(1, (zoom - 3) / 10));
  return Math.round(16 + (closeSize - 16) * zoomProgress);
}

function styleDotElement(dot, point) {
  const size = markerSizePx(point);
  const border = size >= 20 ? 3 : 2;
  dot.className = markerDotClass(point);
  dot.style.width = size + 'px';
  dot.style.height = size + 'px';
  dot.style.border = border + 'px solid #fff';
}

function createMarkerElement(point) {
  const wrap = document.createElement('div');
  wrap.className = 'olive-live-marker';
  const dot = document.createElement('div');
  styleDotElement(dot, point);
  wrap.appendChild(dot); // Keep dot as firstChild for LocationMapMotionUiTest
  if (point.own) {
    const cone = document.createElement('div');
    cone.className = 'olive-heading-cone';
    cone.innerHTML =
      '<svg viewBox="0 0 80 80" aria-hidden="true">' +
      '<defs><radialGradient id="oliveConeGrad" cx="50%" cy="50%" r="50%">' +
      '<stop offset="0%" stop-color="rgba(26,115,232,0.48)"/>' +
      '<stop offset="75%" stop-color="rgba(26,115,232,0.16)"/>' +
      '<stop offset="100%" stop-color="rgba(26,115,232,0)"/></radialGradient></defs>' +
      '<path d="M40 40 L16 6 A38 38 0 0 1 64 6 Z" fill="url(#oliveConeGrad)"/></svg>';
    wrap.appendChild(cone);
  }
  return wrap;
}

function buildAccuracyGeoJson(tracked) {
  if (!tracked || !tracked.accuracy || tracked.accuracy <= 0) {
    return { type: 'FeatureCollection', features: [] };
  }
  const steps = 64;
  const lat = tracked.latitude;
  const lng = tracked.longitude;
  const radiusM = tracked.accuracy;
  const dLat = (radiusM / EARTH_RADIUS_M) * (180 / Math.PI);
  const dLng = dLat / Math.max(0.05, Math.cos(lat * Math.PI / 180));
  const coords = [];
  for (let i = 0; i <= steps; i++) {
    const theta = (i / steps) * 2 * Math.PI;
    coords.push([lng + dLng * Math.cos(theta), lat + dLat * Math.sin(theta)]);
  }
  const color = tracked.blue ? '#1a73e8' : '#c52b25';
  return {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      properties: { color: color },
      geometry: { type: 'Polygon', coordinates: [coords] }
    }]
  };
}

function animateMarkerTo(uid, marker, targetLat, targetLng, duration) {
  if (markerAnimations[uid]) cancelAnimationFrame(markerAnimations[uid]);
  const start = marker.getLngLat();
  const began = performance.now();
  function frame(now) {
    const progress = Math.min(1, (now - began) / duration);
    const eased = 1 - Math.pow(1 - progress, 3);
    const lat = start.lat + (targetLat - start.lat) * eased;
    const lng = start.lng + (targetLng - start.lng) * eased;
    marker.setLngLat([lng, lat]);
    markerCoords[uid] = { lat: lat, lng: lng };
    if (followEnabled && followUid === uid && mbMap) {
      if (headingUp) startHeadingCameraLoop();
      else mbMap.jumpTo({ center: [lng, lat] });
    }
    if (progress < 1) {
      markerAnimations[uid] = requestAnimationFrame(frame);
    } else {
      delete markerAnimations[uid];
    }
  }
  markerAnimations[uid] = requestAnimationFrame(frame);
}

function fitLngLatPoints(lngLatList, maxZoom, padding) {
  if (!mbMap || !lngLatList.length) return;
  if (lngLatList.length === 1) {
    mbMap.easeTo({ center: lngLatList[0], zoom: maxZoom || 14, duration: 450 });
    return;
  }
  const bounds = new mapboxgl.LngLatBounds(lngLatList[0], lngLatList[0]);
  for (let i = 1; i < lngLatList.length; i++) bounds.extend(lngLatList[i]);
  mbMap.fitBounds(bounds, { padding: padding || 44, maxZoom: maxZoom || 14, duration: 500 });
}

function createServiceDotElement(saved) {
  const wrap = document.createElement('div');
  wrap.className = 'olive-service-marker';
  const dot = document.createElement('div');
  dot.className = saved ? 'olive-service-dot olive-service-saved' : 'olive-service-dot';
  wrap.appendChild(dot);
  return wrap;
}

window.oliveResizeTo = function (height) {
  if (!height || height === lastResizeHeight) return;
  lastResizeHeight = height;
  mapEl.style.height = height + 'px';
  if (mbMap) mbMap.resize();
};

window.oliveReportMapCenter = function () {
  if (!window.OliveMapViewport || !mbMap) return;
  const c = mbMap.getCenter();
  OliveMapViewport.selected(c.lat, c.lng);
};

window.oliveSetHeading = function (degrees, enabled, navigating) {
  const nextHeadingUp = !!enabled;
  navigatingActive = !!navigating;
  userHeadingDeg = Number.isFinite(degrees) ? normalizeDeg(degrees) : null;
  updateOwnHeadingCones();
  if (!mbMap || !styleLoaded) return;
  const modeChanged = headingUp !== nextHeadingUp;
  headingUp = nextHeadingUp;
  if (nextHeadingUp) {
    if (modeChanged || !cameraLoopFrame) startHeadingCameraLoop();
  } else {
    stopHeadingCameraLoop();
    if (modeChanged) {
      mbMap.easeTo({ bearing: 0, pitch: 0, duration: 350 });
    }
  }
};

window.oliveResetNorth = function () {
  headingUp = false;
  stopHeadingCameraLoop();
  if (mbMap) mbMap.easeTo({ bearing: 0, pitch: 0, duration: 350 });
};

window.oliveZoomIn = function () {
  if (mbMap) mbMap.zoomIn({ duration: 220 });
};

window.oliveZoomOut = function () {
  if (mbMap) mbMap.zoomOut({ duration: 220 });
};

window.oliveRecenter = function () {
  if (!mbMap || !points.length) return;
  const target = points[0];
  mbMap.easeTo({
    center: [target.longitude, target.latitude],
    zoom: Math.max(mbMap.getZoom(), 14.5),
    duration: 450
  });
};

window.oliveSetFollow = function (enabled, uid, focusClose) {
  pendingFollowCall = { enabled: !!enabled, uid: uid || null, focusClose: !!focusClose };
  if (!mbMap || !styleLoaded) return;
  if (!enabled || followUid !== (uid || null)) followFocusPending = false;
  followEnabled = !!enabled;
  followUid = uid || null;
  if (followEnabled && focusClose) followFocusPending = true;
  if (followEnabled) {
    const target = points.find(point => (point.uid || point.name) === followUid);
    if (target) {
      if (detailsOnlyForSelectedPoint && selectedPointUid !== followUid) {
        selectedPointUid = followUid;
        window.oliveSetPoints(points, false, true);
      }
      if (headingUp) {
        startHeadingCameraLoop();
      } else if (followFocusPending) {
        followFocusPending = false;
        const closeFollowZoom = Math.max(mbMap.getMinZoom(), mbMap.getMaxZoom() - 3);
        mbMap.easeTo({
          center: [target.longitude, target.latitude],
          zoom: closeFollowZoom,
          duration: 450
        });
      } else {
        mbMap.easeTo({
          center: [target.longitude, target.latitude],
          duration: 380
        });
      }
    }
  }
};

window.oliveSetPoints = function (next, moveCamera, hideUnselectedDetails) {
  pendingPointsCall = [next, !!moveCamera, !!hideUnselectedDetails];
  if (!mbMap || !styleLoaded) return;
  const firstLocation = points.length === 0 && next && next.length > 0;
  points = next || [];
  detailsOnlyForSelectedPoint = !!hideUnselectedDetails;
  if (selectedPointUid && !points.some(point => (point.uid || point.name) === selectedPointUid)) {
    selectedPointUid = null;
  }

  const active = Object.create(null);
  const trailFeatures = [];

  points.forEach(point => {
    const uid = point.uid || point.name;
    active[uid] = true;
    const label = point.label || point.name;
    const pointIsSelected = !detailsOnlyForSelectedPoint || selectedPointUid === uid;
    let marker = markers[uid];

    if (!marker) {
      const el = createMarkerElement(point);
      const popup = new mapboxgl.Popup({ offset: 18, closeButton: false }).setText(label);
      const mbMarker = new mapboxgl.Marker({ element: el, anchor: 'center' })
        .setLngLat([point.longitude, point.latitude])
        .setPopup(pointIsSelected ? popup : null)
        .addTo(mbMap);

      marker = {
        _mb: mbMarker,
        _popup: popup,
        _el: el,
        _oliveStyle: markerStyleKey(point),
        _olivePoint: point,
        getLatLng() {
          const ll = markerCoords[uid] || mbMarker.getLngLat();
          return { lat: ll.lat, lng: ll.lng };
        },
        getElement() {
          return el;
        },
        setLngLat(pair) {
          mbMarker.setLngLat([pair[1], pair[0]]);
        },
        openPopup() {
          if (!mbMarker.getPopup()) mbMarker.setPopup(popup);
          if (!popup.isOpen()) mbMarker.togglePopup();
        }
      };

      el.addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (detailsOnlyForSelectedPoint) {
          selectedPointUid = uid;
          window.oliveSetPoints(points, false, true);
          marker.openPopup();
        } else if (pointIsSelected) {
          if (!popup.isOpen()) mbMarker.togglePopup();
        }
      });

      markers[uid] = marker;
      markerCoords[uid] = { lat: point.latitude, lng: point.longitude };
    } else {
      const styleKey = markerStyleKey(point);
      if (marker._oliveStyle !== styleKey) {
        styleDotElement(marker._el.firstChild, point);
        marker._oliveStyle = styleKey;
      }
      marker._olivePoint = point;
      marker._popup.setText(label);
      if (pointIsSelected) {
        if (!marker._mb.getPopup()) marker._mb.setPopup(marker._popup);
      } else {
        if (marker._popup.isOpen()) marker._popup.remove();
        marker._mb.setPopup(null);
      }
      const oldTarget = markerTargets[uid];
      if (!oldTarget || oldTarget[0] !== point.latitude || oldTarget[1] !== point.longitude) {
        animateMarkerTo(uid, marker._mb, point.latitude, point.longitude, 1200);
      }
    }
    markerTargets[uid] = [point.latitude, point.longitude];

    const trailPoints = (point.trail || []).map(entry => [entry.latitude, entry.longitude]);
    if (pointIsSelected && trailPoints.length > 1) {
      trails[uid] = {
        getLatLngs() { return trailPoints; }
      };
      trailFeatures.push({
        type: 'Feature',
        properties: { color: point.blue ? '#1a73e8' : '#c52b25' },
        geometry: {
          type: 'LineString',
          coordinates: trailPoints.map(pair => [pair[1], pair[0]])
        }
      });
    } else {
      delete trails[uid];
    }
  });

  Object.keys(markers).forEach(uid => {
    if (!active[uid]) {
      if (markerAnimations[uid]) cancelAnimationFrame(markerAnimations[uid]);
      delete markerAnimations[uid];
      delete markerTargets[uid];
      delete markerCoords[uid];
      markers[uid]._mb.remove();
      delete markers[uid];
      delete trails[uid];
    }
  });

  mbMap.getSource('olive-trails-source')?.setData({
    type: 'FeatureCollection',
    features: trailFeatures
  });

  const tracked = points.find(point =>
    point.live && point.accuracy && (!detailsOnlyForSelectedPoint || (point.uid || point.name) === selectedPointUid)
  );
  mbMap.getSource('olive-accuracy-source')?.setData(buildAccuracyGeoJson(tracked));

  updateOwnHeadingCones();

  if ((moveCamera || firstLocation) && points.length) {
    fitLngLatPoints(points.map(p => [p.longitude, p.latitude]), 14, 44);
  }
  if (followEnabled) window.oliveSetFollow(true, followUid, false);
};

window.oliveSetServices = function (services, fit) {
  pendingServicesCall = [services, !!fit];
  if (!mbMap || !styleLoaded) return;
  const list = services || [];
  const active = Object.create(null);
  list.forEach(service => {
    active[service.id] = true;
    const isSaved = !!service.saved;
    const existing = supportMarkers[service.id];
    if (!existing) {
      const el = createServiceDotElement(isSaved);
      const popup = new mapboxgl.Popup({ offset: 14, closeButton: false }).setText(service.name);
      const mbMarker = new mapboxgl.Marker({ element: el, anchor: 'center' })
        .setLngLat([service.longitude, service.latitude])
        .setPopup(popup)
        .addTo(mbMap);
      el.addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (window.OliveSupportSelection) OliveSupportSelection.selected(service.id);
      });
      supportMarkers[service.id] = { mb: mbMarker, el: el, saved: isSaved };
    } else if (existing.saved !== isSaved) {
      existing.saved = isSaved;
      existing.el.firstChild.className = isSaved ? 'olive-service-dot olive-service-saved' : 'olive-service-dot';
    }
  });
  Object.keys(supportMarkers).forEach(id => {
    if (!active[id]) {
      supportMarkers[id].mb.remove();
      delete supportMarkers[id];
    }
  });
  if (fit && list.length) {
    const locations = list.map(service => [service.longitude, service.latitude]);
    points.filter(point => point.own).forEach(point => locations.push([point.longitude, point.latitude]));
    fitLngLatPoints(locations, 14, 48);
  }
};

window.oliveSetRoute = function (coordinates, fitRoute) {
  pendingRouteCall = [coordinates, !!fitRoute];
  if (!mbMap || !styleLoaded) return;
  const line = coordinates || [];
  const source = mbMap.getSource('olive-route-source');
  if (!source) return;
  if (line.length > 1) {
    source.setData({
      type: 'FeatureCollection',
      features: [{
        type: 'Feature',
        properties: {},
        geometry: {
          type: 'LineString',
          coordinates: line.map(pair => [pair[1], pair[0]])
        }
      }]
    });
    if (fitRoute) {
      if (headingUp && followEnabled) {
        followFocusPending = true;
        startHeadingCameraLoop();
      } else {
        fitLngLatPoints(line.map(pair => [pair[1], pair[0]]), 16, 52);
      }
    }
  } else {
    source.setData({ type: 'FeatureCollection', features: [] });
  }
};

window.oliveSetSelection = function (enabled, latitude, longitude) {
  pendingSelectionCall = [!!enabled, latitude, longitude];
  if (!mbMap || !styleLoaded) return;
  selectionEnabled = !!enabled;
  if (!selectionEnabled) {
    if (selectionMarker) {
      selectionMarker.remove();
      selectionMarker = null;
    }
    return;
  }
  if (Number.isFinite(latitude) && Number.isFinite(longitude) && (latitude !== 0 || longitude !== 0)) {
    if (selectionMarker) {
      selectionMarker.setLngLat([longitude, latitude]);
    } else {
      selectionMarker = new mapboxgl.Marker({ color: '#c52b25', draggable: true })
        .setLngLat([longitude, latitude])
        .addTo(mbMap);
      selectionMarker.on('dragend', function () {
        const ll = selectionMarker.getLngLat();
        window.OliveLocationSelection?.selected(ll.lat, ll.lng);
      });
    }
  }
};

function startMap() {
  if (!token || !token.startsWith('pk.') || !window.mapboxgl?.supported()) {
    reportError();
    return;
  }
  try {
    mbMap = new mapboxgl.Map({
      container: 'map',
      accessToken: token,
      style: 'mapbox://styles/tanmaymalik/cmwn3afx4002m01sr80u4bzdl',
      center: [-113.4938, 53.5461],
      zoom: 12,
      minZoom: 1,
      maxZoom: 19,
      projection: 'mercator',
      interactive: true,
      dragRotate: true,
      touchZoomRotate: true,
      touchPitch: true,
      pitchWithRotate: true,
      maxPitch: 60,
      attributionControl: true,
      performanceMetricsCollection: false
    });
  } catch (_) {
    reportError();
    return;
  }

  const timeout = setTimeout(reportError, 14000);
  let viewportReportTimer = 0;

  function onUserCameraGesture(ev) {
    if (!ev || !ev.originalEvent) return;
    if (followEnabled || headingUp) {
      followEnabled = false;
      headingUp = false;
      stopHeadingCameraLoop();
      window.OliveMapCamera?.onUserGesture();
    }
  }

  mbMap.on('dragstart', onUserCameraGesture);
  mbMap.on('rotatestart', onUserCameraGesture);
  mbMap.on('rotate', function () {
    updateOwnHeadingCones();
    const b = mbMap.getBearing();
    if (Math.abs(shortestDelta(lastReportedBearing, b)) >= 2) {
      lastReportedBearing = b;
      window.OliveMapCamera?.onBearingChanged(b);
    }
  });
  mbMap.on('zoomend', function () {
    Object.values(markers).forEach(marker => {
      if (marker._olivePoint && marker._el?.firstChild) {
        styleDotElement(marker._el.firstChild, marker._olivePoint);
      }
    });
  });
  mbMap.on('moveend', function () {
    if (!window.OliveMapViewport) return;
    clearTimeout(viewportReportTimer);
    viewportReportTimer = setTimeout(window.oliveReportMapCenter, 150);
  });
  mbMap.on('click', function (ev) {
    if (!selectionEnabled) return;
    window.oliveSetSelection(true, ev.lngLat.lat, ev.lngLat.lng);
    window.OliveLocationSelection?.selected(ev.lngLat.lat, ev.lngLat.lng);
  });
  mbMap.on('webglcontextlost', function () {
    clearTimeout(timeout);
    reportError();
  });

  mbMap.on('style.load', function () {
    styleLoaded = true;
    const empty = { type: 'FeatureCollection', features: [] };
    mbMap.addSource('olive-accuracy-source', { type: 'geojson', data: empty });
    mbMap.addLayer({
      id: 'olive-accuracy-fill',
      type: 'fill',
      source: 'olive-accuracy-source',
      paint: { 'fill-color': ['get', 'color'], 'fill-opacity': 0.1 }
    });
    mbMap.addLayer({
      id: 'olive-accuracy-stroke',
      type: 'line',
      source: 'olive-accuracy-source',
      paint: { 'line-color': ['get', 'color'], 'line-width': 1.2, 'line-opacity': 0.45 }
    });
    mbMap.addSource('olive-trails-source', { type: 'geojson', data: empty });
    mbMap.addLayer({
      id: 'olive-trails-layer',
      type: 'line',
      source: 'olive-trails-source',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': ['get', 'color'], 'line-width': 4, 'line-opacity': 0.78 }
    });
    mbMap.addSource('olive-route-source', { type: 'geojson', data: empty });
    mbMap.addLayer({
      id: 'olive-route-casing',
      type: 'line',
      source: 'olive-route-source',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#0d47a1', 'line-width': 9, 'line-opacity': 0.9 }
    });
    mbMap.addLayer({
      id: 'olive-route-line',
      type: 'line',
      source: 'olive-route-source',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#1a73e8', 'line-width': 5.5, 'line-opacity': 0.95 }
    });

    if (pendingPointsCall) window.oliveSetPoints(...pendingPointsCall);
    if (pendingServicesCall) window.oliveSetServices(...pendingServicesCall);
    if (pendingRouteCall) window.oliveSetRoute(...pendingRouteCall);
    if (pendingSelectionCall) window.oliveSetSelection(...pendingSelectionCall);
    if (pendingFollowCall) window.oliveSetFollow(pendingFollowCall.enabled, pendingFollowCall.uid, pendingFollowCall.focusClose);
  });

  mbMap.once('idle', function () {
    clearTimeout(timeout);
    mapReady = true;
    mapEl.style.visibility = 'visible';
    document.title = 'olive-map-ready';
  });
}

startMap();
