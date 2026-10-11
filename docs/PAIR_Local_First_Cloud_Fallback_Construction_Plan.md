# PAIR 本地优先、云端救火路由施工计划

> 施工对象：`afantastic1/Personal-AI-Router` / `feature/cloud-provider`  
> 代码审查基线：`48842ba1f88030f420deb8e18951cf5f9931f52a`（2026-10-11 查验）。  
> 目标：**尽量不改 NVIDIA 官方调度，完善本地异构模型路由；云端只使用用户明确指定的付费兜底模型。**  
> 执行前必须重新 `git fetch`、确认当前 HEAD 与本计划差异；如已新增功能，以源码和测试为准，禁止重复开发。

## 0. 已核对的项目状态（事实，不是推测）

1. `48842ba` 相对于 `8eb4159` 前进 **1 个提交**。该提交已将 Cloud UI 接入 `desktop/src/ui/components/NodeList/NodeEngineSettings.tsx`（仅本机 Electron 节点）；Service Settings 不再承担原入口。新增 `cloud-providers-overview.test.ts` 等 UI 测试，Workload 卡片及服务桥接也已修改。**Overview / Jobs 先验证，不重做。**
2. `services/nvpair-proxy/gateway.go` 已实现 `gatewayInventory()`：复用 `resolveCandidateViews()` 判断可路由节点，从 Scheduler snapshot 读取 `GPUPressureKnown`、`PendingKnown`、`PendingRequests` 和 `InFlightReservations`，不应再建遥测管线。
3. `gateway_dispatcher.go` 仍定义 `local_only / cloud_only / prefer_local / prefer_cloud` 四策略；`resolveAuto()` 仍对 `prefer_cloud` 特殊处理；`resolveFirstCloud()` 从 Cloud Registry 中寻找**第一个兼容模型**，尚无显式兜底模型选择。
4. `gateway.go` 的 `autoAliases` 仍为 `auto / auto-fast / auto-balanced / auto-best`，未见 `auto-local / auto-cloud`。
5. `desktop/src/shared/types/cloud-providers.ts`、`services/nvpair-ui-broker/cloudproviders.go`、`services/nvpair-proxy/gateway_control.go` 目前均无 `fallbackCloudModel` 配置字段；设置通过 Broker 保存，再同步给 Proxy。
6. `gateway_workload.go` 已有 Cloud Workload 生命周期；`WorkloadItemCard.tsx` 已有 Cloud 渲染，最新提交增强 MNN/Cloud 标记。必须进行端到端实测，不能仅凭存在 UI 或测试就声称完整可用。
7. `gateway_policy.go` 的云预算账本按单请求费用上限预留，并采用保守结算；不是精确按 Token 扣费。**不得把预留费用冒充实际费用。**

关键参考：`docs/developing.mdx`、`.cursor/rules/{renderer-stores,service-contracts,engines-vs-backends-naming,build-and-typecheck,no-legacy-fallbacks,type-discipline}.mdc`。服务端真相在 Go，Desktop 只是服务状态投影，不得把调度写进 TS。

## 1. 最终产品规则（必须先固定，防止实现歧义）

### 1.1 三种全局策略

- `local_only`：所有自动别名只在本地选模；显式 `cloud/...` 和 `auto-cloud` 必须拒绝。即使 Cloud Provider 开启也不产生云费用。
- `prefer_local`：自动别名先评估**全集群兼容本地部署**；若全部确定不可用，或全部满足明确的繁忙条件，在 `allowPaidFallback=true` 且完成云授权、预算、模型能力检查后，使用用户配置的唯一 `fallbackCloudModel`。未允许付费或云模型不可用时：如果本地仍可执行就继续本地；否则返回明确错误。
- `cloud_only`：自动别名直接使用用户配置的 `fallbackCloudModel`；显式本地模型和 `auto-local` 被拒绝。显式 `cloud/...` 仍可按 ID 调用且受相同安全/预算控制。
- **删除 `prefer_cloud` 作为可选策略**；旧存储值采用一次性、可测试的迁移规则（见第 4 阶段），不得悄悄变成更宽松的付费授权。

### 1.2 请求模型别名

