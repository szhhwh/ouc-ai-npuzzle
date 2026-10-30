// Zero-dependency validation of the published Java observations.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const path = require('node:path');
const context = {window:{}};
vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../visualization/java-search-traces.js'),'utf8'),context);
const data = JSON.parse(JSON.stringify(context.window.NPuzzleJavaTraces));
assert.equal(data.verifiedAgainstUninstrumented,true);
assert.equal(data.traces.length,6);
for(const trace of data.traces){
  const nodes=new Map(trace.nodes.map(n=>[n.id,n]));
  const visible=new Set([0]);
  for(const n of nodes.values()){
    assert.deepEqual([...n.board].sort((a,b)=>a-b),[0,1,2,3,4,5,6,7,8]);
    const h=n.board.reduce((sum,v,i)=>sum+(!v?0:trace.heuristic==='MISPLACED'?Number(v!==i+1):Math.abs(i%3-(v-1)%3)+Math.abs(Math.floor(i/3)-Math.floor((v-1)/3))),0);
    assert.equal(n.h,h);assert.equal(n.f,n.g+n.h);
    if(n.parent!==null){
      const p=nodes.get(n.parent);assert.equal(n.g,p.g+1);
      const a=n.board.indexOf(0),b=p.board.indexOf(0);
      assert.equal(Math.abs(a%3-b%3)+Math.abs(Math.floor(a/3)-Math.floor(b/3)),1);
      const expected=p.board.slice();[expected[a],expected[b]]=[expected[b],expected[a]];assert.deepEqual(n.board,expected);
    }
  }
  for(const f of trace.frames){
    assert(visible.has(f.current));assert(f.openBefore.includes(f.current));
    const current=nodes.get(f.current);
    for(const id of f.openBefore){const n=nodes.get(id);assert(visible.has(id));assert(n.f>current.f||n.f===current.f&&n.g>=current.g);}
    let active=new Set(f.openBefore);active.delete(f.current);
    for(const id of f.children){
      const n=nodes.get(id);assert.equal(n.parent,current.id);visible.add(id);
      if(n.status==='replaced')active.delete(n.reference);
      if(['frontier','replaced'].includes(n.status))active.add(id);
      else assert(n.status.endsWith('pruned'));
    }
    assert.deepEqual([...active].sort((a,b)=>a-b),[...f.openAfter].sort((a,b)=>a-b));
  }
  if(!trace.truncated){assert(trace.frames.at(-1).found);assert.equal(trace.solution.length-1,trace.referenceLength);assert.equal(nodes.get(trace.solution.at(-1)).h,0);}
  console.log(`${trace.name} / ${trace.heuristic}: ${trace.frames.length} frames OK`);
}
