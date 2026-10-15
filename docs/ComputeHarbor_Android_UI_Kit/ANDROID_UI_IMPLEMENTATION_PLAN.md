<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0
-->

# ComputeHarbor Android UI 专项施工计划（AI Coding 执行版）

> 日期：2026-10-14  
> 范围：原 UI 专项以 Android UI、Android 展示状态聚合和直接必要的契约适配为主；经用户 2026-10-14 明确批准，本次附加实施《MOBILE_CLOUD_AND_TOOLS_SPEC.md》中的 Android OpenAI 兼容 provider 管理、云工具协议透传和必要的 Gateway 验证。直接 MNN 仍明确拒绝 tools；不实现 MNN 工具调用引擎、不重写 Cluster 安全协议。  
> 依据：`afantastic1/ComputeHarbor` `main` 分支的 `AGENTS.md`、`docs/developing.mdx`、`docs/architecture.mdx`、Android Kotlin/Compose 代码，以及用户提供的 PC 界面截图。开工时必须记录新的 HEAD SHA，复核是否与本计划一致。  
> 视觉参考：同目录 `android-ui-reference.png`。**示意图并非事实来源**；图上模型、端口、指标、OpenCL 与 Tool Calling 标签均不得硬编码。

## 0. 交付目标、不可妥协的原则

完成后的手机端是一个 **MNN 本地推理节点 + ComputeHarbor 路由/集群客户端**，不是把 PC 推理引擎搬到 Android：

1. 首页第一屏最醒目位置提供 **Start / Stop**；运行、启动、停止、重连、出错五类状态清晰，避免运行中仍提示启动。
2. **Local Inference** 只展示此 Android 安装实际存在的引擎（当前 MNN）及其可用后端。不能显示 Ollama、LM Studio、llama.cpp 为手机本地引擎。
3. **Connected Nodes** 按真实节点展示，**Active Inference** 为该节点的子信息；远程 PC 节点可显示 `LM Studio · model`，但绝不并入手机 `Local Inference`。
4. **Cluster 配对使用弹窗**：发现节点 → Pair → 弹窗请求 → PIN 验证 → 等待 → 成功/失败；固定 PIN 输入框从 Cluster 主页面移走。仍遵循现有基于 PIN 的 EAP-NOOB 认证；不要用示意图上的“双方比对相同验证码”替换真实协议。
5. **Models** 页面仅在 `On-device Models` 区展示手机安装的 MNN 模型；远程与云模型作为另一个明确分区。Tool Calling 能力只能显示来自真实后端且可验证的状态，否则是 `Unknown / Not verified`，不可凭模型名猜测。
6. **Local API** 显示真实统一网关地址、监听范围、可复制的安全地址及可用性；不能把内部 MNN loopback `14325` 错写成统一网关 `14326`，也不能从截图假设手机在其他设备上可直接访问 `127.0.0.1`。
7. 所有界面状态必须来自 **Broker / RuntimeService / Repository / 模型管理器**。UI 不进行节点发现、引擎探测、授权或调度决策。
8. 与 PC 共享业务语义，不强求同样的导航。移动端采用 Material 3 / Jetpack Compose；尊重可访问性、窄屏、系统字体缩放。

### 明确不做

- 不为 Android 安装或展示 Ollama/LM Studio/llama.cpp 本机按钮；不通过 `if (engine != "ollama")` 之类的孤立 UI 黑名单掩盖上游状态污染。
- 不新增假手动 Load 功能；MNN 继续按需加载。诊断状态可只读展示。
- 不新增一套独立路由器、第二个 `ClusterManager`、新配对协议或前端缓存的真实节点清单。
- 不将 Tool Calling `Supported` 当作产品承诺，除非 capability 接口和通过的契约测试能支撑。
- 不顺手改动跨平台 Go Service 语义；如果发现后端字段不足，先在文档记录协议缺口并单独立项。

## 1. 开工必读规范与源码检查表

**规范优先级**：`AGENTS.md` → 适用 `.cursor/rules/` → `docs/developing.mdx` / `docs/architecture.mdx` → 对应 services `spec.md` / 具体源代码 → 本计划。现有架构的服务端真实逻辑由 `services/` 持有；桌面与 Android 是客户端。

已确认相关文件：

