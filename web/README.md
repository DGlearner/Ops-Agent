# Ops-Agent 控制台（web）

值班人员看 Ops-Agent 的页面：Vue 3 + TypeScript + Vite，组件用 Element Plus，拓扑图用 ECharts。数据全部来自 core-service 的 REST 接口（`/api/v1/...`），页面不直接连数据库或 Kafka。

| 页面 | 内容 |
|---|---|
| Incident 列表 | 按状态、严重度、服务筛选；每行带 Agent 的结论（最可能的根因和置信度、是否不确定、分析中）；每 5 秒刷新 |
| Incident 详情 | Agent 的分析（摘要、按可能性排序的原因、证据、建议排查、被 core 剔除的引用、历史分析），点证据跳到对应的告警和日志样本；告警与日志样本，Agent 引用过的标出来；影响范围（拓扑上标出出现症状的服务和 Agent 认为的根因）；时间线（告警、状态变更、Agent 分析）；状态流转和手动请求分析 |
| 服务拓扑 | 服务目录的依赖图，标出所选时间内有告警的进行中 Incident 涉及的服务；点服务看它的依赖和相关 Incident |

Agent 的输出是大模型生成的文本，页面只按文本显示（Vue 默认转义，不用 `v-html`）。

## 运行

需要 Node.js 22.12+（或 24+）。先启动 core-service（默认 `http://127.0.0.1:8080`）。

```bash
cd web
npm ci              # 按 package-lock.json 安装
npm run dev         # http://127.0.0.1:5180，/api 转发到 core-service
```

- core-service 不在默认地址时：`OPS_CORE_URL=http://127.0.0.1:18080 npm run dev`。
- 打包：`npm run build`（先用 vue-tsc 检查类型，模板也检查），产物在 `dist/`。`npm run preview` 在 http://127.0.0.1:4180 预览，同样转发 `/api`。
- 测试：`npm test`（Vitest，覆盖证据解析、时间线、拓扑布局、格式化这些不依赖页面的逻辑）。
- 端口只绑定 127.0.0.1。在远程开发机上运行时，本机用 `ssh -L 5180:127.0.0.1:5180 <服务器>` 转发后打开。
- **增加或升级依赖用 npm 11**（`npx npm@11 install ...`）：npm 10 在解析 Vitest 的可选 peer 依赖时会报 `Cannot read properties of null (reading 'edgesOut')`。按锁文件安装的 `npm ci` 不受影响。

## 目录

```
src/
  api/          接口类型（与 core-service 的返回一致，snake_case）和请求封装
  views/        三个页面
  components/   Agent 分析、告警列表、拓扑图、标签
  evidence.ts   把 Agent 引用的 id 解析成告警摘要、日志原文、服务说明
  timeline.ts   Incident 历史和 Agent 分析合成一条时间线
  graph.ts      依赖图的分层布局：调用方在上，被依赖的在下
  format.ts     中文标签、状态流转、时间格式
  __tests__/    单元测试
```
