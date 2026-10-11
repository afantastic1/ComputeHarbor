# PAIR 接入 Strata：最小侵入施工计划

**基线**：`afantastic1/Personal-AI-Router`，`feature/cloud-provider`，提交 `48842ba1f88030f420deb8e18951cf5f9931f52a`（2026-10-11 审查）。

## 0. 定位和实施边界

- Strata 是**本地 PC 推理引擎**，不是云 API，更不是独立的 PAIR 物理节点。Strata 部署在某台 PC 上，PAIR 应把它视作该 PC 上一个可供集群路由的引擎。
- Strata 官方接口目前说明 `http://127.0.0.1:8080/v1` 为 OpenAI 兼容入口；还支持 `/v1/messages` 和 `/v1/responses`。官方说明客户端可传任意模型名，但 PAIR 的模型注册需要稳定、可枚举、唯一的模型标识，因此**不能直接假设其 `/v1/models` 一定满足 PAIR 的发现语义**。
- 第一版：**用户自行安装和启动 Strata，PAIR 仅负责安全连接、发现、路由和 Jobs**。不自动下载安装模型、不代管 Python 环境、不嵌入 Strata 源码、不接管 GPU/显存分配。
- 保留已定云端设计：本地优先、整体繁忙时才调用用户指定的付费兜底云模型；Strata 属于本地候选，不参与云预算或云 Provider 列表。
- 安卓 MNN 依然仅在 Android 端配置，在桌面端 Cluster/Jobs 可见。

## 1. 当前源码核查结论

- `services/nvpair-proxy/engines.go`：路由采用静态 `engineProfile`，共享 `/v1/chat/completions` 等分类；增加新引擎必须考虑 `engines` 身份、Proxy facade、Broker 路由接线。
- `desktop/src/shared/constants/engines.ts`：PC 引擎名单是严格的 `EngineTypes` 封闭联合，当前为 `ollama`, `lm-studio`, `llama-cpp`；因此不能只在前端加 Strata 卡片。
- `services/nvpair-proxy/gateway.go`：`gatewayInventory()` 已按可路由节点、模型、Scheduler 快照收集候选；Strata 正式注册后应该复用它，而非新建专用选模通道。
- `services/nvpair-proxy/gateway_dispatcher.go`：本地与云端分支分开，Strata 应走本地 `resolveLocal/resolveLocalAuto`。
- `desktop/src/ui/stores/workloads.store.ts` 与 Workload UI：使用现有 Jobs 链，不增设 Strata Jobs。
- `docs/developing.mdx` 与 `.cursor/rules`：Go 服务为行为来源，桌面只展示/转发；严格类型；跨目录绝对 `@/` 导入；不新增重复 Store；RPC 修改要契约生成和校验。

## 2. 阶段 A：先做协议探测（**不改 PAIR 产品代码**）

在安装 Strata 的 PC 本地执行以下验证并保存 HTTP status、简化响应结构与时间：

```bash
curl -i http://127.0.0.1:8080/v1/models
curl -i http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"probe","messages":[{"role":"user","content":"hi"}],"max_tokens":8}'
```

再验证 `stream:true` 的 SSE、客户端断开时取消、错误码、工具调用协议（仅当实际模型/运行模式支持）、并发请求与队列行为。检查 `/v1/models` 是否返回标准 `data[].id`；如果没有，则由 PAIR 的 Strata 接入层使用**用户显式配置的唯一 public model ID** 建模，不应反过来修改 Strata 或虚构模型目录。测试 `GET /health`（若存在），否则采用保守的 TCP/HTTP 可达性 + 有限超时探测，不猜测内部健康接口。

**Gate A**：证明普通和 SSE Chat Completions 工作、取消语义明确，形成请求/响应契约清单；不通过则禁止宣称 Strata 已支持 PAIR。

## 3. 阶段 B：Strata 作为外部托管的本地引擎

遵循 PAIR 静态 engine 体系完成**最小的正式注册**：

1. `services/shared/engines`：增加 `strata` 的唯一引擎身份、display name、discovery namespace/服务名和端口常量；复核既有引擎端口分配避免冲突。
2. `services/nvpair-proxy/engines.go`：增加 Strata `engineProfile`；`/v1/models`（如果经探针验证可用）、`/v1/chat/completions`、必要时 SSE 同一路径。只添加经过测试的路由；`/v1/responses` 和 Anthropic `/v1/messages` 暂不承诺通过统一 Gateway（要先核对 Gateway 路径与转换行为）。
3. Broker/engine manager：**只做“检测/连接外部 Strata 实例”**；不实现 PAIR 托管的安装、升级、启动、停止、删除操作。审查现有引擎状态 RPC 是否容许 detected external；若不容许，添加最小、显式的外部引擎连接状态，而不是伪造 `running`/`installed` 或侵入官方安装器。
4. 配置只包含：enabled、loopback endpoint（默认 `http://127.0.0.1:8080`）、稳定模型 ID（若无法自动发现）、可选的密钥引用；不要在 renderer 持有明文密钥。
5. 安全原则：第一版仅支持**本机 Strata → 本机 PAIR 服务**，跨机器调用通过 PAIR 现有可信节点链路而不是直接对公网开放 Strata。端点不得任意访问内网/云元数据，优先固定 loopback 和显式端口。不得复用用于外部云的付费/SSRF 逻辑来假装 Strata 是云端。
6. 模型发现：如 Strata 模型 ID 可枚举，使用真实 ID；否则公开 `local/strata/<configured-id>`，转发前重写为 Strata 接受的上游名字；避免空模型、无能力证据下的自动发现。对能力仅声明实测功能，工具/视觉能力未通过测试不得自动放行。
7. 统一 Jobs：标记 `engine=strata`，记录模型、实际 PAIR 计算节点、状态与耗时；Cloud 独有 provider ID/cost 不应误加到 Strata。

