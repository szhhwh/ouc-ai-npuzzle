// Run after Maven compile; temporary fixtures never enter the repository.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname,'..');
const temp = fs.mkdtempSync(path.join(os.tmpdir(),'npuzzle-export-'));
const goal = '1 2 3 4 5 6 7 8 0';
let count = 0;
function run(input, engine='astar', success=true) {
  const file = path.join(temp,`${count++}.txt`), output = file+'.json';
  fs.writeFileSync(file,input);
  const args=['-cp',path.join(root,'target/classes'),'stud.g01.runner.SolutionExporter',file,output,engine,'MANHATTAN','2'];
  const result=spawnSync('java',args,{encoding:'utf8',timeout:15000});
  if(success) {
    assert.equal(result.status,0,result.stderr);
    const data=JSON.parse(fs.readFileSync(output,'utf8')).results[0];
    assert.equal(data.samplesMs.length,2);
    assert.ok(Math.abs(data.timeMs-(data.samplesMs[0]+data.samplesMs[1])/2)<0.000001);
    const before=fs.readFileSync(output,'utf8');
    assert.notEqual(spawnSync('java',args,{encoding:'utf8',timeout:15000}).status,0);
    assert.equal(fs.readFileSync(output,'utf8'),before);
    return data;
  }
  assert.notEqual(result.status,0);
  assert.equal(fs.existsSync(output),false);
}
assert.equal(run(`3 ${goal} ${goal}`).states.length,1);
assert.equal(run(`3 1 2 3 4 5 6 7 0 8 ${goal}`,'ida').states.length,2);
assert.equal(run(`4 1 2 3 4 5 6 7 8 9 10 11 12 13 14 0 15 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 0`).states.length,2);
run(`3 2 1 3 4 5 6 7 8 0 ${goal}`,'astar',false);
run(`3 1 1 3 4 5 6 7 8 0 ${goal}`,'astar',false);
run('3 1 2','astar',false);
run('','astar',false);
console.log('Exporter boundary checks passed. Temporary fixtures:',temp);
