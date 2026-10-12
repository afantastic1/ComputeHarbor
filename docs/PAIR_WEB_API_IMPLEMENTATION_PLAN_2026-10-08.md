# PAIR Web API 接入与统一资源路由实施方案（基于源码审查）

> **状态**：待实施的施工规范，不是已完成清单  
> **日期**：2026-10-08  
> **审查分支**：`feature/android-build`  
> **审查基线**：`a6cc1a173f283f18b5bc51654d9d57966337f337`（稳定性修复）  
> **目标分支**：建议 `feature/cloud-provider`，从上述基线创建  
> **产品目标**：一套 OpenAI 兼容入口，调度 PC/Android 本地模型与用户授权的云端 API；未来承载多 Agent、图像生成和视频生成任务。  
> **执行主体**：编码 Agent 按阶段施工，开发者逐阶段验收。  
> **重要约束**：本次基于仓库内关键链路源文件、规范和现有测试报告进行审查；**没有重新执行 Windows/Android 真机测试，也不声称 1,508 个树条目逐一人工审核过**。文中的“代码现状”为可定位事实，“风险”为待测试或待实现项。

---

## 0. 最终决策摘要

1. **可以现在开始接入云 Web API**。本地设备推理、双向路由、单一 Gateway、自动模型别名和模型选择模块已经存在，不需要再重写这些基础能力。
2. **不新造一套网关**：以 `services/nvpair-proxy/gateway.go` 为唯一入口；由现有 `nvpair-ui-broker` 管理配置、进程与 UI 契约。第一版不要为了支持云 API 就把它伪装成 Ollama/LM Studio/MNN 引擎，也不要立即新建独立微服务。
3. **区分模型选择与执行位置选择**：Gateway 先把“逻辑模型”解析为本地或云端执行目标；本地继续走既有 Facade 和 Scheduler；云端走独立的 HTTP Provider Adapter。不要让云端走本地节点发现、端口迁移、模型加载、显存预估和本地节点重试代码。
4. **分步交付**：W0 先固定边界与契约；W1 建立数据模型/配置；W2 开发云 Provider HTTP Adapter；W3 接入 Gateway；W4 完善能力/策略/审计；W5 PC UI；W6 Android UI 和跨节点云能力授权；W7 多 Agent 验证。W6 的跨节点云执行是**明确独立阶段**，不是 W3 自动获得的特性。
5. **第一期只做文字与 Agent Tool Calling**：`GET /v1/models`，`POST /v1/chat/completions`，流式 SSE、非流式、取消、可观察的错误、工具调用和结构化输出能力识别；后续图片/视频是单独的任务 API 与调度层，不能作为 chat completion 的一个 model 字符串强塞进去。
6. **安全为硬门槛**：云端密钥不得写入日志、模型列表、集群发现、工作负载事件、前端状态或 JSON-RPC 通知；默认禁用付费云路由；禁止不受约束的目标 URL 和自动重试产生重复计费；用户显式授权哪些客户端/节点可以使用云预算。

### 一期“完成”的严格定义

从一个普通 OpenAI SDK/Harness，以一个固定 Base URL 和**PAIR 本地入口凭据**调用 `auto` 或一个固定逻辑模型 ID，在无需改动上层 Agent 的情况下，可以切换 PC LM Studio、Android MNN 或用户已授权的云文本模型。切换不要求改变 Base URL，且原有本地节点推理与流式取消回归全部通过。云端失败必须返回明确原因，不可静默改用付费资源。

---

## 1. 从现有代码确认的事实、约束与缺口

### 1.1 当前调用链（已实现）

```text
OpenAI SDK / curl / 上层 Agent
           |
           v
127.0.0.1:14326  [nvpair-proxy/gateway.go]
    GET  /v1/models
    POST /v1/chat/completions
           |
           v
  显式 model / auto* 解析
           |
           v
  选择 Engine Facade [mnn|ollama|lmstudio]
           |
           v
  facade.handleHTTP [proxy.go]
    发现模型 owner / reserveCandidate / 转发 / 重试 / SSE
           |
           v
  本地 HTTP 引擎 或 集群 mTLS 终端
           |
           v
  MNN on Android / LM Studio / Ollama

控制面：Desktop / Android -> nvpair-ui-broker -> nvpair-proxy
``` 

对应源码：

- `services/nvpair-proxy/gateway.go`：Gateway 监听 `127.0.0.1:14326`，只识别两条 OpenAI 路径；`gatewayModels()` 合并 Facade 发现的模型 ID；`gatewayInventory()` 构造自动选择候选；`resolveGatewayFacade()` 从本地 Engine Facade 中挑选一个；`serveGateway()` 最终调用 `f.handleHTTP()`。
- `services/nvpair-proxy/engines.go`：Engine Profile、路径分类、Ollama 与 LM Studio 的代理入口、MNN chat-only Facade。现有路由**不以 Routes 作为总路径白名单**；未识别路径在 Engine Facade 层有透传语义。
- `services/nvpair-proxy/proxy.go`：64 MiB 请求体上限、模型 owner 过滤、节点预留、前置重试、SSE 反向代理、取消和 workload 事件。`maxDispatchAttempts=5`、推理 `jobDeadline=10min`、首响应预算为特定本地引擎设定，**不能直接套用于付费云端**。
- `services/nvpair-proxy/ingress.go`：本地明文请求限制 Loopback；远程集群通过 mTLS 进入专用 ingress，并终止于本地执行引擎，不允许节点递归转发。
- `services/shared/modelselection/selector.go`：已有 `auto`、`auto-fast`、`auto-balanced`、`auto-best`；先做能力过滤再评分；`RuntimeModel` 有 NodeID、Engine、内存、TTFT、TPS 等字段。**这些信号偏本地模型，不应原样套给云端 API**。
- `services/shared/engines/engines.go`：引擎身份是跨进程契约且绑定发现协议、端口、安装清单、Engine Manager 与 Scheduler。**Cloud Provider 不是一种需要本地端口/模型加载的 Engine**。
- `services/nvpair-ui-broker/broker.go`：`spawnProxy()` 在至少一个 Engine Facade 成功启用（`enabled>0`）后才发送 `gateway/enable`；如果 `enabled==0`，目前按整个 Proxy 启动失败处理。这是 cloud-only 场景的明确代码障碍。
- `android/app/src/main/java/com/nv/pair/models/GatewayModelRepository.kt`：现有 Android 客户端已从 `http://127.0.0.1:14326/v1/models` 读取 Gateway 模型 ID；不要重复另建云模型列表。
- `android/app/src/main/java/com/nv/pair/mnn/http/OpenAiRequestParser.kt`：MNN 只支持部分 Chat Completion 字段；明确拒绝 tools、tool_choice、response_format、多模态等。Cloud Adapter 可支持上述能力，但**不能顺带宣称 MNN 获得了这些能力**。
- `docs/developing.mdx`：Go Service 为业务行为权威；UI 只调用 Broker/渲染状态；变更 RPC 时生产者、Broker、消费者、契约文件、测试和文档必须同一提交链更新；禁止将 Prompt、消息体、PIN 和密钥写入日志。