| 请求 `model` | 解析要求 |
|---|---|
| `auto`, `auto-balanced` | 本地 balanced；仅在 `prefer_local` 且符合兜底条件时升级指定云模型；`cloud_only` 则直接使用指定云模型 |
| `auto-fast`, `auto-best` | 保留本地 fast/best 评分；必要时同样使用指定云模型，**云端不重新评分** |
| `auto-local` | 请求级强制本地 balanced；`cloud_only` 下拒绝 |
| `auto-cloud` | 请求级强制用户指定的 `fallbackCloudModel`；`local_only` 下拒绝；显式云意图不依赖 `allowPaidFallback`，但必须遵守 `cloudEnabled`、鉴权、额度和预算 |
| `local/<engine>/<model>` | 按现有显式本地规则；`cloud_only` 下拒绝；不自动转云 |
| `cloud/<provider>/<model>` | 按现有显式云规则；`local_only` 下拒绝；不做其他云模型替代 |

说明：`auto-local`、`auto-cloud` 默认 balanced，避免 `auto-cloud-fast` 等组合别名膨胀；`/v1/models` 要列出新别名，并维持对其余已公开别名的兼容。明确云意图仍需经过原有 PAIR Token、配对节点权限、安全校验，禁止绕过管理员的 `local_only`。

### 1.3 云端救火：固定一个模型

新增配置字段：`fallbackCloudModel: string`，值为 Cloud Registry 内现有公开 ID，例如 `cloud/deepseek-primary/deepseek-chat`。

- **仅一个选中值**。多个 Provider 可以配置，但自动路由只使用这一项；不做云端排序、价格评分、轮询、随机选择或其他 Provider 静默故障转移。
- 保存时校验非空值是否为规范 `cloud/...` ID；使用时必须再次根据实时 Cloud Registry 校验 Provider/模型启用、能力支持、有效授权/凭证。允许空值，但要求 `cloud_only` 的自动请求、`auto-cloud`、繁忙自动兜底在空值时明确报错。
- 配置中模型被删除或禁用时**保留失效选择以提示用户修复**或要求保存时显式重新选择；不能静默选择列表首项。列表排序仅用于 UI 展示。
- 不把云 Provider 建成 PAIR 物理节点，不增加云 Scheduler，也不改 Model Selector 的本地评分。

## 2. 繁忙判定：与官方 Scheduler 共用资源快照

### 2.1 候选集合

复用 `Proxy.gatewayInventory()` 提供的 `RuntimeModel`（已过滤可路由节点），按请求能力过滤得到兼容模型-节点部署集合；**不是某一台 GPU 繁忙就上云**。保持本地 `AutoModelSelector` 的 fast/balanced/best 决策不变。

### 2.2 三态资源判定

针对每个兼容部署判定：
- `routable`：有明确可路由节点 + 模型匹配，且没有被可靠数据证明达到繁忙阈值；
- `busy`：遥测新鲜且对应指标有已知值，达到可解释的繁忙门槛；
- `unknown`：GPU / pending 遥测不存在或过期，**不得视为 busy**。

全局条件：
- 无兼容、可路由部署 -> `no_local_capacity`（可以尝试付费回退）。
- 存在至少一个不繁忙或未知的兼容部署 -> **优先本地**。
- 所有兼容、可路由部署均被**有效遥测**判为繁忙 -> `local_saturated`（可尝试付费回退）。

建议第一版保守且可测的条件：`GPUPressureKnown && GPUPressure >= schedulerwire.MaxGPUPressure` 可以标记一个部署繁忙；Pending / in-flight 必须结合已知状态及设备容量，不要因裸 `Pending>=3` 就自动扣费。首版可以**仅启用最高 GPU 压力阈值**做自动付费兜底，Pending 仅记录用于审计；如果经现有 Scheduler 证明 pending 容量阈值在各节点可比，再作为二次条件。门槛使用单一 Go 常量或现有规范中的等级常量，不为 MVP 引入一套任意阈值设置 UI。

重要：若所有节点 CPU 推理或 Android MNN 没有可靠 GPU 压力数据，则仅根据已知可路由性优先本地，不能虚构其负载状态。资源未知情况下禁止自动上云（除非确定无可执行部署）。

### 2.3 并发与时效边界