| 路径 | 当前职责 / 已确认现状 | 本次处理 |
|---|---|---|
| `android/app/src/main/java/com/nv/pair/MainActivity.kt` | Compose 入口，三页 `Network / Cluster / Models`；`Start/Stop` 位于 Network 内容底部；同时渲染 `proxies.forEach { ProxyStatusCard }` 和 `MnnLocalEngineCard` | 将首页视图拆为小 Composable，调整顶部操作、节点与推理状态结构 |
| `android/app/src/main/java/com/nv/pair/ClusterManagement.kt` | 主页面直接显示邀请、6 位 PIN 文本框、附近节点及已配对节点 | 引入 Dialog / Sheet 与配对状态机；主页面只保留列表与请求通知入口 |
| `android/app/src/main/java/com/nv/pair/rpc/ClusterApi.kt` | `cluster:invite-node`, `cluster:invite-status`, `cluster:respond-to-invite`, `cluster:cancel-invite` | 优先不改 RPC 格式，复用现有命令和响应 |
| `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeController.kt` | 暴露 `proxies`, `mnnLocalEngine`, cluster 状态以及 Start/Stop 等 actions | ViewModel/Presenter 从这里订阅，不创建第二服务连接 |
| `android/app/src/main/java/com/nv/pair/runtime/PairRuntimeService.kt` | Broker 生命周期、proxy 状态、MNN runtime 和 Cluster 事件的事实来源 | 检查 proxy 语义、错误来源；必要时仅改展示投影，保证 runtime 不被 UI 持有 |
| `android/app/src/main/java/com/nv/pair/data/RouterModels.kt` | `EngineProxyStatus(engine, ready, port)` 没有节点 ID 字段 | 确认其含义是本地 proxy 还是远程引擎，不能直接将 `ready` 当模型 loaded/running |
| `android/app/src/main/java/com/nv/pair/data/PairNode.kt` | 节点带 `modelsByEngine`、`loadedByEngine` | 用节点维度显示远程引擎；缺字段时明确展示 Unknown |
| `android/app/src/main/java/com/nv/pair/models/ModelHubScreen.kt` | MNN 选择、ModelScope/HF、本地导入、安装、网关和云模型内容混在长页面 | 重排分组为 On-device / Browse / Network；沿用现有 ViewModel、安装器和删除逻辑 |
| `android/app/src/main/java/com/nv/pair/runtime/MnnLocalEngineStatus.kt` | Android 本机 MNN 状态 | 本机卡片必须从此类/相应 runtime status 投影数据 |
| `android/app/src/main/java/com/nv/pair/mnn/MnnBackendSelection.kt` | MNN backend 有 CPU/OpenCL 选择 | 只有能力探测确认可用才允许 OpenCL 点击；当前构建可能禁用 OpenCL |
| `android/app/src/main/java/com/nv/pair/mnn/http/OpenAiRequestParser.kt` | 目前明确拒绝 `tools`、`tool_choice` | 本 UI 计划只展示“不支持/未验证”能力，后端支持另行开发 |

**开工动作**：记录分支/提交；逐文件打开而非依赖该表；读取 Android 现有 `build.gradle.kts`、`AndroidManifest.xml`、`MainActivity`、主题文件、测试；搜索桌面现有 cluster pairing 控件以对齐词义，**不照搬 React 实现**。输出一页 `CURRENT_UI_AUDIT.md` 列出保留能力、页面状态来源、需要迁移的回调。

## 2. 信息架构与导航

推荐底部四 Tab（如果项目决定沿用现有三 Tab，可保留 Network 作为 Home 的 wire/内部名称）：

- **Home**：首屏 Start/Stop，节点状态，Local API，本机 MNN，Connected Nodes（含 Active Inference），Recent Jobs。
- **Cluster**：Trusted/Connected Nodes，Nearby Devices，Pending Requests。配对独占弹窗；加入/退出当前 Cluster 的操作仍提供。
- **Models**：`On-device (MNN)`、`Browse / Import`、`Available via Network`；按已有下载/安装逻辑展示。
- **Settings**：设备、MNN Backend、API、存储、诊断、关于；若需要避免新增 Tab，则复用右上角设置按钮进入独立 Settings 页面，底栏仍三项。**选择一种导航架构，不同时保留两套重复入口**。