### 1.2 现有回归基础（仓库已记录，非本次重测）

`docs/M12_5_TEST_REPORT.md` 已记录 Android JVM 143 项、Instrumentation 39 项（其中 3 项条件性跳过）、Android ↔ PC 双向推理、配对持久化、ModelScope 模型安装推理以及多模块 Go/桌面测试通过。Android OS 进程被系统杀死后的全流程恢复和 `go test -race` 在报告中仍是未验证项。本计划把它们列入发布门槛，而不是推断已经完成。

### 1.3 分级问题清单

| 等级 | 定位 | 代码事实与后果 | 施工动作 |
|---|---|---|---|
| P0 | `nvpair-ui-broker/broker.go`，`spawnProxy()` | `enabled==0` 无法使云-only Gateway 独立启动 | 让 Gateway 生命周期独立于本地 Facade 数量；新增 cloud-only 启动/重启/停机测试 |
| P0 | `nvpair-proxy/gateway.go` | 仅从本地 Facade 获取库存，无法展示和选择云模型 | 建立统一候选快照以及云端执行目标 |
| P0 | `nvpair-proxy/gateway.go` | 只选 Engine Facade，没有云端执行路径 | 增加 Gateway Dispatcher，保留原 Facade 处理路径 |
| P0 | `nvpair-proxy/proxy.go` | 本地自动重试可做多次推理转发，付费 API 重试可能双扣费 | 云路径单独重试策略；首次 MVP 默认为**不重放 POST** |
| P0 | Gateway 对外凭据 / 云端凭据 | 本地 Loopback 不能充当付费 API 授权策略；没有云 Provider 认证/授权配置 | 独立入口 Token、密钥保管、配额与显式开关 |
| P1 | `gateway.go` / `selector.go` | 模型名碰撞时按 Engine 偏好解决；云端加入后不能靠裸 upstream model ID 唯一识别 | `PublicModelID` 与目标唯一键分离；引入公共逻辑 ID |
| P1 | `gateway.go` / `selector.go` | `modelCapabilities()` 当前只声明 chat，`requiredGatewayCapabilities()` 仅分析 tools/vision；显式 model 不经自动能力筛选 | 显式与 auto 共用能力验证；JSON Schema、stream、tool choice 等加入要求模型 |
| P1 | `modelselection/selector.go` | 基于参数量/显存/TTFT 的本地评分不考虑云成本和隐私边界 | 策略先过滤授权/能力/预算，再在同一资源类别内部评分 |
| P1 | `ingress.go` | mTLS 可信成员视为接近本机引擎客户端 | 新增**云 API 单独授权**；mTLS 配对不自动授予计费权限 |
| P1 | Android / Desktop UI | 现有列表为模型 ID 视图，未有云 Provider 绑定的用户决策界面 | 在后续 UI 阶段增加配置、授权、状态和预算，不在视图层重写路由 |
| P2 | MNN HTTP Server | 仅支持本地文本推理字段子集 | 保持独立，不借云端改造破坏 MNN 稳定性；按能力阻止不兼容分发 |
| 发布前 | Android OS kill / Go race | 现有报告未运行 | 补真机与 Race 验收 |

**注意**：这些是可核对的设计缺口，不等价于已复现的运行时崩溃。新代码设计应避免在解决这些缺口时引入更大的兼容性回归。

---

## 2. 目标架构与边界

### 2.1 单一入口 + 分开的执行器

```text
        上层：Agent / Chat Client / Workflow Engine
                         |
             http://127.0.0.1:14326/v1
                         |
                  Gateway HTTP
          Auth -> parse -> quota -> route
                         |
                Model Registry (snapshot)
         Public Model ID -> Execution Target
                         |
             +-----------+------------+
             |                        |
       LocalExecution              CloudExecution
             |                        |
      Existing Facade            OpenAI-Compatible
             |                  Provider HTTP Client
       Existing Scheduler             |
             |                        |
       Node owner selection         HTTPS Provider
             |                   (OpenAI/DeepSeek/...)
      MNN / LM Studio / Ollama
             |
          workload events

控制面：Broker (authoritative) -> provider configuration -> Proxy runtime
观测面：统一工作负载事件 -> Broker / UI / 统计
```

**不要做**：

- 不要为 Cloud Provider 在 `services/shared/engines/engines.go` 加 `deepseek`、`openai` 等本地 Engine 条目；这样会牵动 mDNS、Engine Manager、端口规划、安装清单和 Scheduler。
- 不要把 Cloud API 放在 `android/mnn/http/MnnHttpServer.kt`；MNN 是本地执行器，不是路由层。
- 不要在 Electron/Compose UI 里调用云模型并做客户端选择；数据面必须统一由 Go Gateway 处理。
- 不要让所有集群节点在 mDNS 中广播云端密钥、完整 Provider URL 或密钥引用。
- 不要让跨节点云请求递归转发，必须有终端执行 hop、不可路由到自身的防循环约束。

### 2.2 推荐的数据模型（建议新增共享纯类型）

> 以下为**拟定契约**，不代表仓库已有同名类型。建议只有当两个以上 Go 模块确实要共享时才放到 `services/shared`，避免提前制造平台级抽象。

```go
// shared/modelrouting/model.go (suggested)
type ExecutionKind string
const (
    ExecutionLocal ExecutionKind = "local"
    ExecutionCloud ExecutionKind = "cloud"
)

type Capability string
const (
    CapChat Capability = "chat"
    CapTools Capability = "tools"
    CapVision Capability = "vision"
    CapJSONObject Capability = "json_object"
    CapJSONSchema Capability = "json_schema"
    CapStreaming Capability = "streaming"
)

type ModelTarget struct {
    PublicID      string
    Kind          ExecutionKind
    Engine        string // local only
    ProviderID    string // cloud only
    UpstreamID    string // local engine model id / cloud provider model id
    NodeID        string // local only
    Capabilities  map[Capability]bool
    Available     bool
}
```

**关键不变量**：

1. `PublicID` 是 SDK 外部可见的模型名，必须唯一且稳定；`UpstreamID` 只在执行器内部使用。
2. `Kind=cloud` 不要求填写 NodeID/Engine；`Kind=local` 不要求 ProviderID。
3. `ModelTarget` 是选择结果，不应携带 API Key、任意自定义 URL 或隐藏的认证头。
4. 目录由后端生成不可变快照，配置更新以原子换代实现；每次请求自始至终使用同一份快照，避免删除 Provider 期间读到半更新配置。
5. `available=true` 的含义是 Provider 启用且本地配置有效；不能凭未验证的一次网络探测宣称云端一定能够成功或当前有余额。
6. 对所有模型明确记录执行协议和能力：文字 Chat Completion、Embedding、Image、Video 不混用一种未区分的推理接口。

