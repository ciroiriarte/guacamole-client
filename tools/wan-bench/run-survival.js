const puppeteer=require('puppeteer-core');
const A=Object.fromEntries(process.argv.slice(2).map(a=>a.replace(/^--/,'').split('=')));
const URL=A.url||'http://10.200.1.1:8080/guacamole/wan-bench.html';
const SEC=parseInt(A.seconds||'45'); const BLIP=A.blip||'?'; const PROFILE=A.profile||'clean';
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const b=await puppeteer.launch({executablePath:'/usr/bin/chromium',headless:true,args:['--no-sandbox','--disable-dev-shm-usage','--disable-gpu']});
 const p=await b.newPage(); p.on('pageerror',()=>{});
 await p.goto(URL,{waitUntil:'load',timeout:30000});
 await p.evaluate(async()=>{
  const S={frames:[],states:[],closes:[],errors:[],first:null}; window.__S=S;
  const tr=await fetch('api/tokens',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'username=guacadmin&password=guacadmin'});
  const j=await tr.json();
  const tunnel=new Guacamole.WebSocketTunnel('websocket-tunnel'); const client=new Guacamole.Client(tunnel); const display=client.getDisplay();
  display.statisticWindow=200;
  display.onstatistics=()=>{const t=performance.now();S.frames.push(t);if(S.first===null)S.first=t;};
  tunnel.onstatechange=st=>S.states.push(st);
  tunnel.onclose=d=>S.closes.push({code:d.code,wasClean:d.wasClean,since:d.since!=null?d.since:d.sinceLastReceive});
  client.onerror=e=>S.errors.push(e.code);
  document.getElementById('disp').appendChild(display.getElement());
  window.__poke=()=>{client.sendKeyEvent(1,120);client.sendKeyEvent(0,120);};
  client.connect('token='+encodeURIComponent(j.authToken)+'&GUAC_DATA_SOURCE=default&GUAC_ID=ssh-localhost&GUAC_TYPE=c&GUAC_WIDTH=1024&GUAC_HEIGHT=768&GUAC_DPI=96&GUAC_TIMEZONE=UTC');
 });
 await p.waitForFunction(()=>window.__S&&window.__S.first!==null,{timeout:30000}).catch(()=>{});
 await sleep(4000);
 console.log('SURVIVAL_READY'); 
 for(let i=0;i<SEC;i++){ await p.evaluate(()=>window.__poke&&window.__poke()).catch(()=>{}); await sleep(1000); }
 const S=await p.evaluate(()=>window.__S);
 const fr=S.frames; let maxGap=0,gapAt=0;
 for(let i=1;i<fr.length;i++){const g=fr[i]-fr[i-1]; if(g>maxGap){maxGap=g;gapAt=fr[i-1];}}
 const died=S.closes.length>0||S.errors.length>0;
 const framesAfterGap=fr.filter(t=>t>gapAt+maxGap-1).length;
 const survived=!died && maxGap>2500 && framesAfterGap>0;
 console.log('SURVIVAL '+JSON.stringify({profile:PROFILE,blip:BLIP,frames:fr.length,maxGapMs:Math.round(maxGap),survived,died,closes:S.closes,errors:S.errors,states:S.states}));
 await b.close();
})().catch(e=>{console.log('SURVIVAL_ERR '+e.message);process.exit(1);});