导航状态在 Activity/导航容器中持有；后退键先关闭 Dialog，再返回上一级，最后回 Home；不要每次切页触发服务 restart 或丢失下载任务。

### 2.1 屏幕文字草图（界面须按照此层级实现）

```text
┌ ComputeHarbor                 ⚙ ┐
│ Mobile Compute Node             │
│ ┌ Node Status · Running ──────┐ │
│ │ [       STOP NODE         ] │ │  ← 第一屏第一张主操作卡
│ │ Device   Android-Phone       │ │
│ └──────────────────────────────┘ │
│ ┌ Local API · Available ──────┐ │
│ │ OpenAI-compatible Gateway    │ │
│ │ http://.../v1        [Copy] │ │
│ └──────────────────────────────┘ │
│ ┌ Local Inference ────────────┐ │
│ │ MNN  · Ready/Idle/Loading    │ │
│ │ Backend: CPU / OpenCL*      │ │
│ │ Model: ... / None           │ │
│ └──────────────────────────────┘ │
│ Connected Nodes (2)             │
│  ├ Desktop-PC · Online          │
│  │  └ Active Inference          │
│  │     LM Studio · Qwen · Run  │
│  └ This Android · Online        │
│     └ Active Inference          │
│        MNN · Idle              │
│ Recent Jobs             View all│
└ Home | Cluster | Models | ... ┘
```

`*` OpenCL 仅在可用时显示并可选择。若 CPU 是唯一可用选项，直接标记 `CPU`，不展示虚假的切换器。

## 3. 状态模型与展示契约（最重要的工程约束）

### 3.1 三种事实必须分离

- **Engine installed / available**：设备是否存在并能探测到引擎；不是请求状态。
- **Model installed / loaded**：安装在磁盘上和载入运行时是两种不同状态。
- **Inference idle / running**：是否有正在执行的任务；`proxy ready` 不等于正在推理。

**建议内部 UI 投影**（名称和文件可按现有风格取舍，不作为 wire contract）：

```kotlin
// UI-only immutable projections; do not persist as runtime truth.
data class LocalInferenceUiState(
    val engineName: String,              // e.g. MNN
    val availability: EngineAvailabilityUi,
    val modelName: String?,
    val backendLabel: String?,           // CPU / OpenCL, only if probed
    val activity: InferenceActivityUi,
    val errorSummary: String?
)

data class NodeInferenceUiState(
    val nodeId: String,                  // stable identity, not display name
    val displayName: String,
    val connectivity: NodeConnectivityUi,
    val activeInference: List<InferenceSummaryUi>
)
```

枚举/密封类只定义有真实数据来源的状态；不要把上述示例强行导出为新的通用公共模型。将 `Unknown` 作为合法状态；不能确定节点或模型时不显示虚假的 Ready。建立**唯一 Mapper**，将 `RuntimeService` / `Repository` 当前快照投影到 UI；避免每张卡片各自依据字符串猜状态。

### 3.2 节点归属不变量

- 本机 `Local Inference` 来自 `mnnLocalEngine` / MNN runtime，不允许从全网 `modelsByEngine` 推算。
- `EngineProxyStatus` 可能是 **本地代理端口状态**：先追查从 `PairRuntimeService.monitorProxyStatus` 到 broker 的来源。如果其代表 gateway facade，移动到 `Local API / Service Proxies` 的诊断页，不在首页显示成“本机 Ollama/LM Studio”。
- `Connected Nodes` 的远程引擎仅展示对应 `PairNode.nodeId` 的 `modelsByEngine` / `loadedByEngine`，不将全网聚合模型指派给某设备。
- 本机节点从成员列表和本机 `nodeUuid` 识别，**不能依赖 `LOCALHOST`、IP 或设备名称判重**。
- Remote `Active Inference` 只有当 workload/engine 状态能确认正在执行时才标记 Running；`loadedByEngine` 单独只能说明 Loaded。没有任务关联时显示 `Loaded` 或 `No active inference / Unknown`。

### 3.3 单一状态源

`PairRuntimeService` → 既有 `StateFlow/Repositories` → `PairRuntimeController` → Compose `collectAsStateWithLifecycle()` → stateless UI components。

派生状态使用 `remember` / `derivedStateOf` 或独立轻量 Mapper；绝不在 Composable 中启动额外 Broker、轮询不受控的网络请求、创建第二个 MNN runtime。对需要异步刷新/动作反馈的功能，以现有 controller/Repository 的结果为准。