- 快照只是观察值；必须在最终本地调度阶段继续由官方 Proxy 验证并预留；**不能在 Gateway 再实现锁、并发队列或第二套 reservation**。
- 高频并发下的瞬时误判避免用新的云费用触发器修补。先测试压力快照时效、Scheduler 暂停和在途预留是否被准确计入。
- 如果曾经高压而新快照丢失，回到 unknown，不得保持永久“满载”。
- 如果本地选定后发生网络/模型加载失败，不增加请求级本地→云重试。尤其流式响应开始后不可切换模型重放请求。
- 记录内部决策原因 `local_available / local_saturated / no_local_capacity / telemetry_unknown / cloud_not_allowed / budget_exceeded`，可复用现有结构化日志；先不增加新的持久化日志子系统。

## 3. 施工顺序及最小文件范围

### Commit A — 明确配置契约与迁移（先做，再改算法）

阅读并按实际 call chain 修改：
- `services/nvpair-ui-broker/cloudproviders.go`：扩展 persisted settings、RPC 输入输出、合法性检查；在旧数据上兼容读 `fallbackCloudModel` 缺省；维护 JSON 严格解析、安全落盘、配对节点授权。
- `services/nvpair-proxy/gateway_control.go`：扩展 `gatewayConfigureParams` 并校验有效策略、新兜底 ID；确认 Broker → Proxy 的字段被完整转发。
- `services/nvpair-proxy/gateway_dispatcher.go`：settings 原子快照加入 `fallbackCloudModel`；删除 `prefer_cloud` 的正常运行路径。
- `desktop/src/shared/types/cloud-providers.ts`：扩展统一 TS 契约；避免 `as any`。
- `desktop/src/electron/service-bridge/cloud-providers.ts` 及受影响的桥接文件：**只在现有链路未自动传递字段时**补齐，不新增独立状态存储。

迁移策略：对磁盘已有 `prefer_cloud`，不宜静默映射成 `cloud_only`（会改变云端失败后的本地行为），也不能静默映射 `prefer_local`（改变优先级）。推荐明确的版本化一次性迁移：保留原值为待用户确认的 UI 提示，自动路由在用户确认前保守地拒绝新付费兜底或采用 `local_only`；用户显式选择三策略之一并保存后才进入新行为。不得留下长期保留的第四种正式策略实现。根据现有设置 SchemaVersion/原子保存方式选择最小实现，不增加第二个配置文件。

验收：重启后配置一致；旧值不崩溃、不静默改变付费行为；空兜底模型时云自动请求报错；旧配置无新字段时允许按安全默认加载；权限/预算未回归。

### Commit B — 新别名和确定性云模型解析

- `services/nvpair-proxy/gateway.go`：添加 `auto-local / auto-cloud` 公共别名并列入模型目录，遵守现有模型目录与鉴权规则。
- `services/nvpair-proxy/gateway_dispatcher.go`：入口使用明确的来源解析（本地强制、云强制、策略自动），保留 `resolveLocalAuto` 与本地选择器；新增 `resolveConfiguredCloud` 直接按 `fallbackCloudModel` 查 `Snapshot.Resolve`，保留能力校验、凭证/授权、预算执行链路。
- 删除**自动路由中的** `resolveFirstCloud()`；若测试或其他非自动路径依赖它，核对后仅移除确实无用的实现，不做冗余兼容。
- 显式 `cloud/...` 的远程配对节点路径维持原功能；`auto-cloud` 是否使用远程 Provider 必须明确：**首版只使用本机 Cloud Registry 中配置的兜底模型**。若选中 ID 仅在远端可用，明确报错；不要在无明确授权归属的情况下隐式调用远端。显式远程云模型仍按已有路径工作。

验收：策略 × 别名决策表；`auto-cloud` 无默认模型报错；云明确调用不能绕过 `local_only`、认证或预算；`cloud_only` 下 `auto-local` 失败；`auto-best` 发生云兜底时也只使用唯一指定模型。

### Commit C — 全集群资源繁忙兜底

- 在 `gateway_dispatcher.go` / `gateway.go` **二选一最合适的 Go 实现位置**增加一个小型纯函数 `classifyLocalCapacity(...)`，输入已有 candidate snapshot 与要求，不新增 service/store/monitor。
- 对于 `prefer_local + auto*`：先构建兼容本地集合，判断整个集合三态；有可用/未知候选即交给现有本地选模；全 busy 或零候选且 `allowPaidFallback=true` 才尝试配置的云模型。
- 如果云兜底的能力、凭证或预算校验失败：本地虽 busy 但可路由时退回本地；本地完全没有兼容候选则返回明确错误。注意对 401/403、预算错误不隐藏、不重试到其他云模型。
- 保持流式响应和官方 Proxy 的调度/预留路径。避免两次读取不同时间点快照导致“判断全 busy，但评分选了不同候选”；在一次 resolve 中使用同份快照。
- 成本边界：云端预算在现有请求执行流程 reserve；不得仅因预选候选而扣费；禁止在云端拒绝后尝试第二个付费 Provider。