### 2.3 逻辑模型 ID 命名

建议一期面向用户展示：

```text
auto
auto-fast
auto-balanced
auto-best
local/mnn/Qwen3-0.6B-MNN
local/lmstudio/qwen3-0.6b
cloud/deepseek/deepseek-chat
cloud/openai/<configured-model-id>
```

路径片段必须在注册时做可逆、安全的编码，**不要直接将任意 upstream ID 拼成 URL/path**。为兼容已有消费者：

- 保留目前的裸本地模型 ID 作为**过渡期别名**，但仅在解析结果唯一时可用；出现多后端同名时返回确定的冲突错误或要求使用完整 ID，不再按 Engine 偏好悄悄选错。
- `auto*` 作为固定别名，可按策略挑选不同目标；返回可观察的路由结果（内部 request ID / 结构化元数据），不要把 Provider Key 或敏感节点 ID 放进通用响应体。
- 对 `/v1/models` 的结果去重按 `PublicID`，禁止用 `UpstreamID` 去重。
- 外部请求所用 model、内部目标 model、上游响应 model 的映射必须有测试。**第一期明确选择规范**：对外模型 ID 保持用户请求的 `PublicID`（含 `auto*`），上游 `model` 只在内部遥测可见；若改写 SSE，需要正确解析、保留未知事件字段和 usage，不可简单按文本替换。

### 2.4 新增 Gateway 的最小内部接口

```go
// nvpair-proxy/gateway_dispatcher.go (suggested)
type TargetResolver interface {
    Resolve(ctx context.Context, req RequestTraits, modelID string) (ModelTarget, error)
}

type CloudTransport interface {
    DoChat(ctx context.Context, target ModelTarget, req *http.Request) (*http.Response, error)
}
```

保持这两个接口边界：Gateway HTTP 解析/授权，`TargetResolver` 决定目标，Executor 处理数据面。旧 `facade.handleHTTP(w,r)` 作为原有 Local Executor；不要复制 `proxy.go` 的调度器/重试循环。

---

## 3. Web API 合约：第一版明确支持与明确拒绝

### 3.1 对外 HTTP

| 请求 | 一期行为 | 备注 |
|---|---|---|
| `GET /v1/models` | 模型目录和 `auto*` | 默认要求 Gateway 入口 Bearer Token（启用 Cloud 后）；不得暴露密钥/内部 Provider URL |
| `POST /v1/chat/completions` | 非流式文字、流式 SSE、tool calls、JSON 输出能力门控 | 仅将请求派往明确支持该字段组合的目标 |
| `GET /healthz`（建议新增） | 只表示 Gateway 存活，不暴露授权信息 | 仅 loopback，最小信息 |
| `/v1/embeddings` | 一期明确 404/501 或 typed unsupported | 不要宣称“支持”却误转给 chat |
| `/v1/images/generations` | 二期设计 | 不与 ChatCompletion 共享强制结构 |
| 视频/任务查询 API | 后续独立异步接口 | 有 job ID、进度、取消、产物 URL/权限 |

> 基准一律以现有服务端 `GET /v1/models`、`POST /v1/chat/completions` 为起点；新增 `healthz` 需加测试与文档，不可与现有引擎内 `/healthz` 混淆。

### 3.2 RequestTraits（不能只检查工具数组是否非空）

从请求**保留原始 JSON 负载**，只解析路由需要的字段，例如：

- `model`、`stream`、`stream_options.include_usage`
- `messages[].content` 中的图片类型（vision）
- `tools`、`tool_choice`（包括 `required` 或指定 function）
- `response_format.type`（text、json_object、json_schema）
- 消息中的 `tool_calls` 和 role=`tool`（是多轮 Agent 正常调用）
- 上下文长度/Token 上限（只有能确证才推导，否则不做乐观保证）
- 需要的 provider-specific 参数是否有适配器能力支持

自动模式需先经过能力/授权/预算/状态过滤；显式模型也必须经过同一套能力验证。**不得为了兼容而静默删除 `tools`、`response_format`、`stream_options` 等字段**。未知字段：当前受控 Provider 配置可允许透明透传，但若目标不确定支持，应返回带参数名的 400/422，而不是悄悄舍弃。

### 3.3 Cloud Provider Adapter 数据面

**仅作为协议适配器，不承担模型选择或跨节点路由。**

- 统一签名：`ChatCompletion(ctx, providerConfig, upstreamModelID, rawBody) -> http.Response`；`ctx` 必须来自入站 `r.Context()`。
- `BaseURL` 以**API Base URL** 语义保存，例如 DeepSeek 配置为 `https://api.deepseek.com`，客户端构建目标 endpoint 时根据 Provider 配置规则加入 `/chat/completions` 或 `/v1/chat/completions`，绝不无条件追加两次 `/v1`。必须用真实 `/v1/models`（若 Provider 支持）或 fake server 验证路径。
- 用户只能选**配置过并通过地址策略的 Provider endpoint**；请求 JSON 不能控制 URL、Host、认证头、代理地址。生产默认仅 `https`；禁止 redirect 到未授权主机、私网/loopback/metadata 地址（测试环境用显式 allowLoopback 开关）。解析 DNS 之后的连接地址仍需执行限制，防 DNS Rebinding。
- 每个 Provider 使用有界连接池/并发限制、Dial/response header/idle streaming deadline；不能对整个长 SSE 流强设过短的 `http.Client.Timeout`。
- 上游 HTTP status 和兼容 JSON 错误尽量完整保留；网络错误输出统一类型化错误，不能回显 URL 中 query token、请求正文、凭据或 Provider 原始敏感 header。
- 流式原样逐事件输出（若实施 `model` 改写则经过有界 SSE Parser）；客户端断开 -> 取消上游 context 并释放并发槽；SSE `data: [DONE]` 终止语义按 Provider 能力处理，不能自己重复追加 DONE。
- 处理 `429`、`Retry-After`、`401/403`、上下游 5xx、EOF、截断 chunk、无响应 header、无法解析 usage。应明确不同错误的可重试性质与是否计费不确定。
- **默认不自动重试已发送的云端 POST**。不能因为未收到首个 Token 就假定 Provider 未执行：Cloud API 可能已经计费。只有在确认请求字节未发出且仍处于连接建立阶段时，才可考虑一次等价重连；初期全部禁用重发更安全。跨 Provider fallback 仅限未发出请求前的路由选择（包括禁用/配额/能力不足）。
- 遥测汇总：request ID、PublicID、ProviderID、HTTP 状态、是否 stream、耗时、usage/费用估算（如果已知）、取消状态；永不打印消息/工具参数/Authorization header。
- 非流式应限制最大响应体；SSE 限制单事件最大字节数、防无限行/帧和慢客户端资源占用。