## 4. Home 详细施工规范

### 4.1 顶部与主操作

- 顶栏：品牌名、简短节点类型、设置入口；不在此显示 PC 的 GPU 名称。
- **首张卡片**：`Node Status` + 状态指示点 + 最大宽度 Start/Stop；Running 时只显示 Stop 作为主操作（不要同时放看似可点击的 Running 按钮）。
- Stop 是破坏当前推理的操作：如运行任务不可安全中断，显示确认说明；不要误导为“退出应用”。
- 相位处理：Stopped→Start，Starting/WaitingReady→spinner+不可重复点击，Running→Stop，Stopping→spinner，RestartBackoff→Reconnecting，StartupFailed→error+可恢复操作（遵循 controller 能力）。
- 通知权限被拒绝、前台服务无法启动、服务不可用时展示明确原因和恢复方式，不伪装为 Running。
- 屏幕旋转/Activity 重建后状态来自 Controller，不能靠 Composable 内局部 bool 判断是否运行。

### 4.2 Local API 卡片

- 主文案 `OpenAI-compatible API`；展示当前**实际对外可使用**的 Unified Endpoint；复制按钮用 Android Clipboard API，仅操作真实地址。
- Loopback 地址须说明只供手机上的应用使用；局域网/其他设备访问应展示可到达地址且必须符合当前网关鉴权和监听策略；无法确认时显示 `Local only`。
- 展示 `Ready / Starting / Unavailable`；`/v1/models` 不可达和推理服务不可用不能混为一谈。API token 不以明文出现在普通 UI 卡片。
- 禁止将 MNN 内部 `127.0.0.1:14325` 当成统一的 `14326` 路由入口。

### 4.3 Local Inference 卡片

- 标题 `Local Inference`；目前 Android 仅一个 `MNN` 引擎项。
- 状态：Unavailable / Idle / Loading / Ready / Generating / Error；状态来自 MNN；如果 Runtime 的粒度不同则只显示能证明的状态。
- 展示 backend：Auto/CPU/OpenCL 的“偏好”和“生效选择”分开；OpenCL 未编译或 probe 失败时禁用选择并说明。
- 展示当前加载模型和错误摘要；错误详情进入诊断页。`MODEL_LOAD_FAILED` 显示可读提示（例如“模型未能加载；查看日志”），但不猜测是内存还是文件损坏。
- 不增加手动 Load 按钮；卸载/删除模型确认沿用现有模型管理操作。

### 4.4 Connected Nodes + Active Inference

- 一张节点卡至少有：设备类型图标、名字、online/offline、local/remote 标识、详细页面入口。
- **Active Inference 是节点卡的子行**，层级缩进或弱分隔；单节点多个正在推理模型时按真实任务列出（折叠 `+N`），不要只显示一个不确定的字符串。
- PC 节点可出现 Ollama/LM Studio/llama.cpp，前提是该节点确实运行/持有这些引擎；手机本机卡始终是 MNN（若未来新增引擎，使用能力源自动增长）。
- Offline 不要展示绿色 Running；若数据过期标记 `Last seen`，不要使用过期任务冒充实时状态。
- 节点数量计算去重，稳定 key 用 UUID/nodeId；网络断开不应造出“同名两个节点”。

### 4.5 Recent Jobs

- 保留已有 `PairWorkload` 数据；显示模型、来源节点、目标节点、状态、持续时长/时间；正在执行的任务优先展示。
- 状态明确 `Queued / Running / Completed / Failed / Cancelled`；失败信息可展开但不得泄露 Prompt 或凭证；最近 N 条和 View All。
- 不将“云端请求由本手机发起”标记为“手机 MNN 在推理”。

## 5. Cluster 页面与配对弹窗完整设计

### 5.1 Cluster 主界面

结构自上而下：`Current Cluster / membership` → `Trusted / Connected Nodes` → `Nearby Devices` → `Pending Requests`（可带计数标记）→ `Leave Cluster` 危险操作。未创建 Cluster 时展示空状态与 `Create Cluster`；已加入其他 Cluster 的设备不能直接显示可加入（遵从实际后端能力）。

