const map=L.map('map',{zoomControl:false,attributionControl:true}).setView([53.5461,-113.4938],12);
window.oliveResizeTo=function(height){
  document.getElementById('map').style.height=height+'px';
  map.invalidateSize(false);
};
const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{
  maxZoom:19,
  attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);
tiles.on('tileload',function(){
  if(document.getElementById('map').offsetHeight>0) document.title='olive-map-ready';
});
tiles.on('tileerror',function(event){console.error('Map tile failed: '+event.tile.src);});
let markers=[];
let accuracyCircle=null;
let points=[];
window.oliveSetPoints=function(next,moveCamera){
  const firstLocation=points.length===0&&next&&next.length>0;
  points=next||[];
  markers.forEach(marker=>map.removeLayer(marker));
  if(accuracyCircle) map.removeLayer(accuracyCircle);
  markers=points.map(point=>{
    const marker=point.live
      ? L.marker([point.latitude,point.longitude],{icon:L.divIcon({className:'olive-live-marker',html:'<div class="olive-live-dot"></div>',iconSize:[24,24],iconAnchor:[12,12]})})
      : L.marker([point.latitude,point.longitude],{icon:L.divIcon({className:'olive-live-marker',html:'<div class="olive-contact-dot"></div>',iconSize:[22,22],iconAnchor:[11,11]})});
    return marker.bindTooltip(point.label||point.name,{direction:'top'}).bindPopup(point.label||point.name).addTo(map);
  });
  const tracked=points.find(point=>point.live&&point.accuracy);
  accuracyCircle=tracked?L.circle([tracked.latitude,tracked.longitude],{
    radius:tracked.accuracy,color:'#c52b25',weight:1,fillColor:'#c52b25',fillOpacity:.08,interactive:false
  }).addTo(map):null;
  if((moveCamera||firstLocation)&&points.length){
    if(points.length===1) map.setView([points[0].latitude,points[0].longitude],14);
    else map.fitBounds(L.latLngBounds(points.map(point=>[point.latitude,point.longitude])),{padding:[36,36],maxZoom:14});
  }
};
window.oliveZoomIn=function(){map.zoomIn();};
window.oliveZoomOut=function(){map.zoomOut();};
window.oliveRecenter=function(){
  if(points.length) map.setView([points[0].latitude,points[0].longitude],14);
};
