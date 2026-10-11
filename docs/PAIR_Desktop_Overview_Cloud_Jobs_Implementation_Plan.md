# PAIR Desktop Overview / Cloud Providers / Jobs 最小侵入施工计划

> 目标分支：`feature/cloud-provider`  
> 审查基线：`8eb4159f1deefdb6c90a096e8d23a57bfefd5d9b`（2026-10-11 检查）  
> 使用方式：交给 AI Coding Agent 执行；动工前必须重新核对 HEAD，尤其不能覆盖并行 Agent 正在修改的资源感知选模代码。

## 0. 不可变产品边界

1. **Desktop Overview 可配置的推理来源只有四类**：Ollama、LM Studio、llama.cpp、Cloud Providers。后三个本地引擎及 Cloud 的现有能力依实际后端为准，不发明新引擎类型。
2. **MNN 是 Android 执行端**：PC 不提供 MNN 的安装、卸载、下载模型、CPU/OpenCL 或生命周期设置。Android 节点及其模型仍可通过 Cluster/节点展示，**MNN Job 必须在 PC 的现有 Jobs 中可见**。
3. Cloud Provider 属于 API 供应方，不是 PAIR 集群物理节点；不应拥有 Node UUID、GPU 使用率或 Engine Manager 生命周期。
4. **Jobs 是整个已知集群的任务视图**：Ollama / LM Studio / llama.cpp / MNN / Cloud 统一使用既有 Workload 事件和 store。只呈现能证实的执行节点、模型、状态、用量、估算费用；未知时显示未知或不显示。
5. **保持官方核心尽量不动**：不重写 Scheduler、Proxy 预留/重试算法、Cluster 配对、NodeList/Workloads 的通用状态机；优先更改 fork 已新增的 Cloud UI 组件与必要的组合入口。没有失败测试证明缺口，不允许“顺手”修改 Go 后端。
6. 本轮不是重新设计整体导航：保持官方现有 `Content.tsx` 的 Jobs + 节点组合布局，复用现有设置交互与节点卡片，**不新造独立 Routing/Jobs 数据库/Cloud Scheduler**。

## 1. 已核实的现状：先复用再增量施工

| 需求 | 已有位置 | 当前发现 | 操作 |
|---|---|---|---|
| Cloud 配置 | `desktop/src/ui/components/ServiceSettings/CloudProvidersSettings.tsx` | 已有 `getSettings/saveSettings/testConnection/onSettingsChanged`，包含策略、预算、授权 | 复用同一组件/API；调整入口与视图密度 |
| Cloud 当前挂载 | `desktop/src/ui/components/ServiceSettings/ServiceSettings.tsx` | 末尾渲染 `CloudProvidersSettingsCard` | 迁移后删除原挂载，避免双入口重复状态 |
| Overview | `desktop/src/ui/components/MainApp/Content.tsx` | 非 Settings 时同时渲染 Jobs、连接线和 `NodeList` | 尽量仅动局部组合，不打散布局 |
| 本地引擎卡片 | `desktop/src/ui/components/BackendRow/BackendRow.tsx` 与 `NodeList/*` | 与节点/本地引擎生命周期关联 | **不把 Cloud 假冒为 `BackendRow`** |
| Jobs 数据 | `desktop/src/ui/stores/workloads.store.ts` | 已有 snapshot + push 订阅，按 `(originatedFrom,id)` 复合键索引 | 禁止增加第二个 Cloud/MNN store |
| Jobs 列表 | `WorkloadListView.tsx` | 按 active/completed/failed/cancelled 分类，未见显式 cloud 过滤 | 优先补测试，不急于改 |
| Jobs 卡片 | `WorkloadItemCard.tsx` | 已有 `kind === 'cloud'` 与 `providerId` 显示，node label 读取 store | 补 Android MNN 显示验证与缺省行为 |
| Cloud Workload | `services/nvpair-proxy/gateway_workload.go` | 已产生 queued / running / completed / failed / cancelled 等生命周期及 provider/usage 字段 | 保留，实现端到端验证 |
| Desktop Workload Type | `desktop/src/shared/types/workloads.ts` | `kind?:'local'|'cloud'`、`engine?:EngineType`、`providerId`、`usage` | 只在真实 schema 不符时扩展 |

> 注意：上表是静态源码核对，并不等于 PC/Android 联机验收通过。

## 2. P0：动工前的只读审计与变更隔离

**必须先完成**：