每个附近设备：稳定 identity（用于操作）、设备名、类型、IP（如已知）、状态与 **Pair** 按钮。`Pair` 不应提前加入 Trusted。重复请求期间 disable。刷新有明确 loading/error/empty。

### 5.2 配对状态机

```text
CLOSED
  ├─ 点击 Pair → REVIEW_TARGET
  └─ 收到入站邀请 → INBOUND_REQUEST
REVIEW_TARGET → REQUESTING → OUTBOUND_PENDING_PIN
INBOUND_REQUEST → PIN_ENTRY → RESPONDING
OUTBOUND_PENDING_PIN / RESPONDING
  ├─ 后端确认成功 → SUCCESS
  ├─ 后端拒绝/超时/错误 PIN → FAILURE
  └─ 用户取消 → CANCELLING → CLOSED/FAILURE
SUCCESS → CLOSED (列表由后端 membership 更新)
FAILURE → RETRY 或 CLOSED
```

状态保留 `inviteId` 与 `peerNodeId`，不得仅靠节点名称匹配；同一时刻多个邀请按 inviteId 区分，不能使用当前单个 `pinByInvite` 让 PIN 泄漏到另一个邀请。销毁/旋转后重新从 Repository 的邀请快照恢复可恢复的 pending，不在 `SavedStateHandle` 保存 PIN 或密钥。

### 5.3 Dialog 每个状态必须具备的 UI

| 阶段 | 必须展示 | 操作与约束 |
|---|---|---|
| Review target | 目标节点名、类型、网络地址、当前可信状态 | `Request pairing` / `Cancel`；阻止向自己、已配对者重复邀请 |
| Outbound pending | 后端真实 PIN（现有协议要求邀请方分享），过期提示、目标设备 | `Copy PIN` 是否允许由安全策略定；绝不记录日志；`Cancel invite` 必须向后端发起取消 |
| Inbound request | 来源设备信息、PIN 输入框，清晰说明“输入邀请方显示的 PIN” | 仅允许 6 位数字；`Accept`/`Decline`；输入错误不清除其他邀请状态 |
| Working | 正在验证，不可重复提交 | 可取消时提供 Cancel；否则解释等待 |
| Success | 真实完成结果、节点身份、信任已建立 | Done；主列表依后端更新 |
| Failure | 具体错误：错误 PIN、超时、拒绝、离线、未知 | Retry（若协议允许）/ Close；拒绝和超时绝不自动创建信任 |

**安全红线**：本阶段保留原来的 EAP-NOOB / PIN 安全语义。设计图的“双方显示相同验证码点击确认”只是视觉占位，**当前协议不保证能实现该交互**。若要真正免输入、双方比对码，必须单列协议安全设计并经测试后实施，严禁 UI 假验证。

Dialog 关闭行为：普通 dismiss 不等于取消远端邀请；UI 如有取消按钮要调用后端，等待响应或明确告知取消失败。Back/点遮罩、旋转、离线、超时需要分别处理。避免在 `LaunchedEffect` 无条件重复发送邀请。

### 5.4 与 PC 行为对齐

对齐 RPC 语义、状态标签和 PIN 验证方式即可，PC 继续保留其设置侧栏；Android 用 Material3 `AlertDialog` 或 `Dialog` + 自定义表单。不要为了外观同步引入 React/WebView。

## 6. Models 页面：只显示手机真正拥有的引擎

### 6.1 分区与筛选

1. **On-device Models**：仅从手机 MNN Catalog/ModelHub installed 状态取数据。每行模型 ID/友好名、大小（确实可得才显示）、已安装、下载中、可用、错误、删除等真实状态；不要混入远程 LM Studio 模型。
2. **Browse / Install**：保留 ModelScope、Hugging Face、导入；结果必须校验 MNN 模型包格式，不能把 Hugging Face 原始权重模型直接标作可运行 MNN。
3. **Available via Network**：路由网关发现的远程/云模型另列，清晰标注归属节点/提供商，阅读为主，不能在此误显示本机已安装。
4. **Inference Engine Settings**：仅 MNN；backend Auto/CPU/OpenCL 源自 probe；进度、下载、删除复用现有 `ModelHubViewModel` 和安装器。

### 6.2 Tool Calling UI 的真实能力策略