验收：一台忙、另一台空闲 -> 本地；全部明确高压 -> 指定云；一个 unknown -> 本地；全部无可路由候选 -> 在付费允许时云；关闭付费 -> 无云；月/单次预算超限 -> 不产生违规云请求；并发 10/20 时不重复计费。

### Commit D — Overview 配置与 Jobs 验证（不重做已完成 UI）

- `desktop/src/ui/components/ServiceSettings/CloudProvidersSettings.tsx`：在已迁入 Overview 的 Cloud 卡片中增加**单一兜底模型下拉**，选项来自当前配置的已启用 Cloud Providers/模型；支持未设置/已失效标记；仅三种策略；显示醒目的“繁忙时可能付费”说明；保持现有 UI 组件和事件订阅。
- `desktop/src/ui/components/NodeList/NodeEngineSettings.tsx`：只在原有入口需要变化时调整；PC 端 **仅** 配置 Ollama、LM Studio、llama.cpp、Cloud；绝不出现 MNN 安装/后端设置。
- `desktop/src/ui/components/Workloads/WorkloadItemCard.tsx` 与 `workloads.store.ts`：先执行现有 Cloud/MNN 测试与真实集群联调。仅在链路发现缺口时补最小修复；不新增 Jobs 表或 Cloud Job Store。
- 检查 Cloud Job 的 provider、requester、状态和费用语义；检查 Android MNN Job 是否由 PC 正确展示执行节点。仅记录真实可观测字段。

验收：选择兜底模型→保存→重启→原值恢复；删除被选中 Provider 时提示失效，不自动换模型；Cloud 救火产生一条可见 Job；Android MNN 推理出现在 PC Jobs；PC Overview 无 MNN 配置；原有引擎安装/停止流程不变。

### Commit E — 回归、安全、文档

- 单测覆盖策略 × 别名 × 能力 × 资源已知性 × 付费许可 × 预算 × 云 Provider 禁用。覆盖旧设置迁移、空 fallback ID 和恶意伪造 ID。
- E2E 覆盖 Ollama 双节点（同模型 + 异模型）、Android MNN 和 Cloud Provider stub；SSE 第一段/完成、429/500、配对节点云授权、节点离线、任务状态。
- README/spec 与 `docs/services-api.md` 按项目脚本生成；不要手改生成物。业务变化若需要 headless TUI，按 developing 指南判定是否需同步。
- 去掉新增后无用的分支/导出；不删除官方既有能力的测试，不做 unrelated formatting/refactor。

## 4. 安全和行为边界（不可跳过）

1. **全局禁云硬约束**：`local_only` + `auto-cloud` 和显式 `cloud/...` 都拒绝。
2. **预算硬约束**：`monthlyBudgetUSD`、`perRequestMaxEstimatedCostUSD`、余额不足和保存故障的处理沿用现有预算 ledger；预算为零/不可用时不允许隐式产生费用。
3. **鉴权一致**：新别名 `auto-cloud` 及在 `prefer_local` 中可能触发付费的 `auto*`，应按**可能调用云端**处理鉴权，不能先走本地绕过 Token，再在负载变动时使用付费 Provider。`/v1/models` 保持已有云开启时的鉴权策略。
4. **Provider 不可用**：只使用明确配置的 ID，不切换其他 Provider；错误消息不得泄露上游凭证或敏感正文。
5. **故障不自动重放**：本地已发出请求后不自动升级云端；SSE 已开始输出后更不能切换目标。
6. **资源未知保守本地**：不假设 Cloud 无限配额或零延迟，云的 429、5xx 与网络失败应被正确呈现和计入 Jobs。
7. **跨节点最小权限**：自研云配置权限仍绑定授权节点与证书；新兜底模型不能扩大远端密钥访问权限。
8. **费用可观测性**：Jobs 的 `CostEstimate` 不是精确账单，不允许显示为实际消费。