- `git fetch` 后记录 `HEAD`、当前 diff 和未提交文件；若并行 Agent 正在修改 `services/nvpair-proxy`、`services/shared/modelselection`、Scheduler，**不碰这些文件**。必要时另开 worktree / UI 专用分支，合并时只取本计划文件。
- 阅读 `docs/developing.mdx`、`CONTRIBUTING.md`、`.cursor/rules/{architecture,renderer-stores,type-discipline,type-safety,absolute-imports,service-contracts,build-and-typecheck,engines-vs-backends-naming,security,proxy-inference-routing}.mdc`，以仓库当前规则为准。
- 找到实际 Overview 的节点/引擎配置展开位置、Cloud Settings 组件全部操作以及 Jobs push 消费链，画出**一张简短代码路径图**并标出缺口；确认之前的 Cloud 修复没有先行完成。
- 给变更文件做分类：`fork 自研` / `官方通用 UI` / `官方 Go`；按顺序优先修改，任何官方核心修改必须列出必要性和回归证明。

**验收**：一页审计结论、拟修改文件清单、哪些功能已有无需改；没有新增生产代码。

## 3. M1：把 Cloud Provider 放到 Overview（优先改自研 UI）

### 3.1 UI 位置

- 在当前 Overview 的本地引擎/节点配置区域，加入**Cloud Providers 独立区域/卡片或折叠入口**，视觉风格沿用 NVIDIA Foundations React Core。
- 本地引擎仍保持 Ollama、LM Studio、llama.cpp 的现有安装/启动/停止/更新交互。Cloud 区域作为**API Provider 管理**，不能接入 engine install/launch/toggle API。
- UI 展示：Provider 标识、启用状态、已配置模型数、连接测试结果（仅表示最近一次测试）、添加/编辑/禁用/删除操作。
- 与现有 `CloudProvidersSettingsCard` 共用配置数据、保存逻辑和已有 `window.pairApi.cloudProviders.*`。最小变更方案是**直接迁移现有组件入口**；只有当前完整表单明显破坏 Overview 密度时，才将其拆为薄摘要/详情视图，但不能重复状态源或复制 API 调用。
- 高级字段：Provider base URL、API Key、公开/上游模型 ID、capabilities、budget、authorizedNodes 和 routing policy 在已有详情/折叠设置区编辑。应保留原有可达性和无障碍标签。
- 正式迁移后去掉 `ServiceSettings.tsx` 中旧 Cloud 组件挂载，避免两个可编辑组件各持有未同步的本地 draft；已有入口若被外部使用，改为导航到唯一新入口，而非保留第二份表单。

### 3.2 数据与安全

- 服务端为配置权威，订阅 `onSettingsChanged` 更新界面；不要创建新的 Zustand Domain Store，除非现有逻辑证明跨组件共享状态确有必要。
- **API Key 不显示明文、不写 localStorage、不放日志、不从服务响应回填成可见密钥**；凭证仍通过现有安全 bridge 处理。
- `Enabled` ≠ `Connected`：未执行连接测试不能显示“在线”；测试失败不自动代表配置被禁用。
- Cloud 的开关不能绕开已有 `cloudEnabled`、预算、Caller Token、远程节点授权策略。禁用后应立即按现有后端语义拒绝后续请求。
- 不给 Cloud 增加虚构的 GPU、Node ID、Downloaded Model 或进程启动按钮。

### 3.3 预期主要文件（以实际组件树为准）

- `desktop/src/ui/components/ServiceSettings/CloudProvidersSettings.tsx`（优先保留复用；必要时局部抽取展示）
- `desktop/src/ui/components/ServiceSettings/ServiceSettings.tsx`（移除重复挂载）
- `desktop/src/ui/components/MainApp/Content.tsx` 或实际 Overview 父组件（局部挂载）
- 可选新增 **一个** `desktop/src/ui/components/.../CloudProvidersOverview.tsx` 薄组件；确有必要才新增。
- `desktop/tests/...` 增加视图/行为测试。

**禁止**：修改 `EngineType` 为包含 cloud、修改 `BackendRow` 的通用生命周期逻辑、把 Cloud 当成 Node 加到 `NodeList`、改写全局导航。

## 4. M2：验证并补齐 Cloud / MNN 进入 Jobs 的事件链

### 4.1 先审查端到端事件流

Cloud:

`Gateway.dispatchCloudForCaller → newGatewayCloudWorkload → Proxy.emitWorkload → Broker WorkloadManager/WorkloadStore → Desktop service bridge → window.pairApi.workloads → workloads.store → WorkloadListView → WorkloadItemCard`

MNN:

`Android MNN HTTP/PAIR 代理推理 → Proxy 标准本地 Workload → Broker 聚合/同步 → Desktop workloads.store → Jobs 卡片`

