// Builds repeatable city route loops and calculates where a test user should be at any time.
const EARTH_RADIUS_METRES = 6_371_000;
const METRES_PER_SECOND_AT_60_KMPH = 60_000 / 3_600;

// Creates a smooth loop around a fictional user's seeded community-centre location.
function routeLoop(latitude, longitude, radiusMetres = 2_000, pointCount = 16) {
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) {
    throw new TypeError("A route centre needs valid latitude and longitude numbers.");
  }
  if (!Number.isFinite(radiusMetres) || radiusMetres <= 0 || !Number.isInteger(pointCount) || pointCount < 4) {
    throw new RangeError("A route loop needs a positive radius and at least four points.");
  }
  const latitudeDegrees = radiusMetres / 110_574;
  const longitudeDegrees = radiusMetres / (111_320 * Math.cos(latitude * Math.PI / 180));
  const points = Array.from({length: pointCount}, (_, index) => {
    const angle = (index / pointCount) * Math.PI * 2;
    return {
      latitude: latitude + Math.sin(angle) * latitudeDegrees,
      longitude: longitude + Math.cos(angle) * longitudeDegrees,
    };
  });
  return [...points, points[0]];
}

// Measures one route section so the simulator moves at the same speed around the whole loop.
function distanceMetres(start, end) {
  const toRadians = degrees => degrees * Math.PI / 180;
  const latitudeChange = toRadians(end.latitude - start.latitude);
  const longitudeChange = toRadians(end.longitude - start.longitude);
  const startLatitude = toRadians(start.latitude);
  const endLatitude = toRadians(end.latitude);
  const haversine = Math.sin(latitudeChange / 2) ** 2 +
    Math.cos(startLatitude) * Math.cos(endLatitude) * Math.sin(longitudeChange / 2) ** 2;
  return EARTH_RADIUS_METRES * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
}

// Finds the point reached after the requested time while looping forever at 60 km/h.
function positionAt60Kmph(route, elapsedMilliseconds) {
  if (!Array.isArray(route) || route.length < 2 || !Number.isFinite(elapsedMilliseconds)) {
    throw new TypeError("A simulated position needs a route and a valid elapsed time.");
  }
  const segments = route.slice(0, -1).map((start, index) => ({
    start,
    end: route[index + 1],
    length: distanceMetres(start, route[index + 1]),
  }));
  const routeLength = segments.reduce((total, segment) => total + segment.length, 0);
  let remaining = ((Math.max(0, elapsedMilliseconds) / 1_000) * METRES_PER_SECOND_AT_60_KMPH) % routeLength;
  for (const segment of segments) {
    if (remaining <= segment.length) {
      const progress = segment.length === 0 ? 0 : remaining / segment.length;
      return {
        latitude: segment.start.latitude + (segment.end.latitude - segment.start.latitude) * progress,
        longitude: segment.start.longitude + (segment.end.longitude - segment.start.longitude) * progress,
      };
    }
    remaining -= segment.length;
  }
  return route[0];
}

module.exports = {distanceMetres, positionAt60Kmph, routeLoop, METRES_PER_SECOND_AT_60_KMPH};
