const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function fixture() {
  const views = [];
  const clicks = [];
  const selectedServices = [];
  const removedMarkers = [];
  const reportedCenters = [];
  let zoom = 12;
  const map = {
    attributionControl: {addAttribution() {}},
    setView(position, nextZoom) { zoom = nextZoom; views.push({position, zoom}); return this; },
    getZoom: () => zoom, getMinZoom: () => 1, getMaxZoom: () => 19,
    panTo() {}, fitBounds() { zoom = 14; }, on() {},
    removeLayer(layer) { removedMarkers.push(layer); },
    getCenter: () => ({wrap: () => ({lat: 51.04, lng: -114.07})}),
  };
  const marker = position => ({
    addTo() { return this; }, on(event, action) { if(event==='click') clicks.push(action); }, getLatLng: () => ({lat: position[0], lng: position[1]}),
    setLatLng() {}, setIcon() {}, getTooltip: () => false, getPopup: () => false,
    bindTooltip() {}, bindPopup() {}, closePopup() {}, unbindTooltip() {}, unbindPopup() {},
  });
  const context = vm.createContext({
    window: {OliveSupportSelection: true}, OliveSupportSelection: {selected: id => selectedServices.push(id)},
    OliveMapViewport: {selected: (latitude, longitude) => reportedCenters.push([latitude, longitude])},
    document: {createElement: () => ({textContent: ''})}, console, performance: {now: () => 0},
    requestAnimationFrame: () => 1, cancelAnimationFrame() {},
    L: {map: () => map, tileLayer: () => ({addTo: () => ({on() {}})}),
      marker, divIcon: value => value, latLngBounds: value => value},
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../../app/src/main/assets/map/map.js'), 'utf8'), context);
  views.length = 0;
  return {api: context.window, views, clicks, selectedServices, removedMarkers, reportedCenters};
}
const target = {uid: 'emergency-user', name: 'Test user', latitude: 53.5, longitude: -113.5};

test('emergency tracking waits for a delayed marker then applies search zoom', () => {
  const {api, views} = fixture();
  api.oliveSetFollow(true, target.uid, true);
  api.oliveSetFollow(true, target.uid, false);
  api.oliveSetPoints([target], false, true);
  assert.equal(views.at(-1).zoom, 16);
  assert.deepEqual([...views.at(-1).position], [53.5, -113.5]);
  const count = views.length;
  api.oliveSetPoints([{...target, latitude: 53.51}], false, true);
  assert.equal(views.length, count, 'movement must preserve the chosen zoom');
});

test('cancelling tracking before location arrives cancels its pending zoom', () => {
  const {api, views} = fixture();
  api.oliveSetFollow(true, target.uid, true);
  api.oliveSetFollow(false, target.uid, false);
  api.oliveSetPoints([target], false, true);
  assert.equal(views.at(-1).zoom, 14);
});

test('a public support marker forwards its exact service id for native navigation', () => {
  const {api, clicks, selectedServices} = fixture();
  api.oliveSetServices([{id: 'public-service', name: '<Sample support>', latitude: 53.5, longitude: -113.5}], false);
  clicks.at(-1)();
  assert.deepEqual(selectedServices, ['public-service']);
  api.oliveSetServices([], false);
});

test('the map reports its center and retains markers when another search area is added', () => {
  const {api, clicks, removedMarkers, reportedCenters} = fixture();
  api.OliveMapViewport = true;
  api.oliveReportMapCenter();
  assert.deepEqual(reportedCenters, [[51.04, -114.07]]);
  const first = {id: 'first', name: 'First service', latitude: 53.5, longitude: -113.5};
  const second = {id: 'second', name: 'Second service', latitude: 51.04, longitude: -114.07};
  api.oliveSetServices([first], false);
  api.oliveSetServices([first, second], false);
  assert.equal(removedMarkers.length, 0);
  assert.equal(clicks.length, 2);
});
