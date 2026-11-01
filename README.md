# A* 算法过程动画

打开 `index.html`，无需前端依赖。紧凑页面只展示棋盘与 g/h/f、搜索树两个区域；隐藏说明小字、Open 候选列表与后继面板，不再显示搜索规模曲线。桌面左右排列，窄屏上下排列。

支持播放/暂停、单步、进度定位、两种启发函数、节点检查和录屏布局。录屏布局只隐藏设置与说明，不生成视频文件，可用系统录屏录制。

## 真实 Java 轨迹

`java-search-traces.js` 来自项目 `BestFirstSearcher` 与真实 `PqFrontier`，没有在浏览器重新求解，也没有修改教师搜索框架。导出前与未加观察器的原始搜索核对完整路径、生成数与扩展数。

比较顺序为 f 升序，再 g 升序。两者都相等时展示 Java 队列的真实选择；展示顺序中的编号不是队列的平局规则。

内置 2 步、8 步、21 步实例 × MANHATTAN/MISPLACED。21 步实例只录前 80 次取出，仍实际搜索至结束用于核对；截断不表示无解。不演示 IDA* 或 PDB。

搜索树展示生成关系；重复状态保留独立节点，可选择显示剪枝。目标真正取出时才显示最终解路径，不提前展示未来节点。分镜间隔是讲解节奏，不是运行耗时。

重新录制（需要项目 JDK/Maven，拒绝覆盖已有输出）：

```sh
bash scripts/export-search-traces.sh /tmp/new-java-search-traces.js
```

核验后用新产物替换 `java-search-traces.js`。源版本带 `dirty` 表示录制时包含未提交 Java 源码。

## 本地与发布

直接打开 `index.html`；或在仓库根目录运行 `python3 -m http.server 8765 --directory visualization`，访问 http://127.0.0.1:8765/。

Pages 仅发布 `index.html`、`search-studio.css`、`search-studio.js`、`java-search-traces.js`，合并 main 后由 GitHub Actions 部署。本地修改不会自动更新公网网站。

旧综合面板保留为 `overview.html`，旧说明在 `OVERVIEW-README.md`；旧资源留在仓库，不随新首页发布。
