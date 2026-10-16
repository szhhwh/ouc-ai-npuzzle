# N-Puzzle 解路径可视化

## 搜索过程实验室（本地预览扩展）

第四板块展示独立浏览器教学 A* 的真实执行轨迹，不是 Java 正式求解器的日志，也不是从解路径伪造搜索树。支持三种固定八数码实例、MANHATTAN/MISPLACED、播放暂停/单步/进度定位、可点击局部节点与 g/h/f 详情、累计生成/扩展/Frontier 曲线。

局部结构仅保留父节点、当前取出节点及直接子节点。重复盘面用独立候选节点显示并标注 Closed/Frontier 剪枝；不绘制完整状态图。按 f、g、入队顺序选择节点，最多取出 300 次，明确区分找到目标与截断。统计不包含根节点，生成计数包含被剪枝的合法后继；目标出队不计为扩展。计数不可直接当作 Java 性能实验。

优化版增加取出前 Frontier 前六名快照，可点击检查 g/h/f；每帧统计为取出并扩展后的数值，两种时点明确区分。曲线纵轴采用整条已记录轨迹的固定上限，回放过程中不变。播到末帧立即停止，修改速度即时应用。标准目标奇偶性不符时明确拒绝运行。平局时按候选生成顺序的规则仅属于教学跟踪器，Java PriorityQueue 不保证同序。

默认探索实例最优 8 步，曼哈顿演示取出 13 次、错位将牌演示取出 19 次。第 0 帧仅根节点入队（生成 0、扩展 0、Frontier 1），曲线圆点保证初始状态可见。尚未提供 IDA* 阈值动画或 Java 搜索轨迹导入。

2026-10-12 浏览器验收：自动播放到终点并停止、启发函数切换、调速与暂停、第 0 帧和圆点；390px 窄屏无页面横向溢出。Pages 发布只包含当前版本的八个 HTML/CSS/JS 资源；仍须通过 main 分支审核和部署工作流。

## GitHub Pages 发布

仓库管理员在 Settings → Pages → Build and deployment 中，将 Source 选择为 GitHub Actions。
合并本分支至 main 后，`.github/workflows/pages.yml` 会自动发布 `visualization/` 目录。
也可在 Actions 中手动运行 Publish puzzle visualization（选择 main）。
默认发布地址为 https://szhhwh.github.io/ouc-ai-npuzzle/ ，首次部署成功后才可访问。
私有仓库需要仓库所有者的套餐支持 GitHub Pages；不支持时请保留已有的独立演示网址，不要为此修改小组仓库的可见性。

## 本地使用

直接打开 `index.html`，或在项目根目录运行 `python3 -m http.server 8765 --directory visualization` 后访问 http://localhost:8765 。无需安装前端依赖。

默认载入 `examples.js` 中的六条真实 Java A* 结果（三个课程八数码实例 × 两种启发函数），不需要访问后端。点击探索设置中的实例或启发函数按钮，才运行独立的浏览器 A* 演示。页面明确区分两种来源。支持播放、暂停、单步、进度跳转和播放速度；空格键播放/暂停，左右方向键单步。

点击“导入 Java 解路径 JSON”可展示 3×3 或 4×4 路径。输入格式如下，每个 states 元素是一维棋盘，0 表示空格，包括初始状态和后续每一步；导入时会验证状态及相邻移动。

```json
{
  "algorithm": "A*",
  "heuristic": "MANHATTAN",
  "goal": [1,2,3,4,5,6,7,8,0],
  "states": [[1,2,3,4,5,6,7,0,8], [1,2,3,4,5,6,7,8,0]],
  "timeMs": 1.2,
  "generated": 3,
  "expanded": 1
}
```

统计字段可省略。algorithm、heuristic 缺失时显示“未提供”；goal 可省略，此时末帧仅显示“路径播放完成”，不宣称已求解。提供 goal 时会校验棋盘和路径终点一致，支持任意目标排列。导入路径不会被宣称为最优解。

点击按钮后左右方向键仍可单步；焦点处于输入框或下拉框时保留控件原生键盘行为。焦点处于按钮时空格激活该按钮。

## 多实例、实验记录与 PDB

导入按钮现支持多选 `.json` / `.txt`。JSON 可以是上述单条结果、结果数组，或 `{"results": [...]}`。在“多个问题，一处回放”中切换记录；动作按钮按空格移动方向标注 N/E/S/W，可直接跳转。每个文件限 5 MB，页面最多保留 200 条结果，读取不上传。一批文件有错误时整批不加入。

TXT 支持课程程序输出的“初始状态 / 第 n 步”棋盘，以及 `|` 分隔的棋盘，空格写 `#` 或 `0`。需包含初始棋盘和每一步完整棋盘；不能仅提供动作符号。若有课程标准摘要，会读取启发函数、路径长度、CPU 耗时与节点计数，并核对长度。文本未声明算法或目标时保留未知，不推断为 A* 或标准目标，不证明最优性。

