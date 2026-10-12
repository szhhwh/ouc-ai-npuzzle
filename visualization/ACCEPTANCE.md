# 可视化交接与验收（2026-10-12）

本分支实现批量文本/JSON 导入、动作定位、同题实验表和图表、PDB 分组示意、结果下载、Java 标准导出及六条真实第一阶段样例。未修改教师 core/algs4 源码。

已通过：Maven verify（编译、Spotless、SpotBugs）、网页回归、六条 Java 样例路径与中位数校验、CSV 转义、导出器边界测试。导出器边界覆盖零步解、通用 IDA*、十五数码单步解、不可解、重复数字、缺失数据、空文件、已有输出拒绝覆盖。

运行测试：先 `./mvnw -B -ntp verify`，再 `node visualization/regression.cjs` 和 `node visualization/exporter-test.cjs`。CI 已接入两项 Node 测试。Maven 本身当前没有 Java 单元测试，不能把 BUILD SUCCESS 解释为全部交互均已验收。

浏览器待人工验收：多文件导入操作、JSON/CSV 实际下载、手机窄屏布局、十五数码连续播放。本次当前页面为 file://，浏览器自动化安全策略拒绝访问，故没有将这些项目标记为完成。可直接人工打开 index.html 检查。

真实样例只涵盖第一阶段 A* 两种启发函数；十五数码难例、IDA*/PDB 正式对比仍需组员提供统一环境实测。PDB 面板仅表示抽象模式，不返回数据库值。计时图不计算跨环境加速比。

GitHub Pages 只有合并并部署成功后才更新；本地提交、推送与上线不是同一状态。分支还包含此前尚未合并的 Pages 读取权限与导入目标校验修复。