### 3.4 错误码映射建议

| 情况 | 下游状态 | type/code 建议 | 不应做的事 |
|---|---:|---|---|
| 缺少/错误 Gateway Bearer | 401 | unauthorized | 不要尝试云请求 |
| Cloud 被禁用/无执行权限 | 403 | cloud_not_allowed | 不要自动改变策略 |
| 模型未注册 | 404 | model_not_found | 不泄露内网 Provider URL |
| 不支持工具/vision/schema | 422 | unsupported_capability | 不要删除字段继续生成 |
| 单请求超出预算/并发配额 | 429 | quota_exceeded / rate_limited | 不发起上游请求 |
| Provider 401/403 | 502 或显式上游授权错误 | provider_auth_failed | 不透出真实 Key |
| Provider 限流 | 429 或 503（按标准化契约） | provider_rate_limited | 默认不重试 POST |
| Provider 网络不可用 | 502 | provider_unavailable | 不隐式重放 |
| Provider 超时 | 504 | provider_timeout | 需记录费用不确定标记 |
| 流中断 | 已发送 200 后按 SSE 错误/断流 | stream_interrupted | 不能回写第二个 HTTP 状态 |

> 状态映射必须在**规范与测试中固定**，上表是一期推荐约定。客户端所见错误对象延续 OpenAI-style `error.message/type/code/param`，稳定字段必须写契约测试。

---

## 4. 凭据、授权与预算（P0，不允许跳过）

### 4.1 两种完全不同的 Key

1. **Gateway Client Token**：上层 Agent 调用 `127.0.0.1:14326` 用的本地入口凭据。可轮换、可撤销，决定客户端是否有权触发付费 Cloud。
2. **Upstream Provider API Key**：PAIR 调用 OpenAI/DeepSeek 等上游时使用。客户端、集群广播、模型目录、日志及 UI 状态绝不可读到明文。

**绝不能透传用户传给 Gateway 的 Bearer 到云 Provider**。Adapter 必须先移除所有敏感入站 hop-by-hop/auth/cookie 头，再从受信任配置注入 Provider 自己的凭据。处理响应时也不透出 `Set-Cookie` 等与 Provider 会话有关的 header。

### 4.2 密钥存储优先次序

- 第一批非 UI 开发验收：仅允许显式环境变量或受保护的进程级测试 SecretProvider。测试 Fixture 不得提交真 Key；文档只放变量名，不放凭据。
- 正式 UI 阶段：抽象 `SecretStore`，对 PC 使用 OS 用户密钥存储设施；Android 使用 Android Keystore 管理 Android 本地配置。若某平台尚无可信 SecretStore，实现应报“不支持持久化密钥”，不能静默降级为明文 JSON。
- Go Broker/Proxy 之间应通过受控本地 IPC 传递短生命周期配置或只传密钥引用；必须验证任何日志、异常、RPC notify、诊断包、进程参数不会带出秘密。
- Provider 的公开配置可持久化（ID/名称/BaseURL/型号/能力/是否启用）；Secret 只以引用 ID 关联。不将 API Key 写到 repo、Gradle 配置、Android SharedPreferences 明文、URL 查询参数和环境诊断输出。

### 4.3 授权策略默认值

```json
{
  "cloud_enabled": false,
  "routing_mode": "local_only",
  "allow_remote_cluster_use_of_cloud": false,
  "allow_paid_fallback": false,
  "per_request_max_estimated_cost_usd": 0,
  "monthly_budget_usd": 0
}
```

预算为 0 应视作**未授权收费**而不是无限额。用户明确配置后才可发起收费请求。Cloud 客户端必须受 HTTP 入口鉴权；即使 Loopback，未授权本机程序也不能直接烧钱。更高阶的 Caller ID 与按 Agent 预算在下一轮产品化引入，不影响一期开启/关闭总开关。

计费估算基于已配置价格表版本和 Provider usage；缺少真实 usage 则记录 `unknown`，不要把未知费用当 $0。每次生成前预留预算，结束后按实际 usage 结算/释放差额；无法得知费用时保留风险预留并提示。

### 4.4 跨节点付费权限模型

现有 mTLS 表示**可信集群成员**，并不表示“允许使用我的付费云账户”。第一期只允许 Gateway 所在主机使用该主机配置的 Cloud Provider，不对其他节点自动广告云能力。后续 W6：

- 云执行节点显式发布有限能力摘要（不包含密钥或 BaseURL）。
- 发起方提供经授权的 Caller/Node 身份，云执行节点自行核验 allowlist、预算和限额。
- 执行过程只能有一个终端 hop，使用现有 cluster mTLS 通道或在其上新建专用终端 RPC；拒绝云转云/循环路由。
- Node revoke、Provider disable、取消与重启应能立即生效；请求中途到达失效边界时允许中止。

**W3 完成不等于跨节点云路由已完成。**

---

## 5. 按阶段施工（编码 Agent 必须逐阶段验收、禁止一口气重写）

### W0 — 固定基线与契约（先于业务开发）

**目标**：防止“加了 Cloud 但破坏 M12.5 稳定版本”。

**工作**：

1. 从 `a6cc1a1` 建 `feature/cloud-provider`，确认未提交本地修改后再开始。
2. 保存基线：`docs/M12_5_TEST_REPORT.md`，记录 `android/README.md`、`docs/developing.mdx` 与 `nvpair-proxy/README.md` 当前契约。
3. 新增 `docs/PAIR_CLOUD_API_CONTRACT.md`（建议）：明确配置 schema、模型命名、能力和错误映射。
4. 加不可退化测试：`gateway_test.go` 原有模型目录、auto、MNN tools 排除、显式 model、请求大小上限；本地 streaming/cancel；`services/tests` 现有双向路由测试。
5. 将 Gateway 缺少 Facade 时启用/禁用/重启的情况写成**失败先行**测试；确认当前 cloud-only 的 `enabled==0` 行为。**尚未修改生产逻辑**。

**交付物**：基线测试记录、接口契约、cloud-only 启动失败用例。  
**Gate**：全套已有 Go/Android/桌面核心测试保持通过；新增测试失败原因是目标行为尚未实现，而非编译错误。

### W1 — Provider Registry、元数据和配置模型

**建议修改/新增**：

- `services/shared/modelselection/selector.go`：仅增加适配所需的非本地模型元数据和选择过滤能力，避免破坏原有本地评分。
- 建议 `services/shared/modelrouting/`（**需要跨进程共享时才新增**）：强类型 `ModelTarget`、`RequestTraits`、`Capabilities`、`RoutingPolicy`。
- `services/nvpair-proxy/cloud_registry.go`（新增）：只维护 Provider 运行时快照、启用状态、逻辑 ModelID 映射。
- `services/nvpair-proxy/cloud_config.go`（新增）：解析**非密钥**配置，严格拒绝未知安全相关字段与无效 URL/duplicate PublicID。
- `services/nvpair-proxy/cloud_registry_test.go`（新增）。