| 值 | 显示 | 数据规则 |
|---|---|---|
| Verified supported | `Tool Calling · Supported` | 仅能力契约+验证通过 |
| Verified unsupported | `Not supported` | 后端明确声明或已验证 |
| Not assessed | `Not verified` | 当前 Android 默认，不能假设 |

当前 Android `OpenAiRequestParser` 仍拒绝 `tools` / `tool_choice`，因此**当前 Android MNN 不得默认展示 Supported**。真正的 Tool Calling 请求处理、响应生成、SSE 另立后端计划，本 UI 工作只预留能力位及渲染逻辑。

### 6.3 Model Load Failed

在模型行和本地推理卡展示 `Load failed` + 查看诊断。安装完成不能自动标记 `Ready`；加载失败不应误显示 `Model not installed`；不在 UI 强行重试无限次或触发模型重复下载。

## 7. Settings 页面

- **Node information**：设备名、设备标识（截断显示可复制）、本机 IP / 网络状态、版本；值均从真实仓库和运行时读取。
- **Inference**：MNN 能力探测结果、生效 backend、用户偏好、CPU 线程数（**仅当前底层确实支持配置时才可交互**），模型目录存储占用。
- **API / Connectivity**：统一 API 地址、当前监听策略、鉴权状态（不泄露 token）、复制/诊断；只有当前系统明确支持的设置才可修改。
- **Diagnostics**：MNN health、模型加载失败阶段（若后端提供）、broker 状态、最近错误、日志导出/复制（脱敏）。
- **About**：ComputeHarbor 版本、许可信息。

任何在草图中出现但当前实现未暴露的设置（如“LAN Access Control”）都必须先标为 `Future`，不能制作无效开关。

## 8. Design Tokens / Compose UI 风格

参考当前 PC 截图，不沿用此前蓝色大按钮的概念稿：

- 背景：深蓝黑 `#071622` 左右；表层：蓝黑 `#102838` / `#142F40`；分隔线：低对比灰蓝。
- 主要强调：**青绿 Teal `#20C7B2`**；Running/可用用绿色系（且加文本标签）；错误/Stop 红色，但不使用多彩荧光边框。
- 文本：白灰主次分层；使用系统字体及 Compose Material Typography；尺寸 12–24sp 以可读性为准。
- 圆角：卡片约 10–14dp；屏幕水平内边距 16dp；卡片间距 12dp；主按钮高 ≥48dp；点击区 ≥48×48dp。
- 支持浅色/深色策略（若产品只提供深色，遵守项目主题策略）；`fontScale=1.3/1.5` 下不重叠；窄屏 320dp 不水平滚动；系统导航栏、状态栏避让。
- 状态不能只靠颜色：`Running`、`Offline`、`Failed` 均配图标/文字。Dialog 必须有可访问的标题、错误反馈和 TalkBack 顺序。
- 动画只做轻量加载/过渡；无高频无限动画；滚动列表用 `LazyColumn`，稳定 `key` 绑定 nodeId/modelId/inviteId。

**前端可见命名统一**：与 `AGENTS.md` 一致，对用户优先说 `engine`，不要将 `backend` 当作引擎（MNN 是 engine，CPU/OpenCL 是其 execution backend；旧 wire 字段保留不改名）。

## 9. 推荐组件拆分（尽量少，不为抽象而抽象）

建议目录，不要求为了目录漂亮而移动所有文件：

```text
android/app/src/main/java/com/nv/pair/
  MainActivity.kt                 # 只承载 lifecycle / navigation / 顶层状态订阅
  ui/home/HomeScreen.kt           # NodeStatus, Api, LocalInference, Nodes, Jobs
  ui/cluster/ClusterScreen.kt     # Cluster list + pending requests
  ui/cluster/PairingDialog.kt     # Dialog + inviteId 局部表单状态
  ui/models/ModelsScreen.kt       # 根据现有 ModelHubScreen 逐步拆，避免重写下载器
  ui/settings/SettingsScreen.kt   # 设备信息 / MNN / API / 诊断
  ui/state/NodeUiProjection.kt   # 从已有 DTO 投影，保持纯函数
  ui/theme/*                      # Color / Theme / Type
```

先抽 **纯渲染组件** 再移动数据收集；现有 `ClusterManagement`、`ModelHubScreen` 可渐进拆解。每个组件拥有最小事件集合，例如 `onStart`, `onStop`, `onInvite(peerId)`, `onRespond(inviteId, accept, pin)`；不接收整个全能 controller，不在卡片里直接发 RPC。