实验表仅列出当前选中导入结果的**相同初始状态与声明目标**，不把浏览器演示混入 Java 实验；不会计算跨机器、跨计时口径的加速比。正式报告仍需在相同机器、相同计时口径下预热并重复测量。

PDB 模式视图随当前棋盘变化，分组取自仓库实现：8-Puzzle 的 3–3–2、普通 15-Puzzle 的 6–6–3、RankedPdb 的 7–8。仅示意抽象状态，不查询或构建数据库，不显示伪造的启发值。

Google Fonts 加载失败时使用系统字体，核心功能不依赖外网。OUC 风格颜色参考用户提供的色卡，因参考图未提供数值，不声称为官方精确色值。

## Java 一键导出

在项目根目录执行（需要项目规定的 JDK，首次 Maven 编译可能下载依赖）：

```sh
bash scripts/export-visualization.sh
```

默认读取 `resources/problems-stage1.txt`，使用 Java A*、MANHATTAN/MISPLACED，每组预热一次、测量三次，输出 `visualization/results.json`。输出文件已存在时拒绝覆盖，请使用新文件名。

完整参数为输入、输出、引擎、逗号分隔的启发函数、测量轮数：

```sh
bash scripts/export-visualization.sh resources/problems-stage1.txt /tmp/npuzzle-experiment.json astar MANHATTAN,MISPLACED 5
```

引擎与当前统一入口一致，支持 `astar`、通用 `ida`、专用 `nida`、`dijkstra`；通过 `PuzzleFeeder.getSearcher` 创建真实求解器。启发函数支持 `MANHATTAN`、`MISPLACED`、`DISJOINT_PATTERN`。Dijkstra 忽略启发函数，只运行一次，导出为 `ZERO`。PDB 必须在第六个参数显式传入 `--allow-pdb`，不会默认构建大型数据库。例如：

```sh
bash scripts/export-visualization.sh resources/problems-stage1.txt /tmp/npuzzle-pdb.json nida DISJOINT_PATTERN 5 --allow-pdb
```

输入文件每行必须为 `n 初始排列 目标排列`，支持 n=3/4，逐行校验排列与可解性。十五数码难例可能非常耗时或超出 2GB 堆，默认仅运行第一阶段；不会因为网页支持十五数码就声称难例已完成。专用 `nida` 仅在 PDB 配置下走特化路径，基础启发函数会转交通用 IDA*。

普通命令行实验使用 `./scripts/run.sh problems-stage1.txt astar MISPLACED,MANHATTAN`；第二、三阶段输入分别为 `resources/problems-stage2.txt`、`resources/problems-stage3.txt`。网页 TXT 导入支持新版“搜索引擎”摘要，也兼容旧版摘要；旧版缺少算法名时保留“未提供”，不猜测算法。

输出包含初始状态、每一步状态、声明目标、算法、启发函数、生成/扩展计数、五次或指定次数的原始墙钟耗时、中位数、预热次数、JDK/OS/架构、导出时间与源版本。每轮新建求解器，校验重复测量的步数和节点统计一致，输出前验证路径合法且达到目标。计时覆盖 `search` 调用，不包含编译和文件写入；结果不是跨平台性能保证。

PDB 的首次建库/磁盘缓存加载由不计入样本的预热搜索触发；测量轮复用进程内数据库，因而导出耗时属于预热后的搜索，不是首次启动总耗时。建库耗时、缓存大小与峰值内存需另外记录。不要将其他人提供的、包含建库耗时的记录与该口径混比；只有基础样例已内置，尚无十五数码 PDB 正式性能数据。

输出名以 `.js` 结尾时生成 `window.NPuzzleExamples = ...`，用于静态内置。当前 `examples.js` 由上述脚本以 **5 次测量**生成；源版本带 `dirty` 表示当时包含未提交源码，不冒充已提交版本。重新生成内置样例时先保留旧文件并选择新输出名，核验后再替换。

## 图表与下载

同题实验表下方显示扩展节点条形图。耗时图仅在所有记录明确声明相同 Java 环境、`wall-median` 计时方式及预热/测量次数时共享尺度；这仍不保证硬件、系统负载完全一致，正式性能结论需控制实验条件。不同口径仅展示原始表格，不计算加速比。节点统计亦需核对算法的计数定义。

“下载全部结果 JSON”保存内置和已导入的全部记录（不含独立浏览器演示）；“下载实验记录 CSV”保存相应摘要及测量环境，采用 UTF-8 BOM、CSV 引号转义和常见公式注入防护，可用于 Excel 和报告制图。