先在每个边界确认**实际字段映射**：`id`、`originatedFrom`、`scheduledOn`、`engine`、`kind`、`providerId`、`model`、`publicModelId`、`state`、timestamps、`requesterId`、`usage`、`costEstimate`。实际缺项时才修改对应生产代码。

### 4.2 Cloud Job 验收

- Cloud 请求成功：Jobs 出现任务，状态完整结束为 Completed；连续流式中可见 Running。
- Provider 返回 401/429/500、传输错误、SSE 中断、主动取消：最终状态正确，且一个 Workload 最多一个 terminal 事件。
- `kind='cloud'` 且无 `engine` 是合法情况；不要被过滤或强行填 Ollama。
- Cloud 卡片展示 Provider + public model ID + 请求来源（如存在）；不能把 Provider 当执行节点。
- 费用：`costEstimate` 和按上限预留预算不等于实际账单，**未知不显示 `$0`**；Usage 只有 Provider 实际提供时才显示。
- 远程云转发：区分“向外代理的请求”和“实际执行云 API 的主机”；先核查是否真的发生双重 Workload 计数，再决定是否需要展示关联。**本阶段不引入跨节点追踪 ID 或数据库迁移**。

### 4.3 MNN Job 验收

- Android MNN 模型通过现有发现/节点机制出现在 PC 的节点数据中；PC 不提供任何 MNN 配置。
- 由 PC Gateway 调度到 Android 的请求，应在 PC Jobs 显示真实 Engine=`mnn`（以实际后端字段为准）、模型名、Android 执行节点；若执行节点未被报告，显示未知，不能假造。
- Android 发起并执行的任务是否能进入 PC Jobs，取决于现有 Broker 的跨节点同步范围：需要单独验收；如当前跨节点 Workload 不同步，**报告准确缺口**，先检查既有同步协议和配置，确实必要才新增最小字段或事件传递。
- Android MNN 请求失败、模型切换、节点离线时，任务状态不能永久卡在 Running；复用 Broker 现有 stale-workload 处理机制，不做新清理线程。
- 保证 `(originatedFrom,id)` 去重，避免跨节点 ID 冲突。

### 4.4 需要先读、未必需要改的文件

- `services/nvpair-proxy/gateway_workload.go`
- `services/nvpair-ui-broker/workloadmanager.go`
- `services/nvpair-ui-broker/workloadstore/{store,persistence}.go`
- `desktop/src/electron/service-bridge/{modular-state,empty-handlers}.ts`
- `desktop/src/ui/stores/workloads.store.ts`
- `desktop/src/ui/components/Workloads/{WorkloadListView,WorkloadItemCard}.tsx`
- `desktop/src/shared/{types/workloads.ts,utils/workloads.ts}`

**原则**：若现有 Cloud 与 MNN 都已正确显示，只新增测试/更新文档，**不改这些生产文件**。

## 5. M3：自动化测试矩阵

| 类别 | 测试场景 | 必须证明 |
|---|---|---|
| Overview | 3 个本地引擎原有交互 | 安装/启动/停止等不回归 |
| Overview | Cloud 新建、编辑、禁用、删除 | 数据持久化，唯一设置源 |
| Overview | Cloud Test Connection 成功/失败 | 状态语义正确，不显示凭证 |
| Overview | MNN 不进入配置入口 | 没有 PC MNN 管理操作 |
| Jobs | Ollama / LM Studio / llama.cpp | 官方本地 Jobs 正常 |
| Jobs | PC→Android MNN | 有模型、执行节点、终态 |
| Jobs | Android 端自产任务跨节点同步 | 明确通过或明确缺口 |
| Jobs | Cloud 成功/429/错误/取消/SSE 截断 | lifecycle 正确、单次终态 |
| Jobs | Cloud `engine` 为空 | 不被 Jobs 过滤 |
| Jobs | 相同 ID 不同 originating node | 按复合键区分 |
| Jobs | 执行节点暂未发现 | 不 crash，不显示虚构设备 |
| Jobs | 远程云代理请求 | 不误算两次实际推理 |
| 安全 | 无云权限/无预算/禁用 Provider | 禁止请求，界面不泄露密钥 |

M1、M2、M3 每个阶段都要在相关单测通过后再提交，不接受只做 UI 截图的“通过”报告。

## 6. 代码规范（必须遵守）

来自 `docs/developing.mdx` 和 `.cursor/rules/`：

