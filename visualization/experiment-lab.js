/* Workbook statistics are separate from replayable paths and browser teaching traces. */
'use strict';
(function(scope) {
  const groups = {
    stage1: {label:'阶段一 · 八数码',prefix:'s1-',note:'每实例预热 1 次、测量 5 次；仅显示三个有实例号的课程八数码。'},
    stage2: {label:'阶段二 · 十五数码',prefix:'s2-',note:'每实例测量 3 次取中位数。A*/IDA* 使用曼哈顿，NIDA* 使用 7–8 PDB。'},
    stage3: {label:'阶段三 · 全部十例',prefix:'s3-',note:'前 3 例为八数码（PDB 为 3–3–2），后 7 例为十五数码（PDB 为 7–8）。IDA* 错位数限时 60s、曼哈顿限时 90s；A* 堆上限 6GB。'},
    benchmark: {label:'八数码 · 深度分层',prefix:'b8-',note:'深度 8–24，每层 8 个实例。耗时/扩展数取该层中位数，Open/内存取该层最大值；不是单次运行。'},
    structure: {label:'数据结构 · 深度 8–16',prefix:'st-v',note:'45 个实例，统一 A* + 曼哈顿。耗时/扩展数取实例中位数，Open/Closed/内存取最大值。'},
    hardStructure: {label:'数据结构 · 深度 20–24',prefix:'st-hard-',note:'25 个实例。耗时/扩展数取实例中位数，Open/Closed/内存取最大值；不能据一个中位数断言所有实例都更快。'},
    structure15: {label:'数据结构 · 十五数码',prefix:'st15-',note:'比较允许重复的堆与带状态索引的堆。另有一条有序表超时记录缺少实例号，未强行对应到实例 1。'}
  };
  const metrics = {timeMs:'求解时间 / ms',expanded:'扩展节点',openPeak:'Open 峰值 / 节点',processPeakMb:'进程峰值 / 来源表 MB'};
  const colors = ['#004494','#00a6d8','#c8102e','#b89953','#8662ac','#497b56'];
  const median = values => {const a=values.slice().sort((a,b)=>a-b);return a.length?(a[Math.floor((a.length-1)/2)]+a[Math.floor(a.length/2)])/2:null;};
  function label(r) {
    const engine={astar:'A*',ida:'IDA*',nida:'NIDA*',dijkstra:'Dijkstra'}[r.engine] || r.engine;
    if(r.variant!=='PROD')return {LIST_ALL:'① 有序表 + 线性 Closed',LIST_HASH:'② 有序表 + 哈希 Closed',PQ_HASH:'③ 二叉堆 + 哈希 Closed',INDEXED_HASH:'④ 二叉堆 + 索引 + 哈希 Closed'}[r.variant] || r.variant;
    return `${engine} · ${{MANHATTAN:'曼哈顿',MISPLACED:'错位数',DISJOINT_PATTERN:'PDB'}[r.heuristic] || r.heuristic}`;
  }
  function recordsFor(data,kind) {
    if(!groups[kind])throw new Error('未知数据集');
    return data.records.filter(r=>r.config.startsWith(groups[kind].prefix) && Number.isSafeInteger(r.instance) && !(kind==='structure' && r.config.endsWith('-full')));
  }
  function seriesFor(data,kind,metric) {
    if(!metrics[metric])throw new Error('未知指标');
    const buckets=new Map();
    for(const r of recordsFor(data,kind)) {
      const x=kind==='benchmark'?r.length:['structure','hardStructure'].includes(kind)?'全部实例':r.instance;
      const key=`${r.config}/${x}`;
      if(!buckets.has(key))buckets.set(key,{config:r.config,label:label(r),x,rows:[]});
      buckets.get(key).rows.push(r);
    }
    return [...buckets.values()].map(b=>{
      const solved=b.rows.filter(r=>r.status==='solved');
      const values=solved.map(r=>r[metric]).filter(v=>Number.isFinite(v)&&v>=0);
      const value=values.length ? (['openPeak','processPeakMb'].includes(metric)?Math.max(...values):median(values)) : null;
      return {...b,rows:undefined,value,count:b.rows.length,solved:solved.length,status:solved.length?'solved':b.rows[0].status,
        limitMs:b.rows[0].timeoutLimitMs || null};
    });
  }
  function verify(data) {
    if(!data || data.schemaVersion!==1 || !Array.isArray(data.records) || data.records.length!==data.metadata.recordCount)throw new Error('实验数据格式或记录数不一致');
    for(const r of data.records) {
      if(!['solved','timeout','oom'].includes(r.status))throw new Error('未知实验状态');
      if(r.status!=='solved' && r.timeMs!==null)throw new Error('失败实验不能具有成功耗时');
      for(const key of Object.keys(metrics))if(r[key]!==null && (!Number.isFinite(r[key]) || r[key]<0))throw new Error('指标非法');
    }
    return data;
  }
  const api={groups,metrics,median,label,recordsFor,seriesFor,verify};
  if(typeof module!=='undefined' && module.exports)module.exports=api;
  scope.NPuzzleExperiment=api;
  if(typeof document==='undefined')return;
  const el=id=>document.getElementById(id),node=(tag,text,className)=>{const n=document.createElement(tag);if(text!==undefined)n.textContent=text;if(className)n.className=className;return n;};
  const format=v=>v==null?'—':v.toLocaleString('zh-CN',{maximumFractionDigits:4});
  const data=scope.NPuzzleLabData;
  try{verify(data);}catch(e){el('lab-source').textContent=`实验数据无法载入：${e.message}`;return;}
  const meta=data.metadata;
  el('lab-source').textContent=`数据来源：${meta.sourceFile}「${meta.rawSheet}」· ${meta.recordCount.toLocaleString()} 条明细。${meta.environment}。实验对应提交未提供；代码说明核对至 ${meta.codeReviewedRevision}，不是在本机重新测得。`;
  for(const [key,g] of Object.entries(groups)){const o=node('option',g.label);o.value=key;el('lab-dataset').append(o);}
  el('lab-dataset').value='stage2';
  for(const [key,text] of Object.entries(metrics)){const o=node('option',text);o.value=key;el('lab-metric').append(o);}
  el('lab-issues').textContent=`数据检查：${meta.issues.length} 条失败记录缺少实例号，不加入按实例比较。原表 -1 哨兵按未测量显示；超时/OOM 不记为 0。图表指标仅汇总已解记录，失败运行的内存观测值保留在下载明细中。原始表未提供路径、逐轮样本或建库是否计入本次 search 的明细，故不据此生成回放或精确加速比。`;
  const details=node('details'),summary=node('summary','来源校验与异常记录');details.append(summary,node('p',`SHA-256：${meta.sha256}`));
  for(const issue of meta.issues)details.append(node('p',`原表第 ${issue.sourceRow} 行 · ${issue.config}：${issue.reason}`));
  el('lab-audit').append(details);
  function statusText(p){return p.status==='oom'?'OOM（6GB 堆）':p.status==='timeout'?(p.limitMs?`超时 ≥${p.limitMs/1000}s`:'超时（上限未知）'):'未测量';}
  function svgNode(tag,attrs={}){const n=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [k,v] of Object.entries(attrs))n.setAttribute(k,v);return n;}
  function lineChart(points,configs,scale,metric) {
    const svg=svgNode('svg',{viewBox:'0 0 820 320',role:'img','aria-label':`八数码分层${metrics[metric]}曲线`});
    for(let i=0;i<=4;i++){
      const y=260-i*55,raw=scale.inverse(i/4),line=svgNode('line',{x1:72,y1:y,x2:790,y2:y,stroke:'#dfe8ef'}),text=svgNode('text',{x:64,y:y+4,'text-anchor':'end',fill:'#526677','font-size':12});text.textContent=format(raw);svg.append(line,text);
    }
    for(let depth=8;depth<=24;depth+=2){const text=svgNode('text',{x:72+(depth-8)/16*718,y:283,'text-anchor':'middle',fill:'#526677','font-size':12});text.textContent=depth;svg.append(text);}
    for(const [i,config]of configs.entries()){
      const values=points.filter(p=>p.config===config && p.value!==null).sort((a,b)=>a.x-b.x);
      svg.append(svgNode('polyline',{points:values.map(p=>`${72+(p.x-8)/16*718},${260-scale.map(p.value)*220}`).join(' '),fill:'none',stroke:colors[i%colors.length],'stroke-width':2.5}));
      for(const p of values){const dot=svgNode('circle',{cx:72+(p.x-8)/16*718,cy:260-scale.map(p.value)*220,r:3,fill:colors[i%colors.length]});const title=svgNode('title');title.textContent=`深度 ${p.x} · ${p.label}：${format(p.value)}（${p.count}例${['openPeak','processPeakMb'].includes(metric)?'最大值':'中位数'}）`;dot.append(title);svg.append(dot);}
    }
    const caption=svgNode('text',{x:72,y:311,fill:'#526677','font-size':12});caption.textContent='横轴：表内最优步数；每层 8 例。纵轴单位见指标选择，刻度见上方说明。';svg.append(caption);el('lab-chart').append(svg);
  }
  function render() {
    const kind=el('lab-dataset').value,metric=el('lab-metric').value,points=seriesFor(data,kind,metric),records=recordsFor(data,kind);
    const configs=[...new Set(points.map(p=>p.config))].sort();
    const finite=points.map(p=>p.value).filter(v=>v!==null),useLog=finite.length>0 && el('lab-log').checked && finite.every(v=>v>0);
    const low=useLog?Math.floor(Math.log10(Math.min(...finite))):0,high=useLog?Math.max(low+1,Math.ceil(Math.log10(Math.max(...finite)))):Math.max(1,...finite);
    const scale={map:v=>useLog?(Math.log10(v)-low)/(high-low):v/high,inverse:f=>useLog?10**(low+f*(high-low)):f*high};
    el('lab-note').textContent=groups[kind].note+' 生成/扩展定义受算法和剪枝策略影响，跨算法不把节点计数直接当作速度。';
    el('lab-summary').textContent=`已解 ${records.filter(r=>r.status==='solved').length} / ${records.length} 条 · ${metrics[metric]} · ${useLog?'对数刻度（每一等距代表相同倍数）':'线性刻度'}。未测量项保持空缺，失败项单独标注。`;
    const legend=el('lab-legend');legend.replaceChildren();for(const [i,config]of configs.entries()){const item=node('span',points.find(p=>p.config===config).label);item.style.setProperty('--series',colors[i%colors.length]);legend.append(item);}
    const chart=el('lab-chart');chart.replaceChildren();
    if(!finite.length)chart.append(node('p','此数据集未提供该指标。'));
    else if(kind==='benchmark')lineChart(points,configs,scale,metric);
    else for(const x of [...new Set(points.map(p=>p.x))].sort((a,b)=>typeof a==='number'?a-b:String(a).localeCompare(String(b)))){
      const card=node('section',undefined,'lab-chart-card');card.append(node('h3',typeof x==='number'?`实例 ${x}`:x));
      for(const config of configs){const p=points.find(p=>p.x===x&&p.config===config);if(!p)continue;const row=node('div',undefined,'lab-bar-row');row.append(node('span',p.label),node('strong',p.value===null?statusText(p):format(p.value)));
        if(p.value!==null){const track=node('div',undefined,'lab-bar-track'),fill=node('div');fill.style.width=`${Math.max(0,Math.min(100,scale.map(p.value)*100))}%`;fill.style.background=colors[configs.indexOf(config)%colors.length];track.append(fill);row.append(track);}else row.classList.add('lab-missing');card.append(row);}
      card.append(node('p',`同尺度范围：${format(scale.inverse(0))}–${format(scale.inverse(1))}。${useLog?'条宽为对数坐标，不代表线性耗时比例。':'条宽从零起算。'}`,'hint'));chart.append(card);
    }
    const table=el('lab-table');table.replaceChildren();for(const p of points.sort((a,b)=>String(a.x).localeCompare(String(b.x),undefined,{numeric:true})||a.config.localeCompare(b.config))){const tr=node('tr');for(const text of[p.x,p.label,p.count,p.value===null?statusText(p):format(p.value)])tr.append(node('td',String(text)));table.append(tr);}
    el('lab-download').onclick=()=>{
      const keys=['config','instance','size','status','length','timeMs','reportedTimeMs','timeoutLimitMs','generated','expanded','openPeak','closed','processPeakMb','engine','heuristic','variant','sourceRow'];
      const all=data.records.filter(r=>r.config.startsWith(groups[kind].prefix) && !(kind==='structure' && r.config.endsWith('-full')));
      downloadFile(`npuzzle-${kind}-source.csv`,'\uFEFF'+[keys.map(csvCell).join(','),...all.map(r=>keys.map(k=>csvCell(r[k])).join(','))].join('\r\n'),'text/csv;charset=utf-8');
    };
  }
  el('lab-dataset').onchange=render;el('lab-metric').onchange=render;el('lab-log').onchange=render;
  el('lab-download-json').onclick=()=>downloadFile('npuzzle-workbook-snapshot.json',JSON.stringify(data,null,2),'application/json');
  render();
})(typeof window!=='undefined'?window:globalThis);
