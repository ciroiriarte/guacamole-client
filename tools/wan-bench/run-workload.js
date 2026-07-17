const puppeteer=require('puppeteer-core');
const A=Object.fromEntries(process.argv.slice(2).map(a=>a.replace(/^--/,'').split('=')));
const URL=A.url||'http://10.200.1.1:8080/guacamole/wan-bench.html';
const PROFILE=A.profile||'?'; const ECHO=parseInt(A.echo||'15');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const b=await puppeteer.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-gpu']});
 const p=await b.newPage();
 const cdp=await p.target().createCDPSession(); await cdp.send('Network.enable');
 let bR=0,bS=0;
 cdp.on('Network.webSocketFrameReceived',x=>{bR+=(x.response&&x.response.payloadData?x.response.payloadData.length:0);});
 cdp.on('Network.webSocketFrameSent',x=>{bS+=(x.response&&x.response.payloadData?x.response.payloadData.length:0);});
 const perr=[]; p.on('pageerror',e=>perr.push(String(e)));
 await p.goto(URL,{waitUntil:'load',timeout:30000});
 await p.evaluate(async()=>{
  const M={lat:[],disp:[],close:[],firstFrame:null,t0:null,echo:[]}; window.__M=M;
  const tr=await fetch('api/tokens',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username=guacadmin&password=guacadmin'});
  const j=await tr.json();
  const tunnel=new Guacamole.WebSocketTunnel('websocket-tunnel'); const client=new Guacamole.Client(tunnel); const display=client.getDisplay();
  display.statisticWindow=200;
  display.onstatistics=s=>{M.disp.push({lag:s.processingLag,cfps:s.clientFps,sfps:s.serverFps,drop:s.dropRate}); if(M.firstFrame===null)M.firstFrame=performance.now();};
  tunnel.onlatency=l=>M.lat.push({rtt:l.rtt,srtt:l.srtt,jitter:l.jitter,spike:l.spike});
  tunnel.onclose=d=>M.close.push({code:d.code,wasClean:d.wasClean,sinceLastReceive:d.sinceLastReceive});
  document.getElementById('disp').appendChild(display.getElement());
  window.__echo=(ch)=>new Promise(res=>{const t=performance.now();let done=false;const prev=display.onstatistics;
    display.onstatistics=function(s){prev&&prev(s);if(!done){done=true;display.onstatistics=prev;res(performance.now()-t);}};
    const k=ch.codePointAt(0);client.sendKeyEvent(1,k);client.sendKeyEvent(0,k);});
  window.__type=t=>{for(const ch of t){const k=ch==='\n'?65293:ch.codePointAt(0);client.sendKeyEvent(1,k);client.sendKeyEvent(0,k);}};
  M.t0=performance.now();
  client.connect('token='+encodeURIComponent(j.authToken)+'&GUAC_DATA_SOURCE=default&GUAC_ID=ssh-localhost&GUAC_TYPE=c&GUAC_WIDTH=1024&GUAC_HEIGHT=768&GUAC_DPI=96&GUAC_TIMEZONE=UTC');
 });
 await p.waitForFunction(()=>window.__M&&window.__M.firstFrame!==null,{timeout:30000}).catch(()=>{});
 const ttff=await p.evaluate(()=>window.__M.firstFrame?Math.round(window.__M.firstFrame-window.__M.t0):null);
 await sleep(2500);
 for(let i=0;i<ECHO;i++){
  const d=await p.evaluate(async()=>{try{return await Promise.race([window.__echo('x'),new Promise(r=>setTimeout(()=>r(null),4000))]);}catch(e){return null;}});
  if(d!=null) await p.evaluate(v=>window.__M.echo.push(v),d);
  await sleep(700);
 }
 const M=await p.evaluate(()=>window.__M);
 const echo=M.echo.map(x=>Math.round(x));
 console.log('RUN '+JSON.stringify({profile:PROFILE,ttff,latN:M.lat.length,dispN:M.disp.length,echoN:echo.length,echo,srttLast:M.lat.length?Math.round(M.lat[M.lat.length-1].srtt):null,bytesRecv:bR,bytesSent:bS,close:M.close,err:perr.slice(0,2)}));
 await b.close();
})().catch(e=>{console.log('WORKLOAD_ERR '+e.message);process.exit(1);});