## 5. 必须遵守的仓库规范

- `docs/developing.mdx`：Go 服务掌握行为；Desktop 为只读状态投影和命令入口；改 JSON-RPC 同步 producer / broker / preload / type / store / tests。
- `.cursor/rules/renderer-stores.mdc`：使用既有 domain store；事件初始快照+push；不要把服务数据复制到组件的持久缓存；本地 UI 草稿状态可以保留。
- `.cursor/rules/engines-vs-backends-naming.mdc`：新 UI/TS 命名使用 `engine`，已有 wire 字段不可擅改；Cloud Provider 不是 EngineType 成员。
- `.cursor/rules/absolute-imports.mdc`：跨目录 `@/...`，同目录 `./`；禁止动态 import。
- `.cursor/rules/type-discipline.mdc`、`docs/developing.mdx`：禁止 `any`、类型断言和用 `unknown` 逃避契约。
- `.cursor/rules/no-legacy-fallbacks.mdc`：不长期维护双路兼容分支；旧配置迁移应一次性、受测。
- `.cursor/rules/service-contracts.mdc`：变更 JSON-RPC 后执行文档生成/契约检查；不得手改生成文档；不修改 services/versions.json 除非遵循仓库规定的 release 机制。

## 6. 验收矩阵（至少全部通过）

| 场景 | 期望 |
|---|---|
| `local_only` + `auto-cloud` | 明确拒绝，零云费用 |
| `cloud_only` + `auto-local` | 明确拒绝 |
| `cloud_only` + `auto` | 唯一指定云模型 |
| `prefer_local` + `auto`, 本地有空闲 | 本地 |
| `prefer_local` + `auto`, A 忙 B 空闲 | B 或其他本地可执行部署，不上云 |
| `prefer_local` + `auto`, 所有兼容部署均已知高压、已授权付费 | 配置兜底模型 |
| `prefer_local` + `auto`, 一个遥测未知 | 本地，不因未知产生费用 |
| `prefer_local` + `auto`, 无兼容本地部署、已授权付费 | 配置兜底模型 |
| `prefer_local` + `auto`, 禁止付费回退 | 本地（若有可执行候选）或明确失败 |
| 任意请求，未选择 fallback model | 自动上云返回清晰错误，绝不静默选第一项 |
| `auto-best` 上云 | 仍只选指定云模型，不运行云模型排名 |
| 云模型已禁用/缺失 | 不自动选替代模型，提示修正配置 |
| Token/云授权无效 | 云请求被拒绝 |
| 月预算或单请求预算触顶 | 云端不发出违规调用 |
| SSE 已经开始 | 不跨模型重试/不重复输出 |
| 双 PC 本地运行 | 官方节点调度继续生效 |
| Android MNN 执行 | PC Jobs 展示真实节点，不在 PC Overview 提供 MNN 配置 |
| Cloud Job | Jobs 状态正确，不重复记账 |
| 旧 `prefer_cloud` 设置 | 安全、明确、受测的迁移行为 |

## 7. 执行命令与交付约束

```bash
# 执行前，从项目根目录
 git fetch origin
 git status --short
 git rev-parse HEAD
 git diff --stat 8eb4159..HEAD

# Desktop（仓库已有脚本）
cd desktop
npm run typecheck
npm run lint
npm run dead-code:check
npm run test:unit
npm run service-contracts:check
# 仅在修改 Go JSON-RPC 契约时
npm run service-contracts:write
npm run service-contracts:check

# Go：在有改动的 Go module 下执行
cd ../services/nvpair-proxy && go test ./...
cd ../nvpair-ui-broker && go test ./...
```

AI Coding Agent 必须每个 commit 输出：改动文件、为什么必须改（官方/自研）、测试命令及结果、没有运行的测试及原因、遗留风险。任何测试失败不得宣称已验收。严禁凭空补全未来接口，先检查项目已存在契约。若 HEAD 已推进，先 rebase/re-evaluate，再做最小增量修改。

**最终交付定义**：本地模型继续由资源感知策略选模且由官方 Scheduler 选节点；云端只有用户明确指定的救火模型；本地集群确实整体繁忙或无法执行且授权付费时才上云；Overview 管理 PC 三个引擎及 Cloud，Jobs 观察 PC/Android/Cloud 执行；不新增云端选模器、监控系统、调度器或任务数据库。
