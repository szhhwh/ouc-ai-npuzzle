# N-Puzzle 解路径可视化

## GitHub Pages 发布

仓库管理员在 Settings → Pages → Build and deployment 中，将 Source 选择为 GitHub Actions。
合并本分支至 main 后，`.github/workflows/pages.yml` 会自动发布 `visualization/` 目录。
也可在 Actions 中手动运行 Publish puzzle visualization（选择 main）。
默认发布地址为 https://szhhwh.github.io/ouc-ai-npuzzle/ ，首次部署成功后才可访问。
私有仓库需要仓库所有者的套餐支持 GitHub Pages；不支持时请保留已有的独立演示网址，不要为此修改小组仓库的可见性。

## 本地使用

直接打开 `index.html`，或在项目根目录运行 `python3 -m http.server 8765 --directory visualization` 后访问 http://localhost:8765 。无需安装前端依赖。

内置三个课程 8-Puzzle 实例，使用浏览器中的 A* 计算真实解路径。页面明确区分浏览器耗时与导入的 Java 数据。支持播放、暂停、单步、进度跳转和播放速度；空格键播放/暂停，左右方向键单步。

点击“导入 Java 解路径 JSON”可展示 3×3 或 4×4 路径。输入格式如下，每个 states 元素是一维棋盘，0 表示空格，包括初始状态和后续每一步；导入时会验证状态及相邻移动。

```json
{
  "states": [[1,2,3,4,5,6,7,0,8], [1,2,3,4,5,6,7,8,0]],
  "timeMs": 1.2,
  "generated": 3,
  "expanded": 1
}
```

统计字段可省略。当前版本提供导入协议，Java 自动导出适配可随后接入。Google Fonts 加载失败时使用系统字体，核心功能不依赖外网。
