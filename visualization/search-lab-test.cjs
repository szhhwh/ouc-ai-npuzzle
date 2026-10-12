const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {traceAStar} = require('./search-trace.js');
const elements=new Map(), timers=new Map();let nextTimer=1;
function element() { return {children:[],value:'',textContent:'',attributes:{},append(...nodes){this.children.push(...nodes);},replaceChildren(...nodes){this.children=[...nodes];},before(node){this.beforeNode=node;},setAttribute(k,v){this.attributes[k]=v;}}; }
function get(id){if(!elements.has(id))elements.set(id,element());return elements.get(id);}
get('trace-sample').value='1';get('trace-heuristic').value='manhattan';get('trace-speed').value='500';
const context=vm.createContext({document:{getElementById:get,createElement:element,createElementNS:element,addEventListener(){}},traceAStar,setTimeout(callback,delay){const id=nextTimer++;timers.set(id,{callback,delay});return id;},clearTimeout(id){timers.delete(id);}});
vm.runInContext(fs.readFileSync(path.join(__dirname,'search-lab.js'),'utf8'),context);
assert.equal(get('trace-count').textContent,'0 / 13');
assert.match(get('trace-message').textContent,/尚未取出或扩展/);
assert.match(get('trace-stats').textContent,/生成 0 · 累计扩展 0 · Frontier 1/);
const scaleLabel=()=>get('trace-chart').children.find(n=>n.textContent.startsWith('横轴')).textContent;
const initialScale=scaleLabel();
get('trace-next').onclick();assert.equal(get('trace-count').textContent,'1 / 13');assert.equal(scaleLabel(),initialScale);
get('trace-play').onclick();assert.equal(timers.size,1);
get('trace-speed').value='150';get('trace-speed').onchange();assert.equal(timers.size,1);assert.equal([...timers.values()][0].delay,150);
for(let guard=0;timers.size && guard<20;guard++){const [id,{callback}]=timers.entries().next().value;timers.delete(id);callback();}
assert.equal(get('trace-count').textContent,'13 / 13');assert.equal(timers.size,0);assert.equal(get('trace-play').textContent,'▶ 播放搜索');assert.match(get('trace-message').textContent,/找到目标/);
get('trace-reset').onclick();assert.equal(get('trace-count').textContent,'0 / 13');
get('trace-heuristic').value='misplaced';get('trace-heuristic').onchange();assert.equal(get('trace-count').textContent,'0 / 19');
const panel=get('trace-graph').beforeNode;const buttons=panel.children[2].children;
buttons[0].onclick();assert.match(get('trace-detail').textContent,/正在查看节点 #0/);
console.log('Search lab controls / fixed scale / playback completion checks passed.');