**Provider Config 示例（不含 Secret）**：

```json
{
  "schema_version": 1,
  "providers": [{
    "id": "deepseek-primary",
    "protocol": "openai_chat_completions",
    "base_url": "https://api.deepseek.com",
    "auth_ref": "user-vault:deepseek-primary",
    "enabled": true,
    "models": [{
      "public_id": "cloud/deepseek/deepseek-chat",
      "upstream_id": "deepseek-chat",
      "capabilities": ["chat", "streaming", "tools", "json_object"]
    }]
  }]
}
```

**要求**：

- `auth_ref` 是不透明凭据引用，不能把 `apikey` 混入普通配置。
- 配置 Schema 有版本号；支持幂等加载、原子换代、非法配置保留最后一份有效运行快照且报告错误。
- 同一 upstream model 允许在多个 Provider 共存，但 PublicID 必须唯一。
- 模型目录以**配置的、启用且受授权的模型**为基础，不依赖对每个云 Provider 实时调用 `/models`（很多兼容服务不提供稳定目录）。
- 模型能力由显式可信配置或 Provider 官方预置定义，不能靠 `-vision` 或名字中的 `b` 字样猜。

**Gate**：重复 ID、非法 URL、空 Key 引用、配置热更新、未知能力、开关禁用、并发快照无竞态用例通过。

### W2 — 通用 Cloud HTTP Client（先 Fake Server，再真实 Provider）

**建议新增**：

- `services/nvpair-proxy/cloud_client.go`：受控 endpoint 构建、独立请求、Header 白名单、TLS/代理政策。
- `services/nvpair-proxy/cloud_stream.go`：SSE 适配（若需要改写）和下游取消；不要复制已有 MNN SSE 写入逻辑。
- `services/nvpair-proxy/cloud_errors.go`：错误分类和安全脱敏。
- `services/nvpair-proxy/cloud_client_test.go`、`cloud_stream_test.go`。

**强制测试场景**：

1. Fake server 核对 Authorization 是 Provider Key，不是 Gateway Key；所有 Cookie、Proxy-Authorization、Connection 等不应外传。
2. 原始请求保留 `tools`、`tool_choice`、`response_format`、`stream_options` 等明确支持的字段；仅替换 `model` 为 upstream ID。
3. SSE 分片边界任意切割、多个 `data:` 事件、usage chunk、`[DONE]`、取消、上游突然 EOF、客户端突然断开。
4. 401/403/429/5xx/网络不可达/超时/重定向/private IP/恶意 DNS/大响应体/慢客户端。
5. 云端 POST 失败**不被原有 `maxDispatchAttempts=5` 重放**；Fake upstream 接收到请求次数可断言为 1。
6. 关闭 Cloud 即刻阻止新请求，已启动请求根据明确关闭策略取消或等待结束；资源槽无泄漏。

**Gate**：Fake HTTP/SSE 全部通过，`go test -race ./...`（支持 Race 的 OS）无竞态；不新增真实 Key 依赖。

### W3 — Gateway Dispatcher 与 cloud-only 生命周期

**修改目标**：

- `services/nvpair-proxy/gateway.go`：以 `ModelRegistry.Resolve()` 替代当前仅选 Facade 的决策入口；保留原有路由 API；分 `local` / `cloud` 两类执行路径。
- `services/nvpair-proxy/gateway_dispatcher.go`（新增）：显式模型与 `auto*` 的统一分发，防止 Gateway HTTP handler 膨胀。
- `services/nvpair-proxy/proxy.go`：**只进行必要的共享事件/状态复用**；本地 `facade.handleHTTP`、retry/reservation、mTLS ingress 的行为不改。
- `services/nvpair-ui-broker/broker.go`：`gateway/enable` 独立于 `enabled > 0`；把“Proxy 完全不能启动”和“0 个本地 Facade 但 Gateway 仍可执行云任务”拆开。调整 supervisor 对启动状态、重启和清理的语义。
- `services/nvpair-proxy/gateway_test.go`：扩充正反向选择和新接口测试。
- `services/nvpair-ui-broker/*_test.go`：cloud-only / proxy crash-restart / no engine / Gateway listener bind error。

**调度规则（优先实现确定性，不引入复杂 AI 评分）**：

```text
请求 -> gateway auth -> traits -> policy -> registry snapshot
  -> public model ID/auto policy
  -> allowed by caller + allowed by local config + supports required capabilities
  -> within budget, concurrency, status
  -> target.kind == local ? original facade.handleHTTP : cloudClient.DoChat
```

路由策略：

| policy | 行为 |
|---|---|
| `local_only`（默认） | 只选本地，没有就返回 unavailable |
| `cloud_only` | 只选显式启用且授权的 Cloud |
| `prefer_local` | 先选本地兼容目标；没有时**只在用户显式开启 paid fallback 后**选择 Cloud |
| `prefer_cloud` | 先选获授权 Cloud；没有才选本地（明确提示本地可能能力不同） |

当前 `auto-fast/balanced/best` 的评分语义是模型质量/延迟倾向；**不要把它们直接重定义成收费策略**。先用“资源范围过滤 + 现有本地排序 + 云端单独排序”完成 `auto*`。模型质量和价格指标不足时不发明数值，采用用户配置的优先顺序和稳定 tie-break。

**cloud-only 特别验收**：禁用所有 Ollama/LM Studio/MNN Facade 但打开 Cloud Provider，`14326` 仍正常监听，`GET /v1/models` 返回配置的可用云模型，Cloud Chat 可执行；重启 Broker 后同样恢复。无本地/无云资源时 Gateway 健康状态与 API 错误明确，不启动重试风暴。

### W4 — 统一认证、配额、Workload 与能力选择

**建议扩展**：

- `services/nvpair-proxy/gateway_auth.go`：本机调用的 Client Token 验证和未来 Caller ID，不与 Provider Secret 混用。
- `services/nvpair-proxy/gateway_policy.go`：政策检查、预算预留、能力过滤和限流；预算账本持久化须有明确所有者，不能仅存易丢失的内存计数。
- `services/nvpair-proxy/gateway_workload.go`：把云请求投影为 PAIR 工作负载，不伪造 MNN/Ollama 的 NodeID。
- `services/shared/modelselection/selector.go`：扩展 RequestTraits→Capabilities 的筛选和确定性候选选择；保留原有本地算法回归测试。
- `services/nvpair-ui-broker` 对应转发和事件/错误通路：保证新通知不冲破现有 JSON-RPC 日志规则。