1. **`services/` 是行为权威，Desktop 只透传命令和渲染状态**；不得在 React 中实现调度、发现或鉴权。
2. React 使用既有领域 store；服务快照 + push 更新。表单 draft 可以用局部 `useState`，不能双写后端状态。
3. TypeScript 禁止 `any`、`as any`、`as unknown`、无理由断言；沿用现有类型和完整边界收窄。
4. 跨目录导入使用 `@/...`，同目录用 `./`；禁止动态导入和 Renderer 直引 Electron Main 文件。
5. 服务调用只通过 `window.pairApi`，Electron 原生能力通过 `window.windowApi`；禁止 Renderer 直接调用 Go HTTP。
6. 用户界面称 `engine` 而不是新造 `backend` 命名；尊重已有外部 RPC 字段名称。
7. **不增加 legacy fallback / 兼容垫片 / 无意义 re-export**；删除迁移后无引用的代码，确保 dead-code 检查通过。
8. 所有新文件保留 SPDX 头；遵循现有格式化、测试目录与 Foundations 组件风格。
9. 无新的 RPC 不更新任何 `schedulerwire`/`EngineType`；确需新字段时遵守服务端→Broker→bridge→shared types→store→UI 的完整传播，文档与测试同提交。
10. 不手改 `desktop/docs/services-api.md`，仅在 RPC 表面实际变动时运行 `npm run service-contracts:write`。
11. 不修改无关 Android 推理代码、官方 Proxy node dispatch、Scheduler、资源感知评分器。

## 7. 验收命令和真实设备测试

从 `desktop/` 运行（按项目规则，不能用单独 `npx tsc --noEmit` 代替）：

```bash
npm run typecheck
npm run lint
npm run dead-code:check
npm run test:unit
npm run service-contracts:check
```

仅在 Go 层实际改动时，从受影响 Go module 目录运行 `go test ./...` 和目标 race tests；需要真实新 JSON-RPC 时按规范先 `npm run service-contracts:write` 再 `:check`。Android 侧构建/仪器测试仅在 Android 改动或需证明 MNN 实机行为时执行。

**真实端到端操作**：

1. PC 启动 PAIR，配置 Ollama、LM Studio、llama.cpp 中至少一种正常推理；本地完成一次请求，Jobs 有记录。
2. Overview 新建 Cloud Provider，安全保存 key、执行连接测试，允许 Cloud 后用 `cloud/...` 真实发起一次成功和一次失败请求；检查 Jobs 提交→运行→终态。
3. Android 启动 PAIR/MNN、与 PC 完成配对，模型出现在 PC 节点发现中；PC 用 Gateway 访问 Android MNN 模型；PC Jobs 显示 Android 执行节点与任务。
4. 在 Android 本地独立发一次 MNN 请求，确认是否同步到 PC Jobs；不通过则记录观察到的协议断点，不宣称已实现全集群历史归集。
5. PC-A 经 PC-B 的 Cloud Provider 转发请求，核查执行归属和任务计数；确保没有将一次 Provider 调用算成两次云推理。
6. 重启桌面/服务，确认 Cloud 配置保留、Jobs 历史按既有策略恢复，Credential 不出现在日志或 UI 明文中。

## 8. 提交计划与停工条件

- **Commit A — Cloud Overview**：只完成 Cloud 入口迁移、唯一配置逻辑和 UI 回归测试。优先不碰官方 Go。
- **Commit B — Jobs E2E Regression**：补齐 Cloud/MNN 工作负载验证与展示问题。只有测试揭示缺口才做最小生产代码修复。
- **Commit C — Integration & Documentation**：端到端验收、必要文档、移除重复引用；不夹带无关重构。

每次提交要求输出：`git diff --stat`、官方文件改动名单及必要性、测试命令与真实结果、遗留限制。**遇到并行开发冲突、需调整官方调度算法、或需要引入新的 Job 数据库，立即停止该扩展并报告原因，不擅自扩范围。**

## 9. 验收定义（Definition of Done）

- [ ] PC Overview 中有 Ollama、LM Studio、llama.cpp、Cloud 的配置入口且只维护一个 Cloud 配置源。
- [ ] PC Overview 不出现 MNN 的安装/推理配置；Android 仍作为可发现可路由的节点。
- [ ] Cloud Jobs 从请求到终态完整可见，`engine` 为空不会丢失。
- [ ] Android MNN Job 能在 PC Jobs 中显示**真实已报告**的执行节点、模型和状态；未实现的自主远端事件同步不隐瞒。
- [ ] 本地 Jobs、云权限、预算、NodeList、Proxy 调度行为均无回归。
- [ ] 无额外 Cloud/MNN Store、无第二套 Job 系统、无重复 Cloud 表单。
- [ ] 官方代码修改最小，所有更改符合仓库规范和完整 CI 命令要求。