**Gate B**：单 PC Strata 通过 PAIR `GET /v1/models` 和 `POST /v1/chat/completions` 完成非流式、SSE、异常/取消测试；Jobs 正确完成且可追踪。

## 4. 阶段 C：多节点发现、调度和自动选模

- 在 PC-A 配 Strata，PC-B 配 Ollama/LM Studio；PC-C 运行客户端。只通过 PAIR Gateway 访问，确认真实模型 ID 与执行节点对应。
- Strata 自动进入**本地模型库存**，并复用现有 Scheduler 对模型部署的节点选择；不得在 Strata adapter 中自己实现节点调度。
- 重点防止 Strata 模型名含数字/宣传参数造成错误的显存需求估算。`modelParameterCount()` 从名字推断不是实际显存占用，Strata 大模型可能依赖 CPU RAM/分层卸载，**不能把总参数量 × 2 当成硬件准入硬条件**。必要时仅对 Strata 记录未知内存需求、明确加载状态和可达性；资源不足不得凭推断直接自动付费上云。
- Strata 对并发/请求排队的行为未确认时，不能凭其 GPU 压力等级就判定设备不可用。对 Strata 可用的指标（设备级压力、排队情况、观测到的并发）先复用现有 Scheduler；任何无数据状态保持 unknown。
- 全局 `prefer_local` 下，Strata 与其他兼容引擎共同构成本地候选集；只有**全部可兼容本地部署**被确认繁忙或不可用且允许付费回退，才路由到用户指定的云模型。
- 显式 `local/strata/...` 和 `auto-local` 均不可被云端替代；`auto-cloud` 直接去用户指定云模型，按既定云预算/鉴权检查。

**Gate C**：2 PC + 1 客户端跨节点请求、节点离线、Strata/其他引擎同时空闲和分别繁忙的测试；验证选模和 Cloud fallback 不受误报影响。

## 5. 阶段 D：桌面 UI（仅可控制本机 Strata）

- 在 PC Overview 与 Ollama、LM Studio、llama.cpp 并列展示 `Strata (External)`；按钮只提供连接/断开 PAIR、查看状态、打开官方文档，不提供安装、下载、卸载、停止外部进程。
- 单个 Strata 配置 UI：endpoint、测试连接、模型 ID（必要时）、启用状态。错误提示区分“未运行”“鉴权失败”“模型不可识别”“协议不兼容”。
- 复用 `EngineTypes`、现有 engine store 和 `NodeEngineSettings`，不产生新的 Strata Store；若外部托管生命周期与 EngineStatusData 不同，只做最小正确的合同扩展。
- Android MNN 不出现 PC Overview 的安装/配置入口，依然在 Cluster/Jobs 显示。

## 6. 必改/待证实的文件边界

**确定需检查（不等于全部必须修改）**：

- `services/shared/engines/*`
- `services/nvpair-proxy/engines.go`, `gateway.go`, 相关测试
- `services/nvpair-ui-broker/engineproxy.go`, `enginesettings.go` 及 RPC 接线
- `services/nvpair-engine-manager/registry.go`（先核对是否支持仅 detected）
- `desktop/src/shared/constants/engines.ts`, `src/shared/types/engines.ts`
- `desktop/src/ui/components/NodeList/NodeEngineSettings.tsx` 与已有 engine 展示组件
- `desktop/src/ui/components/Workloads/WorkloadItemCard.tsx`、`EngineIcon`（仅在有真实缺口时）
- `services/nvpair-proxy/spec.md`、对应 README、必要的 JSON-RPC 契约文档

**禁止**：修改 Strata 上游源码；嵌入 Strata 模型运行时；另建 HTTP 网关、Scheduler、Jobs DB；把 Strata 塞入 Cloud Provider；在桌面 renderer 里探测硬件或直接调用 Strata HTTP。

## 7. 测试和质量门禁

```bash
# 桌面工程根目录 desktop/
npm run typecheck
npm run lint
npm run dead-code:check
npm run test:unit
npm run service-contracts:check

# 如果 Go RPC surface 改变：
npm run service-contracts:write
npm run service-contracts:check
```

Go 模块分别执行 `go test ./...`（在各模块目录）；运行相关跨服务集成测试。实际端到端用 Windows/Linux 各一台（至少 Windows 一台）检查启动时 Strata 不在、启动后出现、模型变更、流式中断、网络隔离和节点离线。不修改 `services/versions.json` 或生成文档的规则之外文件。遵循 `docs/developing.mdx` 的类型安全、静态 imports、`@/`、service-authoritative、已存在 store、代码许可头、测试与最小 diff 要求。

## 8. 分提交顺序

- **S0 协议探针与契约记录**（只加测试/文档，无产品新功能）
- **S1 本地 Strata 外部连接与 Proxy 路由**（最小 Go 更改和 Go 单测）
- **S2 Broker 节点传播 + Jobs**（确保 Strata 在集群模型库存，不重复统计）
- **S3 Desktop Overview 接入**（只展示并管理外部连接）
- **S4 全链路与 Cloud fallback 回归**（跨节点测试 + docs）

每个阶段在通过对应测试后再提交。若 S0 证实 Strata 模型发现或请求流协议与 PAIR 不兼容，暂停 S1 并先提供最小协议差异报告，禁止通过硬编码“成功”掩盖差异。

## 资料

- Strata: https://github.com/Niko1221/Strata
- Strata AI setup: https://github.com/Niko1221/Strata/blob/main/docs/AI_SETUP.md
- PAIR developer guide: https://github.com/afantastic1/Personal-AI-Router/blob/feature/cloud-provider/docs/developing.mdx