**Workload 扩展要求**：最小改动，先明确稳定字段 `requestId`、`kind=cloud`、`providerId`、`publicModelId`、`state`、`timestamp`、`usage`、`costEstimate`。现有 Go/Android/Desktop 都会消费工作负载；若现有结构 `Engine` 非空为硬约束，**不要随手塞一个 `engine="cloud"` 规避校验**，必须把消费者、数据模型、排序及事件结构同步更新并补回归。

**必须覆盖**：

- Cloud disabled、未授权 Caller、无预算、未注册模型均是**零上游请求**。
- `tools` / `tool_choice` 不走 MNN；显式 MNN tools 请求应有稳定的 unsupported 错误而不是无意的 Native 失败。
- strict JSON Schema 请求只分给声明支持的模型；unknown 不能假定支持。
- 在流式取消/超时/客户端断线场景，账本和工作负载最多产生一次 terminal 事件。
- `/v1/models` 不泄漏节点 IP、内部端口、Provider Key、凭据引用。

### W5 — 桌面配置界面 + Broker RPC（PC 先可用）

**遵循 `docs/developing.mdx` 的端到端路径**：

1. `services/nvpair-ui-broker/cloudproviders.go`（建议新增）：管理 Cloud Public Config、Cloud 开关、Provider Secret 引用；严禁将 Secret 明文作为可订阅 state。
2. `services/nvpair-ui-broker/README.md`、`services/nvpair-proxy/README.md`、`spec.md`：更新方法和有效负载。
3. `desktop/src/shared/types/ws-channels.ts`：新增 typed 请求/推送通道；控制面使用 Broker 的 RPC，不让 UI 绕过后端。
4. `desktop/src/electron/service-bridge/empty-handlers.ts`：新增受控转发。
5. `desktop/src/electron/service-bridge/modular-state.ts`：快照 + 配置变更推送；不缓存 Secret 明文。
6. `desktop/src/ui/api/pair-api.ts`、`desktop/src/ui/stores/*`、`desktop/src/ui/components/ServiceSettings/*`：Provider 配置、模型勾选、Cloud 总开关、路由模式、预算和状态。
7. `desktop/docs/services-api.md` 用既有命令生成、校验；Desktop lint/typecheck/unit/dead-code 全通过。

**交互最小集**：Provider 类型（Generic OpenAI Compatible / DeepSeek 预置）、API Base URL（预置或 allowlist 校验）、API Key（仅一次写入，不回显）、Test Connection（手动按钮，**禁止保存时自动消费 Token**）、Enabled、允许的模型、总 Cloud 开关、路由策略、每次/周期预算、费用确认。

**Provider API Key 输入**：界面到 Broker 的安全写入 API 是一个一次性命令；响应只能包含 `credentialConfigured: true` 等布尔量。提交前验证日志/异常过滤；UI 不通过普通配置 GET 回读真实 Key。

### W6 — Android 的 Cloud 控制与跨节点使用（可拆两次交付）

**W6A Android 展示与切换**：

- 更新 `android/app/src/main/java/com/nv/pair/models/GatewayModelRepository.kt`：在现有 `/v1/models` 读取逻辑基础上展示本地/云端类别；不平行新建另一个源。
- 更新 `android/app/src/main/java/com/nv/pair/rpc/`（RouterApi/BrokerSession 相应协议）与 `runtime/PairRuntimeService.kt`：消费 Broker 的新配置和状态命令。
- Android 原生 Compose 界面（在当前应用 UI 组织下）添加 Cloud 开关、云模型列表、预算提示；不要在 ViewModel 中实现路由算法，也不要调用上游 Provider。
- Android Keystore 仅在 Android **本机**要保存 Cloud Key 时使用；若只使用 PC 端 Cloud，则 Android 只维护云使用授权状态，不复制 PC Key。

**W6B 真正跨节点云执行**：

- 明确部署模式：PC 作主 Gateway，Android 作本地执行节点是一期开箱可用路线；若 Android 本机 Gateway 要远程调用 PC 的云账号，必须新建**受授权的终端云执行协议**。
- Cloud 资源广播仅发可公开能力/状态摘要，不发 Provider Key、BaseURL、凭据引用。
- 使用现有 mTLS 信任，但单独要求 “allow this paired node to use my paid Provider”。
- 对账与计费由持有 Provider Key 的执行节点完成；Caller/Node 身份随审计归档。断网、取消、节点移除后请求不能无限重试。

**Gate**：PC 配云 Key + Android 无 Key；经显式授权 Android 使用 PC 云资源成功，未授权失败且 PC 上游请求计数为 0；PC 离线、重复配对、身份移除均应拒绝/停止。若 W6B 未做，UI 必须明确展示“当前仅在配置云密钥的主机可用”，不能标作集群云调度已完成。

### W7 — 多 Agent + 生图下一阶段验证

- Harness 测试矩阵：普通聊天、工具调用→工具响应回合、结构化 JSON、SSE 增量、并发 10/30 个 Agent、断线取消、一次性 Token 费用上限、带预算的 Cloud fallback。
- 推荐先以 LangGraph 或上层现有 Agent 产品作为客户端，**PAIR 仍不编排 Agent 工作流**。
- 多 Agent 负载指标：本地 NodeID/模型/队列/吞吐量，云 Provider 限流、估计费用、超时、错误和 usage；不要把云虚拟成一个拥有虚构 GPU 显存的节点。
- 图像任务独立定义 `ImageGenerationExecutor`：输入文字/参考图、输出对象引用、费用和安全限制；视频任务独立定义异步 `submit/status/cancel/result`，后续再引入排队、公平性、显存资源和云本地混合调度。

---

## 6. 施工文件清单（现有文件 vs 新增建议）

