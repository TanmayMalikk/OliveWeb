// Safe-app users and safe contacts: draws the interactive location map and route.
const map=L.map('map',{zoomControl:false,attributionControl:true}).setView([53.5461,-113.4938],12);
window.oliveResizeTo=function(height){
  document.getElementById('map').style.height=height+'px';
  map.invalidateSize(false);
};
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{
  maxZoom:19,
  attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);
// Marker updates and controls work before network tiles arrive; do not block the map on OSM latency.
requestAnimationFrame(function(){document.title='olive-map-ready';});
tiles.on('tileerror',function(event){console.error('Map tile failed: '+event.tile.src);});
const markers={};
const markerAnimations={};
const markerTargets={};
const trails={};
let accuracyCircle=null;
let routeLine=null;
let mapboxAttributionAdded=false;
let points=[];
let followEnabled=false;
let followUid=null;
let selectionMarker=null;
let selectionEnabled=false;
// Map: Builds the map marker icon. It creates a Leaflet div icon with the required accessible marker markup.
function markerIcon(point){
  const className=point.blue?'olive-own-live-dot':(point.live?'olive-live-dot':(point.lastKnown?'olive-last-known-dot':'olive-contact-dot'));
  const size=(point.live||point.lastKnown)?24:22;
  return L.divIcon({className:'olive-live-marker',html:'<div class="'+className+'"></div>',iconSize:[size,size],iconAnchor:[size/2,size/2]});
}
// Map: Chooses the map marker style. It checks the value against the rules before using it.
function markerStyle(point){return point.blue?'blue':(point.live?'live':(point.lastKnown?'last-known':'contact'));}
// returns the animate marker for map. It goes through the list and keeps or changes each item that is needed.
function animateMarker(uid,marker,target,duration){
  if(markerAnimations[uid]) cancelAnimationFrame(markerAnimations[uid]);
  const start=marker.getLatLng();
  const began=performance.now();
  // returns the frame for map. It saves the new value and refreshes what the user sees.
  function frame(now){
    const progress=Math.min(1,(now-began)/duration);
    const eased=1-Math.pow(1-progress,3);
    marker.setLatLng([start.lat+(target[0]-start.lat)*eased,start.lng+(target[1]-start.lng)*eased]);
    if(followEnabled&&followUid===uid) map.panTo(marker.getLatLng(),{animate:false});
    if(progress<1) markerAnimations[uid]=requestAnimationFrame(frame);
    else delete markerAnimations[uid];
  }
  markerAnimations[uid]=requestAnimationFrame(frame);
}
window.oliveSetPoints=function(next,moveCamera){
  const firstLocation=points.length===0&&next&&next.length>0;
  points=next||[];
  const active={};
  points.forEach(point=>{
    const uid=point.uid||point.name;
    active[uid]=true;
    const target=[point.latitude,point.longitude];
    let marker=markers[uid];
    // Only continue with marker when marker is false.
    if(!marker){
      marker=L.marker(target,{icon:markerIcon(point)}).addTo(map);
      marker._oliveStyle=markerStyle(point);
      markers[uid]=marker;
    }else{
      const style=markerStyle(point);
      if(marker._oliveStyle!==style){marker.setIcon(markerIcon(point));marker._oliveStyle=style;}
      const oldTarget=markerTargets[uid];
      if(!oldTarget||oldTarget[0]!==target[0]||oldTarget[1]!==target[1]) animateMarker(uid,marker,target,1800);
    }
    markerTargets[uid]=target;
    const label=point.label||point.name;
    if(marker.getTooltip()) marker.setTooltipContent(label); else marker.bindTooltip(label,{direction:'top'});
    if(marker.getPopup()) marker.setPopupContent(label); else marker.bindPopup(label);
    const trailPoints=(point.trail||[]).map(entry=>[entry.latitude,entry.longitude]);
    // Only continue with trail points when trail points length>1.
    if(trailPoints.length>1){
      if(trails[uid]) trails[uid].setLatLngs(trailPoints).setStyle({color:point.blue?'#1769aa':'#c52b25'});
      else trails[uid]=L.polyline(trailPoints,{color:point.blue?'#1769aa':'#c52b25',weight:4,opacity:.78,lineCap:'round',lineJoin:'round',interactive:false}).addTo(map);
      trails[uid].bringToBack();
    }else if(trails[uid]){
      map.removeLayer(trails[uid]);delete trails[uid];
    }
  });
  Object.keys(markers).forEach(uid=>{if(!active[uid]){
    if(markerAnimations[uid]) cancelAnimationFrame(markerAnimations[uid]);
    delete markerAnimations[uid];delete markerTargets[uid];map.removeLayer(markers[uid]);delete markers[uid];
    if(trails[uid]){map.removeLayer(trails[uid]);delete trails[uid];}
  }});
  const tracked=points.find(point=>point.live&&point.accuracy);
  // tracked is handled here by tracked.
  if(tracked){
    if(accuracyCircle) accuracyCircle.setLatLng([tracked.latitude,tracked.longitude]).setRadius(tracked.accuracy).setStyle({color:tracked.blue?'#1769aa':'#c52b25',fillColor:tracked.blue?'#1769aa':'#c52b25'});
    else accuracyCircle=L.circle([tracked.latitude,tracked.longitude],{
      radius:tracked.accuracy,color:tracked.blue?'#1769aa':'#c52b25',weight:1,fillColor:tracked.blue?'#1769aa':'#c52b25',fillOpacity:.08,interactive:false
    }).addTo(map);
  }else if(accuracyCircle){map.removeLayer(accuracyCircle);accuracyCircle=null;}
  if((moveCamera||firstLocation)&&points.length){
    if(points.length===1) map.setView([points[0].latitude,points[0].longitude],14);
    else map.fitBounds(L.latLngBounds(points.map(point=>[point.latitude,point.longitude])),{padding:[36,36],maxZoom:14});
  }
};
window.oliveSetRoute=function(coordinates,fitRoute){
  const line=coordinates||[];
  // line checks whether line length>1 before continuing.
  if(line.length>1){
    if(!mapboxAttributionAdded){
      map.attributionControl.addAttribution('&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>');
      mapboxAttributionAdded=true;
    }
    if(routeLine) routeLine.setLatLngs(line);
    else routeLine=L.polyline(line,{color:'#1769aa',weight:6,opacity:.86,lineCap:'round',lineJoin:'round',interactive:false}).addTo(map);
    routeLine.bringToBack();
    if(fitRoute) map.fitBounds(routeLine.getBounds(),{padding:[38,38],maxZoom:16});
  }else if(routeLine){map.removeLayer(routeLine);routeLine=null;}
};
window.oliveZoomIn=function(){map.zoomIn();};
window.oliveZoomOut=function(){map.zoomOut();};
window.oliveRecenter=function(){
  if(points.length) map.setView([points[0].latitude,points[0].longitude],14);
};
window.oliveSetFollow=function(enabled,uid){
  followEnabled=!!enabled;
  followUid=uid||null;
  if(followEnabled){
    const target=points.find(point=>(point.uid||point.name)===followUid);
    if(target) map.panTo([target.latitude,target.longitude],{animate:true,duration:.5});
  }
};
// Incident reports: lets the user tap the map or drag one pushpin to save exact coordinates.
window.oliveSetSelection=function(enabled,latitude,longitude){
  selectionEnabled=!!enabled;
  if(!selectionEnabled){if(selectionMarker){map.removeLayer(selectionMarker);selectionMarker=null;}return;}
  if(Number.isFinite(latitude)&&Number.isFinite(longitude)&&(latitude!==0||longitude!==0)){
    if(selectionMarker) selectionMarker.setLatLng([latitude,longitude]);
    else{
      selectionMarker=L.marker([latitude,longitude],{draggable:true}).addTo(map).bindTooltip('Incident location');
      selectionMarker.on('dragend',function(){const point=selectionMarker.getLatLng();OliveLocationSelection.selected(point.lat,point.lng);});
    }
  }
};
map.on('click',function(event){
  if(!selectionEnabled)return;
  if(selectionMarker) selectionMarker.setLatLng(event.latlng);
  else window.oliveSetSelection(true,event.latlng.lat,event.latlng.lng);
  OliveLocationSelection.selected(event.latlng.lat,event.latlng.lng);
});