## 10. 施工顺序（每步独立提交，可撤回）

### U0 — 基线审计与 UI 测试夹具（优先级 P0）

**改动**：只写审计、生成当前状态映射表；增加 Compose Preview / 示例数据工厂。记录当前 SHA、截图、构建通过结果。  
**验收**：识别所有“本机 vs 远程”来源；确认 `EngineProxyStatus` 含义；列出缺失契约。不得先用硬编码过滤掩盖。

### U1 — 真正的本机引擎与节点投影（P0）

**改动**：建立本地 MNN、本地 proxy、远程 Node 三个不同 UI 数据域；`MainActivity` 不再把泛用 `proxies` 当作手机真实推理引擎卡。 `Active Inference` 显示在各节点下。  
**验收**：PC 使用 LM Studio、Android MNN idle 时，手机 Local Inference 只有 MNN，PC 节点下 LM Studio 明确归属 PC；未知状态不写 Running。两节点同名但 UUID 不同时正确区分。

### U2 — Home 页面视觉重排（P0）

**改动**：顶部 Start/Stop、Local API、Local Inference、Connected Nodes、Jobs；拆分 Compose 组件。  
**验收**：第一屏看见 Start/Stop；启动中不连点；CPU-only 正确；页面旋转和后台恢复不改变 runtime；API 复制链接正确。

### U3 — Cluster 弹窗配对（P1）

**改动**：从 `ClusterManagement` 移出常驻 PIN 输入；Dialog 根据 repository 邀请状态驱动；接入现有 `invite/respond/cancel/status`。  
**验收**：双机邀请、输入 PIN、成功、拒绝、错误 PIN、超时、取消、网络掉线、同时收到两个邀请、Activity 重建全部可预测。成功前不出现 Trusted。

### U4 — Models 页面重分区（P1）

**改动**：清晰分离本机 MNN Installed 与 Remote/Cloud，现有模型搜索、下载、导入、删除行为原封不动复用；Tool Calling 缺失能力以 Unknown 展示。  
**验收**：PC 端下载模型不会出现在手机 On-device Installed；MNN 失败模型不误标可运行；不显示 Android 的 Ollama/LM Studio 卡。

### U5 — Settings 与状态细节（P1）

**改动**：本机配置、Backend 探测、API、存储与诊断入口；简化首页二级操作。  
**验收**：不支持 OpenCL 时不可点击；服务不可用时有恢复引导；设置都确实能读写或明确只读；暗色/字体缩放合格。

### U6 — UI 回归、现场验收与清理（P0）

**改动**：删旧界面无用状态、旧 PIN 字段和重复组件；更新 Android README/截图/协议缺口清单，不改自动生成文档。  
**验收**：各阶段测试和双机实测全部有证据；零假状态、零固定引擎硬编码、零敏感日志。

## 11. 测试矩阵（AI Coding 必须落地，不是口头验收）

| 用例 | 期望 |
|---|---|
| Android CPU-only, MNN healthy | Local Inference=MNN CPU；不出现 Android Ollama/LM Studio |
| PC 远程 LM Studio active | 仅在 PC 节点 Active Inference 显示 LM Studio |
| Proxy healthy, MNN failed | API proxy 和 MNN 状态分离，不误报手机模型 Running |
| 已安装 MNN，但未 load | Installed 且未 Loaded，不能写 Ready-to-generate |
| MNN `MODEL_LOAD_FAILED` | 可读失败态、可查看诊断、不误标下载损坏 |
| 节点 Offline/网络断开 | Online 及时变化，历史活动不冒充正在推理 |
| 同名不同 node ID | 不合并、不重复误配 |
| 双端配对/取消/错误 PIN | 后端信任状态正确；弹窗可恢复 |
| 快速点击 Pair 两次 | 只能发起一次请求或明确去重 |
| 两个同时收到的 invites | PIN/错误/关闭行为按 inviteId 隔离 |
| 320dp 窄屏 + 1.5 字体 | 无遮挡、无不可达按钮、无裁剪 |
| 后台→前台/旋转 | 没有额外 runtime 启停、没有重复发送邀请 |
| 手机 On-device Model list | 仅手机 MNN Installed，远程模型分区独立 |
| Tool Calling 能力缺失 | `Not verified`，绝不误标 Supported |
| `14325` vs 统一 endpoint | UI 地址按实际用途呈现，不把内部端口暴露成用户入口 |

