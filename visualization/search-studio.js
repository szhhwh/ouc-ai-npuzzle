/* Replay observations from the real Java queue; no browser search or invented tie-break. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const data = window.NPuzzleJavaTraces;
  let trace, nodes, shots, position = 0, timer, inspected = null, expanded = false, fit = true;
  const status = {frontier:'加入 Open', replaced:'替换旧候选', 'duplicate-pruned':'Open 已有更优或等价', 'closed-pruned':'已在 Closed · 剪枝'};
  function stop() { clearTimeout(timer); timer = null; $('play').textContent = '▶ 播放算法'; }
  function load() {
    stop(); trace = data.traces.find(t => t.preset === Number($('preset').value) && t.heuristic === $('heuristic').value);
    nodes = new Map(trace.nodes.map(n => [n.id,n])); shots = [{phase:'ready', round:-1}];
    trace.frames.forEach((frame, round) => {
      for (const phase of ['f','g','pick']) shots.push({phase,round});
      if (frame.found) shots.push({phase:'goal',round});
      else { frame.children.forEach((_, child) => shots.push({phase:'child',round,child})); shots.push({phase:'merge',round}); }
    });
    position = 0; inspected = null; $('timeline').max = shots.length - 1;
    $('source').textContent = `${data.source} · ${data.sourceRevision} · 已核对原始搜索结果`;
    render();
  }
  function miniature(n) { return `<div class="mini-board">${n.board.map(v=>`<span class="${v?'':'blank'}">${v||''}</span>`).join('')}</div>`; }
  function card(n, classes = '', badge = '') {
    return `<button class="candidate ${classes} ${inspected===n.id?'inspected':''}" data-node="${n.id}" aria-label="查看节点 ${n.id}"><span class="node-title">#${n.id}${badge?` · ${badge}`:''}</span>${miniature(n)}<span class="cost-line">${n.g} + ${n.h} = <b>${n.f}</b></span></button>`;
  }
  function direction(a,b) { const d=b.board.indexOf(0)-a.board.indexOf(0); return ({'-3':'U','3':'D','-1':'L','1':'R'})[d]; }
  function render() {
    const shot=shots[position], frame=trace.frames[shot.round], phase=shot.phase;
    const root=trace.nodes.find(n=>n.parent===null);
    const current=frame?nodes.get(frame.current):root;
    let openIds=!frame?[root.id]:(phase==='merge'?frame.openAfter:frame.openBefore);
    if(frame&&['child','goal'].includes(phase)) {
      openIds=frame.openBefore.filter(id=>id!==frame.current);
      if(phase==='child') frame.children.slice(0,shot.child+1).forEach(id=>{
        const n=nodes.get(id);
        if(n.status==='frontier'||n.status==='replaced') {
          if(n.status==='replaced')openIds=openIds.filter(old=>old!==n.reference);
          openIds.push(id);
        }
      });
      openIds.sort((a,b)=>nodes.get(a).f-nodes.get(b).f||nodes.get(a).g-nodes.get(b).g||a-b);
    }
    const open=openIds.map(id=>nodes.get(id));
    const minF=Math.min(...open.map(n=>n.f)), minG=Math.min(...open.filter(n=>n.f===minF).map(n=>n.g));
    const selected=phase==='pick';
    const titles={ready:'起点进入 Open', f:`先找最小 f = ${minF}`,g:`同 f 时，再选较小 g = ${minG}`,pick:`取出节点 #${current.id}`,child:'计算后继权值',merge:'更新 Open，开始下一轮',goal:`找到目标 · ${current.g} 步`};
    const words={ready:'f = g + h：已走代价 + 剩余估计。',f:'金色标出最小权值候选。',g:'权值相同，按项目规则比较已走代价。',pick:'从整个 Open 中选择，不限于刚生成的后继。',child:'逐个估值；重复状态按项目规则剪枝。',merge:'保留可用候选，再从全局比较权值。',goal:'金色连线标出最终解路径。'};
    const tied=frame?frame.openBefore.map(id=>nodes.get(id)).filter(n=>n.f===current.f&&n.g===current.g):[];
    if(phase==='pick'&&tied.length>1) words.pick='f、g 均相同：展示 Java 队列本次真实选择。';
    if(position===shots.length-1&&trace.truncated) words.merge='前 80 轮演示结束；搜索未录完，不代表无解。';
    $('headline').textContent=titles[phase]; $('explanation').textContent=words[phase];
    $('shot-tag').textContent=frame?`第 ${shot.round+1} 轮`:'起点';
    document.querySelectorAll('[data-phase]').forEach(el=>el.classList.toggle('active',el.dataset.phase===(phase==='goal'?'pick':phase)));
    let shown=expanded?open:open.slice(0,8);
    if(selected&&openIds.includes(current.id)&&!shown.includes(current)) shown=[...shown.slice(0,7),current];
    $('open-list').innerHTML=shown.map(n=>card(n,`${['f','g'].includes(phase)&&n.f===minF?'f-min':''} ${phase==='g'&&n.f===minF&&n.g===minG?'g-min':''} ${selected&&n.id===current.id?'winner':''}`)).join('')||'<p class="small">Open 已清空</p>';
    $('open-caption').textContent=`${open.length} 个候选 · f 小优先，同 f 比 g${!expanded&&open.length>8?' · 显示 8 个':''}`;
    $('show-open').textContent=expanded?'收起':'展开全部'; $('show-open').disabled=open.length<=8;
    const revealed=phase==='child'?frame.children.slice(0,shot.child+1):phase==='merge'?frame.children:[];
    $('expansion').innerHTML=card(current,'parent-node',phase==='goal'?'目标':'父节点')+'<span class="arrow">→</span><div class="children">'+(revealed.length?revealed.map((id,i)=>{const n=nodes.get(id);return card(n,`${n.status.endsWith('pruned')?'bad':''} ${phase==='child'&&i===revealed.length-1?'child-active':''}`,`${direction(current,n)} · ${status[n.status]||n.status}`);}).join(''):'<div class="child-placeholder">'+(phase==='goal'?'目标已到达':'等待展开')+'</div>')+'</div>';
    const focus=nodes.get(inspected) || (phase==='child'?nodes.get(revealed.at(-1)):current);
    $('inspect-title').textContent=`节点 #${focus.id}`;
    $('cost-g').textContent=focus.g; $('cost-h').textContent=focus.h; $('cost-f').textContent=focus.f;
    $('heuristic-proof').textContent=trace.heuristic==='MANHATTAN'?'h：数字到目标的横纵距离之和。':'h：未归位的数字个数。';
    $('comparison-proof').textContent=Number.isFinite(minF)?`Open 最小 f：${minF} · 其中最小 g：${minG}`:'Open 无候选';
    $('inspect-note').textContent=inspected!==null?'查看所选节点 · 切换分镜恢复跟随':'点击候选或树节点，查看棋盘与权值。';
    board(focus.board);
    const visible=new Set([root.id]);
    trace.frames.slice(0,Math.max(0,shot.round)).forEach(f=>f.children.forEach(id=>visible.add(id)));
    revealed.forEach(id=>visible.add(id));
    drawTree(visible,new Set(openIds),current,phase==='goal');
    $('tree-note').textContent=phase==='goal'?'完整搜索树 · 金色为最终解路径':'生成树逐步生长 · 点击节点查看；重复状态单独标记。';
    $('position').textContent=`${position+1} / ${shots.length}`;
    $('timeline').value=position; $('previous').disabled=position===0; $('next').disabled=position===shots.length-1;
  }
  let oldBoard;
  function board(values) {
    const el=$('focus-board');
    if(!el.children.length) for(let v=0;v<9;v++){const tile=document.createElement('span');tile.className=`focus-tile ${v?'':'blank'}`;tile.dataset.value=v;tile.textContent=v||'';el.append(tile);}
    const changes=oldBoard?values.filter((v,i)=>v!==oldBoard[i]).length:9;
    el.classList.toggle('instant',changes!==2); void el.offsetWidth;
    [...el.children].forEach(tile=>{const i=values.indexOf(Number(tile.dataset.value)),cell=el.clientWidth/3;tile.style.transform=`translate(${i%3*cell}px,${Math.floor(i/3)*cell}px)`;}); oldBoard=values.slice();
  }
  let previousTree = new Set(), previousTreeStep = -1;
  function drawTree(visible,open,current,goal) {
    const list=[...visible].map(id=>nodes.get(id)).filter(n=>$('pruned').checked||!n.status.endsWith('pruned'));
    const byParent=new Map();list.forEach(n=>{if(!byParent.has(n.parent))byParent.set(n.parent,[]);byParent.get(n.parent).push(n);});
    let leaf=0;const coords=new Map();
    function walk(n){const kids=byParent.get(n.id)||[];kids.forEach(walk);coords.set(n.id,{x:kids.length?kids.reduce((s,k)=>s+coords.get(k.id).x,0)/kids.length:45+leaf++*64,y:35+n.g*68});}
    walk(list.find(n=>n.parent===null));
    const viewport=$('tree-viewport'),width=Math.max(viewport.clientWidth,leaf*64+30),height=Math.max(160,...list.map(n=>35+n.g*68+40));
    const scale=fit?Math.max(.45,Math.min(1,viewport.clientWidth/width)):1,svg=$('search-tree');
    svg.setAttribute('viewBox',`0 0 ${width} ${height}`);svg.setAttribute('width',width*scale);svg.setAttribute('height',height*scale);
    const solution=new Set(goal?trace.solution:[]);
    svg.innerHTML=list.map(n=>{const p=coords.get(n.parent),c=coords.get(n.id);return p?`<path class="tree-link ${solution.has(n.id)&&solution.has(n.parent)?'solution':''}" d="M${p.x},${p.y+17} L${c.x},${c.y-17}"/>`:'';}).join('')+list.map(n=>{const p=coords.get(n.id);return `<g class="tree-node ${!previousTree.has(n.id)?'new-node':''} ${open.has(n.id)?'open':''} ${n.id===current.id?'active':''} ${n.status.endsWith('pruned')?'pruned':''} ${solution.has(n.id)?'solution':''} ${inspected===n.id?'inspect':''}" transform="translate(${p.x},${p.y})" data-node="${n.id}" tabindex="0" role="button" aria-label="节点 ${n.id}，g ${n.g} h ${n.h} f ${n.f}"><title>#${n.id} · g ${n.g} + h ${n.h} = f ${n.f}</title><circle r="18"/><text text-anchor="middle" y="-1">#${n.id}</text><text text-anchor="middle" y="11">f ${n.f}</text></g>`;}).join('');
    previousTree=new Set(list.map(n=>n.id));
    if(previousTreeStep!==position&&coords.has(current.id)){
      const p=coords.get(current.id);viewport.scrollTo({left:Math.max(0,p.x*scale-viewport.clientWidth/2),top:Math.max(0,p.y*scale-viewport.clientHeight/2),behavior:'auto'});
    }
    previousTreeStep=position;
  }
  function step(delta){stop();position=Math.max(0,Math.min(shots.length-1,position+delta));inspected=null;render();}
  function tick(){if(position>=shots.length-1){stop();return;}timer=setTimeout(()=>{position++;inspected=null;render();tick();},Number($('speed').value));}
  $('play').onclick=()=>{if(timer){stop();return;}if(position===shots.length-1)position=0;$('play').textContent='Ⅱ 暂停';render();tick();};
  $('previous').onclick=()=>step(-1);$('next').onclick=()=>step(1);$('restart').onclick=()=>step(-position);
  $('timeline').oninput=()=>{stop();position=Number($('timeline').value);inspected=null;render();};
  $('preset').onchange=load;$('heuristic').onchange=load;
  $('speed').onchange=()=>{if(timer){clearTimeout(timer);tick();}};
  $('show-open').onclick=()=>{expanded=!expanded;render();};$('pruned').onchange=render;
  $('fit-tree').onclick=()=>{fit=true;render();};$('actual-tree').onclick=()=>{fit=false;render();};
  $('cinema').onclick=()=>{document.body.classList.toggle('cinema');$('cinema').textContent=document.body.classList.contains('cinema')?'退出录屏':'录屏布局';render();};
  document.addEventListener('click',event=>{const target=event.target.closest('[data-node]');if(target){inspected=Number(target.dataset.node);render();}});
  document.addEventListener('keydown',event=>{if(event.target.matches('.tree-node')&&['Enter',' '].includes(event.key)){event.preventDefault();inspected=Number(event.target.dataset.node);render();}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
  window.addEventListener('resize',()=>render());load();
})();
