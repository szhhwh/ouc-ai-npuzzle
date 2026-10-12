// Run with: node visualization/regression.cjs (no external dependencies).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const elements = new Map();
let keyboard;
function element() {
  return {
    style: { setProperty() {} }, classList: { toggle() {} },
    append() {}, replaceChildren() {}, clientWidth: 276, clientHeight: 276, value: '0'
  };
}
const document = {
  getElementById(id) {
    if (!elements.has(id)) elements.set(id, element());
    return elements.get(id);
  },
  querySelector: () => element(), createElement: element,
  addEventListener(name, handler) { if (name === 'keydown') keyboard = handler; }
};
const context = vm.createContext({
  document, window: { addEventListener() {} }, performance, console,
  setTimeout: () => 1, clearTimeout() {}, assert
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
