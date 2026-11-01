'use strict';
(() => {
  const el=id=>document.getElementById(id);
  let trace,position=0,clock=null;
  let selectedId=null;
  const candidatePanel=document.createElement('section');candidatePanel.className='trace-candidates';
  const candidateHeading=document.createElement('h3');candidateHeading.textContent='选取前 · Frontier 前 6 名';
  const candidateNote=document.createElement('p');candidateNote.className='hint';
  const candidateList=document.createElement('div');candidateList.className='moves';
  candidatePanel.append(candidateHeading,candidateNote,candidateList);el('trace-graph').before(candidatePanel);
  const sourceNote=document.createElement('p');sourceNote.className='hint';sourceNote.textContent='教学版使用固定标准目标；同 f、g 时按候选生成顺序排序。Java PriorityQueue 不保证相同平局顺序，故本图不代表 Java 的逐节点轨迹或性能数据。';candidatePanel.append(sourceNote);
  const presets=[[1,2,3,4,0,6,7,5,8],[1,3,6,5,0,2,4,7,8],[5,0,8,4,2,1,7,3,6]];
  const labels={frontier:'加入 Frontier',replaced:'更短路径替换', 'closed-pruned':'Closed 重复剪枝','duplicate-pruned':'Frontier 重复剪枝'};
  function stop(){clearTimeout(clock);clock=null;el('trace-play').textContent='▶ 播放搜索';}
  function board(root,values){root.replaceChildren(); for(const v of values){const cell=document.createElement('span');cell.textContent=v||'';root.append(cell);}}
  function detail(id){selectedId=id;const n=trace.nodes[id];board(el('trace-board'),n.board);el('trace-detail').textContent=`正在查看节点 #${id} · g = ${n.g} · h = ${n.h} · f = ${n.f} · 父节点 ${n.parent===null?'无':`#${n.parent}`}`;}
  function card(id,title,status){const button=document.createElement('button');button.className=`trace-node ${status}`;const n=trace.nodes[id];const caption=document.createElement('strong');caption.textContent=title;const miniature=document.createElement('div');miniature.className='trace-mini';board(miniature,n.board);const costs=document.createElement('small');costs.textContent=`#${id} · g ${n.g} + h ${n.h} = f ${n.f}`;button.append(caption,miniature,costs);button.onclick=()=>detail(id);return button;}
  function svgNode(name,attributes){const node=document.createElementNS('http://www.w3.org/2000/svg',name);for(const [key,value] of Object.entries(attributes))node.setAttribute(key,value);return node;}
  function chart(){const svg=el('trace-chart');svg.replaceChildren();const history=trace.events.slice(0,position+1);const max=trace.chartMax;
    for(const fraction of [0,0.5,1]) {
      const y=150-fraction*125;
      svg.append(svgNode('line',{x1:25,x2:575,y1:y,y2:y,stroke:'#dce6ef','stroke-width':1}));
      const label=svgNode('text',{x:26,y:y-4,fill:'#526677','font-size':10});label.textContent=String(Math.round(max*fraction));svg.append(label);
    }
    for(const [key,color] of [['generated','#004494'],['expanded','#00a6d8'],['frontier','#b99a53']]){const points=history.map((e,i)=>`${25+i/Math.max(1,trace.events.length-1)*550},${150-e[key]/max*125}`).join(' ');svg.append(svgNode('polyline',{points,fill:'none',stroke:color,'stroke-width':3}));svg.append(svgNode('circle',{cx:25+position/Math.max(1,trace.events.length-1)*550,cy:150-history.at(-1)[key]/max*125,r:4,fill:color}));}
    const text=svgNode('text',{x:25,y:180,fill:'#526677','font-size':12});text.textContent=`横轴：取出节点次数（0–${trace.events.length-1}）；纵轴：节点数（0–${max}）`;svg.append(text);
    const cursorX=25+position/Math.max(1,trace.events.length-1)*550;
    svg.append(svgNode('line',{x1:cursorX,x2:cursorX,y1:25,y2:150,stroke:'#8399ad','stroke-dasharray':'4 3'}));
  }
  function render(){const event=trace.events[position];el('trace-progress').max=trace.events.length-1;el('trace-progress').value=position;el('trace-count').textContent=`${position} / ${trace.events.length-1}`;
    el('trace-stats').textContent=`本帧结束后：累计生成 ${event.generated} · 累计扩展 ${event.expanded} · Frontier ${event.frontier}`;
    el('trace-message').textContent=event.found?`找到目标：${trace.path.length-1} 步。目标出队不计为扩展。`:position===trace.events.length-1&&trace.truncated?`已达 ${trace.limit} 次取出上限；这是截断轨迹，不表示无解。`:'一帧表示取出一个节点并完成其扩展；按最小 f、较小 g、候选生成顺序选择。点击节点查看详情。';
    if(position===0)el('trace-message').textContent='第 0 帧：根节点已入队，尚未取出或扩展。生成 0、扩展 0、Frontier 1。';
    candidateList.replaceChildren();candidateNote.textContent=`选取前共 ${event.frontierBefore} 个候选，展示 ${event.candidates.length} 个。第 1 名为本帧取出节点；这是扩展前快照。`;
    if(position===0)candidateNote.textContent='初始 Frontier 只有根节点；尚未执行选取。下一帧将取出排名第 1 的根节点。';
    event.candidates.forEach((id,i)=>{const n=trace.nodes[id],button=document.createElement('button');button.textContent=`${i+1}. #${id} · f=${n.f} · g=${n.g} · h=${n.h}`;button.className=i===0?'trace-first':'';button.onclick=()=>detail(id);candidateList.append(button);});
    const root=el('trace-graph');root.replaceChildren();const current=trace.nodes[event.current];
    if(current.parent!==null){const parent=document.createElement('div');parent.className='trace-level';parent.append(card(current.parent,'父节点','closed'));root.append(parent);}
    if(current.parent!==null){const arrow=document.createElement('p');arrow.className='trace-arrow';arrow.textContent='↓';root.append(arrow);}
    const active=document.createElement('div');active.className='trace-level';active.append(card(event.current,position===0?'根节点 · 尚未取出':event.found?'目标节点':'当前取出节点','active'));root.append(active);
    const children=document.createElement('div');children.className='trace-level trace-children';for(const child of event.children)children.append(card(child.id,`${child.direction} · ${labels[child.status]}`,child.status));root.append(children);
    if(selectedId===null)selectedId=event.current;
    detail(selectedId);chart();el('trace-prev').disabled=position===0;el('trace-next').disabled=position===trace.events.length-1;
  }
  function jump(value){stop();position=Math.max(0,Math.min(trace.events.length-1,value));selectedId=null;render();}
  function tick(){clock=null;if(position===trace.events.length-1){stop();return;}position++;selectedId=null;render();if(position===trace.events.length-1){stop();return;}clock=setTimeout(tick,Number(el('trace-speed').value));}
  function rebuild(){stop();trace=traceAStar(presets[Number(el('trace-sample').value)],el('trace-heuristic').value,300);trace.events.unshift({current:0,candidates:[0],frontierBefore:1,children:[],generated:0,expanded:0,frontier:1,found:false});position=0;selectedId=null;render();}
  el('trace-play').onclick=()=>{if(clock){stop();return;}if(position===trace.events.length-1)position=0;selectedId=null;render();el('trace-play').textContent='Ⅱ 暂停搜索';clock=setTimeout(tick,Number(el('trace-speed').value));};
  el('trace-prev').onclick=()=>jump(position-1);el('trace-next').onclick=()=>jump(position+1);el('trace-reset').onclick=()=>jump(0);el('trace-progress').oninput=e=>jump(Number(e.target.value));
  el('trace-sample').onchange=rebuild;el('trace-heuristic').onchange=rebuild;
  el('trace-speed').onchange=()=>{if(clock){clearTimeout(clock);clock=setTimeout(tick,Number(el('trace-speed').value));}};
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
  rebuild();
})();