**测试类型**：
- JVM 单测：状态 Mapper（engine 归属、node identity、Loaded≠Running）、pairing UI reducer、条件筛选与边界。
- Compose UI 测试：Start/Stop 相位、Dialog 生命周期、PIN 输入、Models 分区、空态、错误态、TalkBack 标签。
- Android Instrumented 双设备：已有 `BrokerSessionSmokeInstrumentedTest`、相关配对/路由测试基础上增补；设备上真正走 RPC 和网络。
- 手工验收：真实 Android + Windows PC，同网/断网、多请求、重启、睡眠恢复；提供截图或录像和测试日志。

### 建议命令（从仓库根目录按实际工具链执行）

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Windows：`gradlew.bat`。最后一项需连接设备，并可能触发真实配对与联网，**只能在授权测试环境运行**。Gradle 不存在的 task 必须先从 `./gradlew tasks` 核对。当前源码若需要额外 Go 构建，可按 `android/README.md` 执行，不允许用“测试未跑”写“测试通过”。

## 12. PR / AI Coding 交付标准

每一阶段要求输出：

1. **改动文件列表**：每个路径说明为何改，层级边界是否改变。
2. **状态来源对照表**：屏幕每一个 Running/Ready/Installed/Connected 标签对应的 source field 和含义。
3. **UI 截图**：Home（Stopped/Running）、Cluster（Nearby/PIN/Success/Error）、Models（Installed/Downloading/Failed）、Settings（CPU-only）。
4. **测试证据**：命令、环境、通过/失败/未运行说明及必要 log；不可把 mock 视作实机。
5. **兼容性说明**：未修改 Go wire contract；若被迫修改，需同时修改生产者、Broker、消费者、测试和文档并运行 service-contract checks。
6. **隐私安全核对**：不能记录 Prompt、完整 messages、响应、PIN、API token/密钥；设备私有路径脱敏。
7. **移除冗余证明**：旧界面 PIN 表单被替换、无双份 StateFlow、无无用 Composable/导出；遵守 SPDX 头、静态引用、规范命名。按仓库要求签名提交 `git commit -s`。

### AI Coding 指令（可直接复制进任务）

> 严格按照《ComputeHarbor Android UI 专项施工计划》执行，只做 Android UI 与必要的 presentation 状态投影。先读 `AGENTS.md`、`.cursor/rules/`、`docs/developing.mdx`、`android/README.md` 和涉及文件，记录当前 HEAD，交付 U0 审计结论，然后按 U1→U6 分批实现。每批先加验证再改逻辑，保留现有 Service/Repository 作为单一事实来源；不可引入第二套路由、配对或模型加载实现。不要在手机 Local Inference 显示 PC 引擎；Active Inference 必须归属节点。Cluster 配对使用 Dialog 但严格复用现有 PIN 安全协议。Tool Calling 未经验证不得显示 Supported。每批给出具体改动、构建/测试结果、未完成问题。发现后端契约不足时暂停该点，报告最小契约变更，不自行扩展所有 services。

## 13. 视觉参考与设计验收说明

参考文件：`android-ui-reference.png`。它只用于色调、留白、卡片、弹窗和页面信息密度。**必须覆盖以下视觉稿错误**：任何 Android 本机卡片出现 LM Studio/Ollama/llama.cpp；任何 unsupported OpenCL 被标成可用；Tool Calling 无证据却标 `Supported`；任何假的 PIN 相互比对流程；任何无法证实的地址、模型和性能数据。优先遵守上述代码和事实来源，不能为了复刻概念图破坏功能。

---

**Definition of Done**：手机首页一眼知道是否运行、哪里是有效 API、MNN 是否可用、哪个设备在运行哪个模型；Cluster 可以通过弹窗完成现有真实 PIN 配对；Models 仅将手机 MNN 标为本机；所有状态由真实 runtime 和 repository 驱动；双机和 Android 仪器化测试可追溯。到此 UI 专项结束，Tool Calling 后端协议与 Auto 路由能力筛选留待独立施工。
