// Safe-app users and safe contacts: draws the interactive location map and route.
const map=L.map('map',{zoomControl:false,attributionControl:true,minZoom:1,maxZoom:19}).setView([53.5461,-113.4938],12);
window.oliveResizeTo=function(height){
  document.getElementById('map').style.height=height+'px';
  map.invalidateSize(false);
};
window.oliveLoadBasemap?.(map);
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
let followFocusPending=false;
let selectionMarker=null;
let selectionEnabled=false;
let detailsOnlyForSelectedPoint=false;
let selectedPointUid=null;
const supportMarkers=Object.create(null);
window.oliveReportMapCenter=function(){
  if(!window.OliveMapViewport) return;
  const center=map.getCenter().wrap();
  OliveMapViewport.selected(center.lat,center.lng);
};
let viewportReportTimer;
map.on('moveend',function(){
  if(!window.OliveMapViewport) return;
  clearTimeout(viewportReportTimer);
  viewportReportTimer=setTimeout(window.oliveReportMapCenter,150);
});
function showMapboxAttribution(){
  if(mapboxAttributionAdded) return;
  map.attributionControl.addAttribution('&copy; <a href="https://www.mapbox.com/about/maps/">Mapbox</a>');
  mapboxAttributionAdded=true;
}
window.oliveSetServices=function(services,fit){
  const active=Object.create(null);
  if(services?.length) showMapboxAttribution();
  (services||[]).forEach(service=>{
    active[service.id]=true;
    if(!supportMarkers[service.id]){
      const icon=L.divIcon({
        className:'olive-service-marker',html:'<div class="olive-service-dot"></div>',
        iconSize:[30,30],iconAnchor:[15,15]
      });
      const marker=L.marker([service.latitude,service.longitude],{icon:icon}).addTo(map);
      const label=document.createElement('span');label.textContent=service.name;
      marker.bindTooltip(label,{direction:'top'});
      marker.on('click',function(){if(window.OliveSupportSelection)OliveSupportSelection.selected(service.id);});
      supportMarkers[service.id]=marker;
    }
  });
  Object.keys(supportMarkers).forEach(id=>{if(!active[id]){map.removeLayer(supportMarkers[id]);delete supportMarkers[id];}});
  if(fit&&services.length){
    const locations=services.map(service=>[service.latitude,service.longitude]);
    points.filter(point=>point.own).forEach(point=>locations.push([point.latitude,point.longitude]));
    map.fitBounds(L.latLngBounds(locations),{padding:[36,36],maxZoom:14});
  }
};
// Keeps distant markers small enough to separate while leaving them visible in an Alberta-wide view.
function markerSize(point){
  const closeSize=(point.live||point.lastKnown)?28:26;
  const zoomProgress=Math.max(0,Math.min(1,(map.getZoom()-4)/10));
  return Math.round(16+(closeSize-16)*zoomProgress);
}
// Map: Builds a marker at the size needed for the current zoom level.
function markerIcon(point){
  const className=point.blue?'olive-own-live-dot':(point.live?'olive-live-dot':(point.lastKnown?'olive-last-known-dot':'olive-contact-dot'));
  const size=markerSize(point);
  const border=size>=20?3:2;
  const centreSize=size-(border*2);
  const style='width:'+centreSize+'px;height:'+centreSize+'px;border:'+border+'px solid #fff';
  return L.divIcon({className:'olive-live-marker',html:'<div class="'+className+'" style="'+style+'"></div>',iconSize:[size,size],iconAnchor:[size/2,size/2]});
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
window.oliveSetPoints=function(next,moveCamera,hideUnselectedDetails){
  const firstLocation=points.length===0&&next&&next.length>0;
  points=next||[];
  detailsOnlyForSelectedPoint=!!hideUnselectedDetails;
  if(selectedPointUid&&!points.some(point=>(point.uid||point.name)===selectedPointUid)) selectedPointUid=null;
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
      marker._olivePoint=point;
      marker.on('click',function(){
        if(!detailsOnlyForSelectedPoint)return;
        selectedPointUid=uid;
        window.oliveSetPoints(points,false,true);
        marker.openPopup();
      });
      markers[uid]=marker;
    }else{
      const style=markerStyle(point);
      if(marker._oliveStyle!==style){marker.setIcon(markerIcon(point));marker._oliveStyle=style;}
      marker._olivePoint=point;
      const oldTarget=markerTargets[uid];
      if(!oldTarget||oldTarget[0]!==target[0]||oldTarget[1]!==target[1]) animateMarker(uid,marker,target,1800);
    }
    markerTargets[uid]=target;
    const label=point.label||point.name;
    const pointIsSelected=!detailsOnlyForSelectedPoint||selectedPointUid===uid;
    if(pointIsSelected){
      if(marker.getTooltip()) marker.setTooltipContent(label); else marker.bindTooltip(label,{direction:'top'});
      if(marker.getPopup()) marker.setPopupContent(label); else marker.bindPopup(label);
    }else{
      marker.closePopup();
      marker.unbindTooltip();
      marker.unbindPopup();
    }
    const trailPoints=(point.trail||[]).map(entry=>[entry.latitude,entry.longitude]);
    // Admins only see a trail after selecting that user's dot.
    if(pointIsSelected&&trailPoints.length>1){
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
  const tracked=points.find(point=>
    point.live&&point.accuracy&&(!detailsOnlyForSelectedPoint||(point.uid||point.name)===selectedPointUid)
  );
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
  // A navigation request can arrive before its live marker. Apply its close zoom once data arrives.
  if(followEnabled) window.oliveSetFollow(true,followUid,false);
};
window.oliveSetRoute=function(coordinates,fitRoute){
  const line=coordinates||[];
  // line checks whether line length>1 before continuing.
  if(line.length>1){
    showMapboxAttribution();
    if(routeLine) routeLine.setLatLngs(line);
    else routeLine=L.polyline(line,{color:'#1769aa',weight:6,opacity:.86,lineCap:'round',lineJoin:'round',interactive:false}).addTo(map);
    routeLine.bringToBack();
    if(fitRoute) map.fitBounds(routeLine.getBounds(),{padding:[38,38],maxZoom:16});
  }else if(routeLine){map.removeLayer(routeLine);routeLine=null;}
};
window.oliveZoomIn=function(){map.zoomIn();};
window.oliveZoomOut=function(){map.zoomOut();};
// Resizes every dot after a zoom so crowded markers do not cover one another.
map.on('zoomend',function(){
  Object.values(markers).forEach(marker=>{
    if(marker._olivePoint)marker.setIcon(markerIcon(marker._olivePoint));
  });
});
window.oliveRecenter=function(){
  if(points.length) map.setView([points[0].latitude,points[0].longitude],14);
};
window.oliveSetFollow=function(enabled,uid,focusClose){
  if(!enabled || followUid!==(uid||null)) followFocusPending=false;
  followEnabled=!!enabled;
  followUid=uid||null;
  if(followEnabled&&focusClose) followFocusPending=true;
  if(followEnabled){
    const target=points.find(point=>(point.uid||point.name)===followUid);
    if(target){
      if(detailsOnlyForSelectedPoint&&selectedPointUid!==followUid){
        selectedPointUid=followUid;
        window.oliveSetPoints(points,false,true);
      }
      // Search selection starts three zoom-out steps below the closest map view.
      if(followFocusPending){
        followFocusPending=false;
        const closeFollowZoom=Math.max(map.getMinZoom(),map.getMaxZoom()-3);
        map.setView([target.latitude,target.longitude],closeFollowZoom,{animate:true,duration:.5});
      }else map.panTo([target.latitude,target.longitude],{animate:true,duration:.5});
    }
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
