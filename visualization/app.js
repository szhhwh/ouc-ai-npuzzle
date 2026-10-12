'use strict';
const $ = id => document.getElementById(id);
const samples = [[5,0,8,4,2,1,7,3,6],[6,4,7,8,5,0,3,2,1],[8,6,7,2,5,4,3,0,1]];
let states = [], step = 0, timer = null, heuristic = 'manhattan', size = 3, tiles = new Map();
function stop() { clearTimeout(timer); timer = null; $('play').innerHTML = '▶ <span>播放路径</span>'; }
function render() {
  const current = states[step]; if (!current) return;
  $('board').style.setProperty('--n', size);
  const previous = step ? states[step - 1] : current;
  const moved = step ? previous[current.indexOf(0)] : 0;
  current.forEach((value, index) => {
    if (!value) return;
    let tile = tiles.get(value);
    if (!tile) { tile = document.createElement('div'); tile.className = 'tile'; tile.textContent = value; tiles.set(value, tile); $('board').append(tile); }
    tile.style.transform = `translate(${(index % size) * 100 / size / (1 / size - 7 / $('board').clientWidth)}%, ${Math.floor(index / size) * 100 / size / (1 / size - 7 / $('board').clientHeight)}%)`;
    tile.classList.toggle('moved', value === moved);
  });
  const end = step === states.length - 1;
  $('status').textContent = end ? '已抵达目标 ✓' : timer ? '正在播放' : step ? '逐步探索' : '准备就绪';
  $('step').textContent = String(step).padStart(2,'0'); $('total').textContent = states.length - 1;
  $('progress').max = states.length - 1; $('progress').value = step;
  $('prev').disabled = step === 0; $('next').disabled = end;
  $('move-label').textContent = end ? '每一步都算数。目标状态已达成。' : step ? `第 ${step} 步 · 数字 ${moved} 滑入空格` : '空格记为留白 · 点击播放，开始探索';
}
function advance() { if (step < states.length - 1) step++; if (step === states.length - 1) { stop(); render(); return; } render(); timer = setTimeout(advance, Number($('speed').value)); }
function play() { if (timer) { stop(); render(); return; } if (step === states.length - 1) step = 0; timer = setTimeout(advance, Number($('speed').value)); $('play').innerHTML = 'Ⅱ <span>暂停播放</span>'; render(); }
function jump(value) { stop(); step = Math.max(0, Math.min(states.length - 1, value)); render(); }
$('play').onclick = play; $('prev').onclick = () => jump(step - 1); $('next').onclick = () => jump(step + 1); $('reset').onclick = () => jump(0); $('progress').oninput = e => jump(Number(e.target.value));
document.addEventListener('keydown', e => { if (/INPUT|SELECT|BUTTON/.test(e.target.tagName)) return; if (e.code === 'Space') { e.preventDefault(); play(); } if (e.code === 'ArrowRight') { e.preventDefault(); jump(step + 1); } if (e.code === 'ArrowLeft') { e.preventDefault(); jump(step - 1); } });
class Heap {
  constructor(){this.items=[];}
  better(a,b){return a.f < b.f || (a.f === b.f && a.g < b.g);}
  push(n){const a=this.items;a.push(n);let i=a.length-1;while(i){const p=(i-1)>>1;if(!this.better(a[i],a[p]))break;[a[i],a[p]]=[a[p],a[i]];i=p;}}
  pop(){const a=this.items,top=a[0],last=a.pop();if(a.length){a[0]=last;let i=0;while(true){let j=i,l=2*i+1,r=l+1;if(l<a.length&&this.better(a[l],a[j]))j=l;if(r<a.length&&this.better(a[r],a[j]))j=r;if(j===i)break;[a[i],a[j]]=[a[j],a[i]];i=j;}}return top;}
}
function solve(initial, kind) {
  const start = performance.now(), goal = '1,2,3,4,5,6,7,8,0', closed = new Set(), best = new Map(), heap = new Heap();
  const h = board => board.reduce((s,v,i) => s + (!v ? 0 : kind === 'manhattan' ? Math.abs(Math.floor(i/3)-Math.floor((v-1)/3)) + Math.abs(i%3-(v-1)%3) : Number(v !== i+1)),0);
  const root={board:initial,g:0,f:h(initial),parent:null,key:initial.join(',')};heap.push(root);best.set(root.key,root);let generated=0,expanded=0;
  while(heap.items.length){const node=heap.pop();if(best.get(node.key)!==node)continue;best.delete(node.key);if(node.key===goal){const path=[];for(let p=node;p;p=p.parent)path.push(p.board);return {states:path.reverse(),timeMs:performance.now()-start,generated,expanded};}closed.add(node.key);const blank=node.board.indexOf(0), row=Math.floor(blank/3),col=blank%3;for(const [dr,dc] of [[-1,0],[0,1],[1,0],[0,-1]]){const r=row+dr,c=col+dc;if(r<0||r>=3||c<0||c>=3)continue;generated++;const board=node.board.slice(),target=r*3+c;[board[blank],board[target]]=[board[target],board[blank]];const key=board.join(',');if(closed.has(key))continue;const g=node.g+1;if(best.has(key)&&best.get(key).g<=g)continue;const child={board,key,g,f:g+h(board),parent:node};best.set(key,child);heap.push(child);}expanded++;}throw new Error('当前棋盘不可解');
}
function load(data, source) {
  stop(); states=data.states; size=Math.sqrt(states[0].length);step=0;tiles.clear();$('board').replaceChildren();
  $('length').innerHTML=`${states.length-1}<small>步</small>`;
  $('time').innerHTML=data.timeMs == null ? '—' : `${Number(data.timeMs).toFixed(1)}<small>ms</small>`;
  $('generated').textContent=data.generated == null ? '—' : Number(data.generated).toLocaleString();$('expanded').textContent=data.expanded == null ? '—' : Number(data.expanded).toLocaleString();$('source').textContent=source;document.querySelector('.tag').textContent=`${size*size-1}-PUZZLE`;render();
}
async function run(){stop();$('solve').disabled=true;$('message').textContent='正在计算最优路径…';await new Promise(resolve=>setTimeout(resolve,40));try{load(solve(samples[Number($('sample').value)],heuristic),'浏览器 A* 演示');$('message').textContent='';}catch(e){$('message').textContent=e.message;}finally{$('solve').disabled=false;}}
$('solve').onclick=run;$('sample').onchange=run;
for(const kind of ['manhattan','misplaced'])$(kind).onclick=()=>{heuristic=kind;for(const other of ['manhattan','misplaced'])$(other).classList.toggle('selected',other===kind);$('heuristic-note').textContent=kind==='manhattan'?'所有数字块与目标位置的横纵距离之和，不计空格。':'统计未处于目标位置的数字块数量，不计空格。';run();};
$('import').onclick=()=>$('file').click();
function validate(data){if(!Array.isArray(data.states)||!data.states.length)throw new Error('JSON 需要包含非空的 states 数组');const n=Math.sqrt(data.states[0].length);if(n!==3&&n!==4)throw new Error('仅支持 3×3 或 4×4 棋盘');data.states.forEach((board,i)=>{if(!Array.isArray(board)||board.length!==n*n||new Set(board).size!==n*n||board.some(v=>!Number.isInteger(v)||v<0||v>=n*n))throw new Error(`第 ${i} 个棋盘数据无效`);if(i){const prev=data.states[i-1],a=prev.indexOf(0),b=board.indexOf(0);if(Math.abs(Math.floor(a/n)-Math.floor(b/n))+Math.abs(a%n-b%n)!==1||board.some((v,j)=>v!==(j===a?prev[b]:j===b?0:prev[j])))throw new Error(`第 ${i} 步不是合法滑动`);}});for(const key of ['timeMs','generated','expanded'])if(data[key]!=null&&(!Number.isFinite(data[key])||data[key]<0))throw new Error(`${key} 必须为非负数字`);return data;}
$('file').onchange=async e=>{const file=e.target.files[0];if(!file)return;try{if(file.size>5_000_000)throw new Error('文件请小于 5 MB');const data=validate(JSON.parse(await file.text()));load(data,'导入的 Java 解路径');$('message').textContent='路径已导入，使用播放按钮查看。';}catch(error){$('message').textContent=`导入失败：${error.message}`;}e.target.value='';};
window.addEventListener('resize',render);run();
