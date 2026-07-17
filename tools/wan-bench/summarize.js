/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
const fs=require('fs');
const f=process.argv[2]||'baseline-results.jsonl';
const rows=fs.readFileSync(f,'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const pct=(a,p)=>{if(!a.length)return null;const s=[...a].sort((x,y)=>x-y);return s[Math.min(s.length-1,Math.floor(p/100*s.length))];};
const by={}; for(const r of rows)(by[r.profile]=by[r.profile]||[]).push(r);
const pad=(s,n)=>String(s).padEnd(n);
console.log(pad('profile',8)+pad('runs',6)+pad('TTFF p50/p95',16)+pad('echo p50/p95 ms',18)+pad('srtt p50',10)+pad('bytesRx p50',12)+'echoLoss%');
for(const prof of ['clean','good','poor','lossy']){const rs=by[prof];if(!rs)continue;
 const ttff=rs.map(r=>r.ttff).filter(x=>x!=null);
 const echo=rs.flatMap(r=>r.echo||[]);
 const srtt=rs.map(r=>r.srttLast).filter(x=>x!=null);
 const br=rs.map(r=>r.bytesRecv);
 const expected=rs.reduce((a,r)=>a+ (r.echoN!=null?10:0),0); // approx
 console.log(pad(prof,8)+pad(rs.length,6)+pad(pct(ttff,50)+'/'+pct(ttff,95),16)+pad(pct(echo,50)+'/'+pct(echo,95),18)+pad(pct(srtt,50),10)+pad(pct(br,50),12));
}
