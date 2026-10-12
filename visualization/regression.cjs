// Run with: node visualization/regression.cjs (no external dependencies).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const elements = new Map();
const downloadAnchors = [], downloadBlobs = [];
let keyboard;
function element() {
  return {
    style: { setProperty() {} }, classList: { toggle() {} },
    append() {}, after() {}, click() {this.clicked=true;}, remove() {this.removed=true;}, parentElement: {parentElement:{after(){}}}, replaceChildren() {}, clientWidth: 276, clientHeight: 276, value: '0'
  };
}
const document = {
  body: {append(anchor) {downloadAnchors.push(anchor);}},
  getElementById(id) {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  },
  querySelector: () => element(), createElement: element,
  addEventListener(name, handler) { if (name === 'keydown') keyboard = handler; }
};
const context = vm.createContext({
  document, window: { addEventListener() {} }, performance, console,
  setTimeout: () => 1, clearTimeout() {}, assert,
  Blob, URL: {createObjectURL(blob) {downloadBlobs.push(blob);return 'blob:test';}, revokeObjectURL() {}}
});
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
vm.runInContext(source.replace(/run\(\);\s*$/, ''), context);
vm.runInContext(`
const initial = [1,2,3,4,5,6,7,0,8];
const target = [1,2,3,4,5,6,7,8,0];
load(validate({states:[initial]}), '导入的 Java 解路径');
assert.equal(document.getElementById('status').textContent, '路径播放完成');
assert.equal(document.getElementById('algorithm-name').textContent, '未提供');
assert.equal(document.getElementById('sample').value, 'imported');
load(validate({states:[initial,target], goal:target, algorithm:'IDA*', heuristic:'DISJOINT_PATTERN'}), '导入的 Java 解路径');
assert.equal(document.getElementById('algorithm-name').textContent, 'IDA*');
assert.match(document.getElementById('heuristic-note').textContent, /DISJOINT_PATTERN/);
jump(1);
assert.equal(document.getElementById('status').textContent, '已抵达目标 ✓');
// A noncanonical goal is valid too.
load(validate({states:[target,initial], goal:initial}), '导入的 Java 解路径');
jump(1);
assert.equal(document.getElementById('status').textContent, '已抵达目标 ✓');
assert.throws(() => validate({states:[initial], goal:target}));
assert.throws(() => validate({states:[initial,target], algorithm:{name:'IDA*'}}));
assert.throws(() => validate({states:[initial,target], generated:1.5}));
assert.throws(() => validate({states:[initial,target], timeMs:-1}));
assert.throws(() => validate(null));
assert.throws(() => validate({states:[null]}));
assert.throws(() => validate({states:[initial,[0,2,3,4,5,6,7,1,8]]}));
const goal4 = Array.from({length:16}, (_,i)=>(i+1)%16);
const start4 = goal4.slice(); [start4[14],start4[15]]=[start4[15],start4[14]];
load(validate({states:[start4,goal4],goal:goal4}), '导入的 Java 解路径');
jump(0);
for (const kind of ['manhattan','misplaced']) samples.forEach((s,i) => {
  const result=solve(s,kind);
  assert.equal(result.states.length-1,[21,31,31][i]);
  validate({...result, goal:target});
});
`, context);
const press = (code, tagName) => keyboard({code, target:{tagName}, preventDefault(){}});
press('ArrowRight','BUTTON');
assert.equal(elements.get('step').textContent,'01');
press('ArrowLeft','BUTTON');
assert.equal(elements.get('step').textContent,'00');
press('ArrowRight','INPUT');
assert.equal(elements.get('step').textContent,'00');
press('Space','BUTTON');
assert.equal(vm.runInContext('timer',context), null);
press('Space','DIV');
assert.equal(vm.runInContext('timer',context), 1);
console.log('Visualization regression checks passed.');
vm.runInContext(fs.readFileSync(path.join(__dirname, 'results.js'), 'utf8'), context);
vm.runInContext(`
const textPath = '初始状态：\\n1 2 3\\n4 5 6\\n7 # 8\\n第 1 步，空格移动：E\\n1 2 3\\n4 5 6\\n7 8 #\\n总步数：1';
assert.equal(parseResults(textPath).length,1);
assert.equal(parseResults(textPath)[0].states.length,2);
assert.equal(parseResults(textPath)[0].goal,undefined);
assert.equal(parseResults(textPath+'\\n'+textPath).length,2);
assert.throws(()=>parseResults(textPath.replace('总步数：1','总步数：2')));
assert.throws(()=>parseResults('1 2 3\\n4 5 6'));
assert.throws(()=>parseResults('没有棋盘'));
assert.equal(parseResults(JSON.stringify({results:[{states:[target],goal:target}]})).length,1);
assert.throws(()=>parseResults('{"results":[]}'));
assert.equal(parseResults(textPath.replaceAll('1 2 3','| 1 | 2 | 3 |'))[0].states.length,2);
const timed = parseResults(textPath+'\\n启发函数：MANHATTAN，解路径长度：1，执行了0.012s，共生成了3个结点，扩展了1个结点')[0];
assert.equal(timed.timeMs,12);
assert.equal(timed.algorithm,undefined);
assert.equal(timed.expanded,1);
for (const [engine,algorithm] of Object.entries({astar:'A*',ida:'IDA*',nida:'NIDA*',dijkstra:'Dijkstra'})) {
  const modern = parseResults(textPath+'\\n搜索引擎：'+engine+'，启发函数：MANHATTAN，解路径长度：1，执行了0.012s，共生成了3个结点，扩展了1个结点')[0];
  assert.equal(modern.algorithm,algorithm);
  assert.equal(modern.heuristic,engine==='dijkstra'?'ZERO':'MANHATTAN');
  assert.equal(modern.timeMs,12);
}
`, context);
console.log('Batch JSON / coursework text regression checks passed.');
vm.runInContext(fs.readFileSync(path.join(__dirname, 'examples.js'), 'utf8'), context);
vm.runInContext(`
assert.equal(window.NPuzzleExamples.results.length,6);
window.NPuzzleExamples.results.forEach((record,i)=>{
  validate(record);
  assert.equal(record.states.length-1,[21,21,31,31,31,31][i]);
  assert.equal(record.source,'Java');
  assert.equal(record.samplesMs.length,5);
  const sorted=record.samplesMs.slice().sort((a,b)=>a-b);
  assert.ok(Math.abs(record.timeMs-sorted[2])<0.000001);
});
assert.match(csvCell('=SUM(A1:A2)'),/^"'/);
assert.equal(csvCell('a"b'),'"a""b"');
assert.ok(exportCsv([{data:window.NPuzzleExamples.results[0],label:'sample'}]).includes('wall-median'));
`,context);
console.log('Real Java examples / median / CSV regression checks passed.');
vm.runInContext(`
downloadFile('test.json',JSON.stringify({schemaVersion:1,results:window.NPuzzleExamples.results}),'application/json');
downloadFile('test.csv',exportCsv([{data:window.NPuzzleExamples.results[0],label:'sample'}]),'text/csv;charset=utf-8');
`,context);
assert.deepEqual(downloadAnchors.map(a=>a.download),['test.json','test.csv']);
assert.ok(downloadAnchors.every(a=>a.clicked && a.removed && a.hidden));
assert.equal(downloadBlobs[0].type,'application/json');
assert.equal(downloadBlobs[1].type,'text/csv;charset=utf-8');
console.log('Download link attachment / activation / cleanup checks passed (not browser disk-save verification).');
const {traceAStar}=require('./search-trace.js');
for(const heuristic of ['manhattan','misplaced']) {
  const trace=traceAStar([1,3,6,5,0,2,4,7,8],heuristic);
  assert.equal(trace.solved,true);
  assert.equal(trace.path.length-1,8);
  let generated=0,expanded=0;
  for(const event of trace.events) {
    assert.equal(event.candidates[0],event.current);
    assert.equal(event.candidates.length,Math.min(6,event.frontierBefore));
    const compare=(a,b)=>a.f-b.f || a.g-b.g || a.id-b.id;
    for(let i=1;i<event.candidates.length;i++)assert.ok(compare(trace.nodes[event.candidates[i-1]],trace.nodes[event.candidates[i]])<=0);
    generated+=event.children.length;
    if(!event.found)expanded++;
    assert.equal(event.generated,generated);
    assert.equal(event.expanded,expanded);
    for(const child of event.children)assert.equal(trace.nodes[child.id].parent,event.current);
  }
  for(let i=1;i<trace.path.length;i++) {
    const a=trace.path[i-1].indexOf(0),b=trace.path[i].indexOf(0);
    assert.equal(Math.abs(a%3-b%3)+Math.abs(Math.floor(a/3)-Math.floor(b/3)),1);
  }
}
assert.equal(traceAStar([1,2,3,4,5,6,7,8,0]).events[0].expanded,0);
assert.equal(traceAStar([1,3,6,5,0,2,4,7,8],'manhattan',1).truncated,true);
assert.throws(()=>traceAStar([1,1,2,3,4,5,6,7,0]));
assert.throws(()=>traceAStar([1,2,3,4,5,6,7,8,0],'unknown'));
assert.throws(()=>traceAStar([2,1,3,4,5,6,7,8,0]));
console.log('Search process trace regression checks passed.');
require('./search-lab-test.cjs');