| 文件/目录 | 操作 | 理由 | 时点 |
|---|---|---|---|
| `services/nvpair-proxy/gateway.go` | 修改 | 统一入口分流本地/云；目录、auth、策略 | W3 |
| `services/nvpair-proxy/gateway_test.go` | 扩展 | 原路由全回归 + cloud-only | W0/W3 |
| `services/nvpair-proxy/cloud_registry.go` | 新增建议 | Provider 元数据、快照及模型 ID 映射 | W1 |
| `services/nvpair-proxy/cloud_client.go` | 新增建议 | HTTPS 上游 HTTP Adapter | W2 |
| `services/nvpair-proxy/cloud_stream.go` | 按需新增 | SSE 格式与取消 | W2 |
| `services/nvpair-proxy/cloud_errors.go` | 新增建议 | 错误类型与脱敏 | W2 |
| `services/nvpair-proxy/gateway_dispatcher.go` | 新增建议 | 边界清晰的执行目标派发 | W3 |
| `services/nvpair-proxy/gateway_auth.go` | 新增建议 | Gateway Client Token | W4 |
| `services/nvpair-proxy/gateway_policy.go` | 新增建议 | Cloud 授权、预算与并发 | W4 |
| `services/shared/modelselection/selector.go` | 小范围修改 | 能力筛选与本地打分分离 | W1/W4 |
| `services/shared/modelrouting/*` | 条件新增 | 仅跨模块共享时新增强类型 | W1 |
| `services/shared/engines/engines.go` | **不改** | 云不是本地 Engine | 全程 |
| `services/nvpair-proxy/proxy.go` | 原则上不改数据面 | 复用本地执行，不引入付费重试回归 | W3/W4 |
| `services/nvpair-proxy/ingress.go` | W6B 才考虑 | 终端 mTLS Cloud 授权，不能泛放行 | W6B |
| `services/nvpair-ui-broker/broker.go` | 小范围修改 | Cloud-only Proxy/Gateway 生命周期独立 | W3 |
| `services/nvpair-ui-broker/cloudproviders.go` | 新增建议 | Provider 控制面及 UI 权威状态 | W5 |
| `desktop/src/shared/types/ws-channels.ts` | 修改 | typed 新 RPC/推送 | W5 |
| `desktop/src/electron/service-bridge/empty-handlers.ts` | 修改 | Broker RPC 转发 | W5 |
| `desktop/src/electron/service-bridge/modular-state.ts` | 修改 | 服务为权威的状态快照/推送 | W5 |
| `desktop/src/ui/api/pair-api.ts` 与相关 Store | 修改 | UI 控制面 | W5 |
| `desktop/src/ui/components/ServiceSettings/*` | 修改 | 云端配置界面 | W5 |
| `android/.../GatewayModelRepository.kt` | 修改 | 展示统一模型目录 | W6A |
| `android/.../rpc/*`、`runtime/PairRuntimeService.kt` | 小范围修改 | 消费 Broker 控制面，保留前台服务现状 | W6A |
| `android/.../mnn/http/*` | **不改** | Cloud 不是 MNN HttpServer | 全程 |
| `docs/developing.mdx`、各组件 README/spec | 必要时修改 | 架构约束、RPC 契约、运行方式 | 每阶段 |
| `docs/PAIR_CLOUD_API_CONTRACT.md` | 新增建议 | 单一真相的接口契约 | W0 |
| `services/tests/*`（新测试） | 新增 | 多进程 cloud-only、恢复/路由回归 | W3/W6 |

**禁止**让编码 Agent 一次 PR 改动以上所有文件；每阶段尽量控制在一个可构建/可回滚的提交组中。遇到实际文件结构与本文建议不一致，以仓库实时源码为准，先更新计划再施工。

---

## 7. 验收矩阵（至少这些测试）

| 测试用例 | 条件 | 预期 | 阶段 |
|---|---|---|---|
| local-01 | 无 Cloud 配置，LM Studio 有模型 | 旧模型路由/响应不变 | W0/W3 |
| local-02 | Android MNN + PC | M10/M65 双向推理与 SSE/取消仍通过 | W0/W3 |
| local-03 | MNN 仅聊天 | `tools` 不被静默路由到 MNN | W3/W4 |
| cloud-01 | 只启用 Cloud，0 Facade | Gateway 启动、模型可见、可推理 | W3 |
| cloud-02 | Cloud 默认关闭 | 任何 Cloud 请求都不能到达 Fake upstream | W2/W4 |
| cloud-03 | Gateway Key 错误 | 401，Fake upstream 请求计数 0 | W4 |
| cloud-04 | 独立 Gateway Token + Provider Key | upstream 仅收到 Provider Key | W2 |
| cloud-05 | tools + stream | tool_calls SSE 语义完整，直到 DONE | W2/W4 |
| cloud-06 | JSON Schema 严格输出 | 不支持者 422，支持者转发原字段 | W4 |
| cloud-07 | Cloud 429 / 5xx / timeout | 一次请求仅一次上游 POST，不重复付费 | W2 |
| cloud-08 | 上游 SSE 中断 | 下游报告中断，不伪造正常结束 | W2 |
| cloud-09 | 客户端取消 | 上游 context 取消，并发槽释放，terminal 一次 | W2/W4 |
| cloud-10 | Provider URL 私网 / DNS rebinding / redirect | 拒绝或安全终止，且不泄密 | W2 |
| cloud-11 | 同名本地模型 + 云模型 | 完整 PublicID 路由准确；裸 ID 不歧义 | W3 |
| cloud-12 | 修改路由策略 | 不改变 URL 即切换本地/云 | W3 |
| cloud-13 | 预算归零 | 不请求上游，报告 quota 错误 | W4 |
| cloud-14 | 配置删除 / Proxy 重启 | 快照一致，Gateway 可恢复，无 key 日志 | W1/W3 |
| cloud-15 | 并发与 Fake Provider 慢流 | 有界并发和内存，无请求泄漏 | W2/W4 |
| cluster-01 | 已 mTLS 配对但没云授权 | 远端不能消费本机付费 Cloud | W6B |
| cluster-02 | 授权后撤销 | 新请求立刻拒绝，费用在执行节点记账 | W6B |
| agent-01 | Agent tools 两回合 | 工具调用/工具响应可完整经过 Gateway | W7 |
| agent-02 | 多 Agent 并发 | 本地与云队列/归属/成本指标合理 | W7 |

### 7.1 命令（以仓库实际运行平台和依赖为准）

```powershell
# 代码规范和基础 Go 回归
cd services/nvpair-proxy
go test ./...
cd ../shared
go test ./...
cd ../nvpair-ui-broker
go test ./...
cd ../tests
go test ./...

# 桌面类型与 IPC 契约
cd ../../desktop
npm run lint
npm run typecheck
npm run test:unit
npm run dead-code:check
npm run service-contracts:check

# Android（Windows）
cd ../android
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug

# Git 工作区检查（回仓库根目录执行）
git diff --check
node scripts/spdx-headers.mjs
```

如果修改了 Broker/RPC 的 payload，应在 Desktop 目录执行 `npm run service-contracts:write` 生成后再执行 `npm run service-contracts:check`，并核对 `desktop/docs/services-api.md` 的变化。**不是所有测试都能在 Windows 的无 GCC 环境下跑 Race**，需在 Linux/macOS CI 补 `go test -race ./...`；真机 Android 双向路由继续按仓库 `android/scripts/run-m10-pc-to-android-acceptance.ps1` 和 `run-m65-android-to-pc-acceptance.ps1` 及测试报告所需模型/设备条件执行。

### 7.2 一期手工验收命令示意

```powershell
# Gateway 只监听本机回环地址；云端授权使用 PAIR 已保存的 Provider Key。
$base = 'http://127.0.0.1:14326/v1'
Invoke-RestMethod -Uri "$base/models" -Method Get

$body = @{
  model = 'cloud/deepseek/deepseek-chat'
  messages = @(@{ role = 'user'; content = 'Reply with OK' })
  stream = $false
} | ConvertTo-Json -Depth 12
Invoke-RestMethod -Uri "$base/chat/completions" -Method Post -ContentType 'application/json' -Body $body
```

