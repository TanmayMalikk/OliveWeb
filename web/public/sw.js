// No account pages, private data, API responses, URLs, or article bodies are cached.
// The offline response is deliberately the public disguise, not a saved private screen.
self.addEventListener('install',()=>self.skipWaiting());
self.addEventListener('activate',event=>event.waitUntil(self.clients.claim()));
self.addEventListener('fetch',event=>{
  if(event.request.mode!=='navigate')return;
  event.respondWith(fetch(event.request).catch(()=>new Response('<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#f7f7ef"><title>Little Grove</title><body style="font-family:system-ui;background:#f7f7ef;color:#30432b;padding:10vw"><h1>A little pause.</h1><p>You’re offline. Reconnect to play another round.</p><a href="/">Try again</a></body></html>',{headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'}})));
});
