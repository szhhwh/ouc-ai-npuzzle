// Read-only integrity check for generated website data. Not a replacement for browser QA.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),ctx=vm.createContext({window:{}});
for(const file of ['examples.js','lab-data.js'])vm.runInContext(fs.readFileSync(path.join(root,'visualization',file),'utf8'),ctx);
const api=require('../visualization/experiment-lab.js'),data=ctx.window.NPuzzleLabData;
api.verify(data);
const get=(config,id)=>data.records.find(r=>r.config===config&&r.instance===id);
function close(a,b){assert.ok(Number.isFinite(a)&&Number.isFinite(b)&&Math.abs(a-b)<1e-8,`${a} != ${b}`);}
function source(sheet,cell){const row=data.sourceSummaries[sheet].find(r=>Object.hasOwn(r,cell));assert.ok(row,`${sheet}!${cell}`);return row[cell];}
const stage1=['s1-astar-pdb','s1-astar-man','s1-astar-mis','s1-ida-man','s1-ida-mis','s1-nida-pdb'];
for(let id=1;id<=3;id++)for(let j=0;j<stage1.length;j++)close(get(stage1[j],id).timeMs,source('01 阶段一',`${String.fromCharCode(67+j)}${id+4}`));
const stage2=['s2-astar-man','s2-ida-man','s2-nida-pdb'];
for(let id=1;id<=3;id++)for(let j=0;j<stage2.length;j++){
  close(get(stage2[j],id).timeMs,source('03 阶段二',`${String.fromCharCode(66+j)}${id+5}`));
  close(get(stage2[j],id).expanded,source('03 阶段二',`${String.fromCharCode(66+j)}${id+11}`));
}
const b8=['b8-astar-pdb','b8-astar-man','b8-astar-mis','b8-ida-man','b8-ida-mis','b8-nida-pdb'];
for(const metric of ['timeMs','expanded'])for(let depth=8;depth<=24;depth++)for(let j=0;j<b8.length;j++){
  const p=api.seriesFor(data,'benchmark',metric).find(p=>p.config===b8[j]&&p.x===depth);
  assert.equal(p.count,8);close(p.value,source('02 八数码分层基准',`${String.fromCharCode(66+j)}${depth+(metric==='timeMs'?-3:17)}`));
}
const variants=['v1-list-all','v2-list-hash','v3-pq-hash','v4-indexed-hash'];
for(const [kind,prefix,start]of [['structure','st-',6],['hardStructure','st-hard-',13]]){
  const p=api.seriesFor(data,kind,'timeMs');assert.equal(p.length,4);
  for(let i=0;i<variants.length;i++){
    const config=prefix+variants[i],point=p.find(p=>p.config===config);assert.ok(point);
    close(point.value,source('05 数据结构',`B${start+i}`));
    close(api.recordsFor(data,kind).filter(r=>r.config===config).reduce((s,r)=>s+r.timeMs,0),source('05 数据结构',`C${start+i}`));
    close(api.seriesFor(data,kind,'openPeak').find(p=>p.config===config).value,source('05 数据结构',`D${start+i}`));
  }
}
for(const kind of Object.keys(api.groups))for(const metric of Object.keys(api.metrics)){
  for(const p of api.seriesFor(data,kind,metric))assert.ok(p.value===null || Number.isFinite(p.value));
}
assert.equal(data.records.length,1307);assert.equal(data.metadata.issues.length,2);
assert.equal(api.recordsFor(data,'stage1').length,18);assert.equal(api.recordsFor(data,'stage2').length,9);
assert.equal(api.recordsFor(data,'stage3').length,40);assert.equal(api.recordsFor(data,'structure').length,180);
assert.equal(api.recordsFor(data,'hardStructure').length,100);
assert.equal(api.seriesFor(data,'stage3','timeMs').filter(p=>p.status==='oom').length,3);
assert.equal(api.seriesFor(data,'stage3','timeMs').filter(p=>p.status==='timeout').length,9);
const paths=ctx.window.NPuzzleExamples.results;assert.equal(paths.length,18);
for(const p of paths){
  const n=Math.sqrt(p.states[0].length),goal=Array.from({length:n*n},(_,i)=>(i+1)%(n*n));
  assert.equal(p.sourceRevision.slice(0,7),'a461d3b');assert.ok(!p.sourceRevision.includes('dirty'));
  assert.equal(p.measuredRuns,5);assert.equal(p.samplesMs.length,5);close(p.timeMs,api.median([...p.samplesMs]));
  assert.equal(p.states.length-1,[21,31,31][Number(p.name.match(/\d+/)[0])-1]);
  for(let i=0;i<p.states.length;i++){
    const b=[...p.states[i]];assert.deepEqual(b.slice().sort((a,b)=>a-b),Array.from({length:n*n},(_,i)=>i));
    if(!i)continue;const prev=p.states[i-1],a=prev.indexOf(0),z=b.indexOf(0);
    assert.equal(Math.abs(a%n-z%n)+Math.abs(Math.floor(a/n)-Math.floor(z/n)),1);
    assert.ok(b.every((v,k)=>k===a?v===prev[z]:k===z?v===0:v===prev[k]));
  }
  assert.deepEqual([...p.states.at(-1)],goal);
}
const html=fs.readFileSync(path.join(root,'visualization/index.html'),'utf8');
const workflow=fs.readFileSync(path.join(root,'.github/workflows/pages.yml'),'utf8');
for(const [,file]of html.matchAll(/(?:src|href)="([^"#]+\.(?:js|css))"/g)){
  assert.ok(fs.existsSync(path.join(root,'visualization',file)),file);
  assert.ok(workflow.includes(file),`Missing Pages asset: ${file}`);
}
console.log('Workbook rows, source summaries, censored failures, 18 latest Java paths and Pages assets verified.');