`cloud/deepseek/deepseek-chat` 是本文拟定的**目标逻辑 ID**，配置前不会出现在当前仓库的目录中；这不是现成命令的功能承诺。

---

## 8. 回滚、兼容性与版本治理

1. 通过 `feature/cloud-provider` 分支实施，不直接覆盖稳定的 `feature/android-build`。每阶段打提交标记（例如 `cloud/w0-contract`）。
2. 首期 Cloud feature flag 默认为关闭；关闭时 Gateway 行为必须与基线等价（模型列表变化如引入新规范必须由明确迁移/兼容测试覆盖）。
3. 云端模型 registry、上游凭据仓库和 budget policy 三个资源可单独清理；删除 Provider 时停止新请求，明确处理已有 SSE。
4. 配置变更采用 schema_version；未知/不兼容版本报 typed error，不无声忽略。不能因加载异常清空原本可用的本地模型列表。
5. 新增控制 RPC 时同时更新 Go 生产者、Broker、Desktop/Android 消费者及契约；不要保留两个永久并存且语义冲突的配置接口。
6. 监测 Cloud 关闭时的本地路径延迟、内存、错误率，确保不会因新增 Registry 锁而降低本地吞吐。
7. 支持一键关闭所有 Cloud 调用并撤销 Gateway Client Token。回滚无需删除本地 MNN 下载的模型和配对身份。

---

## 9. 编码 Agent 工作规则（可直接放入任务提示词）

> 请严格基于 `feature/cloud-provider` 当前 HEAD 和本文施工阶段开展工作。每次只实现我指定的 Wn 阶段。先阅读该阶段所涉现有实现、`docs/developing.mdx` 和相关 README/spec，列出实际需要修改的文件、入口和边界，再进行最小变更。Go 服务是行为权威，Desktop/Android 仅消费控制面。优先标准库、短函数、强类型、小接口和表驱动测试，避免复制现有 Facade/Scheduler、MNN Server、配置类型或 SecretStore。不得引入未授权的 HTTP 外连、任意 URL 代理、密钥日志、付费请求自动重试或无限队列。每个阶段写失败先行测试、实施并运行指定构建与回归；测试无法执行必须写出原因和剩余风险，不可声称通过。禁止使用任意成功响应掩盖错误、吞掉异常、引入无法删除的兼容旁路。完成时提交：修改文件清单、架构图/调用链、测试命令及实测输出、已知缺口、下阶段前置条件。

### Code Review 必问清单

- 这个逻辑已经在 `shared/`、Broker、Proxy、MNN 或 UI 中存在吗？是否重复发明？
- 从 Client Token 到 Provider Key 的信任边界是否清楚？任意日志和错误是否脱敏？
- 云请求断流/429/5xx 时是否会被当成本地请求重试？能否造成重复费用？
- 哪些条件下请求会被标作“completed”？是否真的完整收到上游结束事件？
- 是否有明确取消和资源释放路径？有无 goroutine/socket/队列泄漏？
- auto 与显式 model 是否使用同一份能力和授权检查？
- 启用/禁用配置是否原子？Broker crash/restart 时是否恢复？
- 是否触及 Android 的本地 MNN 性能和生命周期？有无不必要修改？
- 新增 RPC 有没有同步更新 UI bridge、契约测试与文档？
- 是否有零本地引擎 cloud-only 的可执行测试，以及 Cloud 关闭时的完整回归？

---

## 10. 第一阶段建议的 PR/提交顺序

| 变更组 | 名称 | 完成条件 |
|---|---|---|
| PR-0 | `docs: define cloud provider and gateway contract` | W0 契约和失败先行测试，当前分支无行为回归 |
| PR-1 | `feat(proxy): register cloud models with typed targets` | Registry、目录唯一性和配置校验可测试，不含真实网络发起 |
| PR-2 | `feat(proxy): add non-retrying OpenAI-compatible cloud transport` | Fake Provider 非流式/SSE/取消/错误安全测试通过 |
| PR-3 | `feat(gateway): dispatch local and cloud, support cloud-only` | 单 URL 两类后端切换和无本地 Engine Gateway 启动通过 |
| PR-4 | `feat(gateway): authorize and budget paid cloud access` | Token、授权、预算、日志安全、Workload 通过 |
| PR-5 | `feat(desktop): manage provider settings from broker` | UI 配置、SecretStore、契约链完成，真实 Provider 小额 smoke 通过 |
| PR-6 | `feat(android): show gateway cloud resources` | Android 目录与开关展示，不改变 MNN 引擎数据面 |
| PR-7 | `feat(cluster): authorize terminal remote cloud execution` | 显式授权、零密钥共享、跨节点收费权限和撤销验收 |

**推荐开始顺序**：现在先做 **PR-0 → PR-1 → PR-2 → PR-3**；到 PR-3 就能证明“一人公司 Agent 的统一路由价值”，无需等待 Android OS kill、视频生成和商业计费面板全部做完。但正式面向外部用户启用付费 Provider，**PR-4 的入口认证、授权和预算是不可跳过的发布条件**。

---

## 11. 源码与协议参考

所有 GitHub 链接都指向审查基线所在分支（合并之后可改成 commit permalink）：

- [Gateway implementation](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-proxy/gateway.go)
- [Gateway tests](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-proxy/gateway_test.go)
- [Engine profiles](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-proxy/engines.go)
- [Proxy data plane](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-proxy/proxy.go)
- [Cluster ingress](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-proxy/ingress.go)
- [Shared selector](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/shared/modelselection/selector.go)
- [Shared engine registry](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/shared/engines/engines.go)
- [Broker supervisor](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/services/nvpair-ui-broker/broker.go)
- [Android Gateway model repository](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/android/app/src/main/java/com/nv/pair/models/GatewayModelRepository.kt)
- [Android MNN request parser](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/android/app/src/main/java/com/nv/pair/mnn/http/OpenAiRequestParser.kt)
- [Developer guide](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/docs/developing.mdx)
- [M12.5 test report](https://github.com/afantastic1/Personal-AI-Router/blob/feature/android-build/docs/M12_5_TEST_REPORT.md)
- [DeepSeek Chat Completion API](https://api-docs.deepseek.com/api/create-chat-completion/)（对上游 SSE、tools、响应约定做适配测试时参考；各 Provider 版本可能不同，应以运行时实际能力为准）

**文档维护规则**：完成某个 Wn 后，在对应 PR 的实施报告里把“现状/风险/建议”改为真实代码路径、完整测试结果和未实现清单。本文不是源代码之外的第二套执行真相。
