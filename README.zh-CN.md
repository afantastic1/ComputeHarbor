<!--
SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
SPDX-License-Identifier: Apache-2.0

本文件为独立项目 ComputeHarbor 编写；上游来源与授权信息见文末。
-->

# ComputeHarbor

**一个 API，统一访问本地 AI 算力、多设备推理与可选的云端模型。**

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Stage: Early development](https://img.shields.io/badge/Stage-Early%20development-orange.svg)](#项目状态)

[English](README.md) | **简体中文**

ComputeHarbor 是一个开源、**本地优先**的异构 AI 推理路由项目。它连接局域网中兼容的计算设备，依据模型能力、可用性和负载将独立推理请求分发给合适的节点，并向 AI Agent 和应用提供统一的 OpenAI 兼容接口。用户也可以主动配置云 API，并控制何时允许使用付费云端模型。

核心目标是：**利用已有 GPU 和闲置设备处理适合本地执行的任务，在明确授权时才调用云端算力。**

> **项目仍处于早期开发阶段。** 尚未发布独立的 ComputeHarbor 稳定版安装包。部分功能与平台集成仍在实验和端到端验证中，请勿将本仓库与 NVIDIA 官方发行版混淆。

## 核心能力

- **统一 API：** 不必为每个推理引擎分别配置 Agent；集中列出模型并通过同一接口请求文本对话。
- **异构设备路由：** 发现或手动添加节点，根据模型是否存在、节点是否可用和运行负载选择执行目标。
- **多引擎支持：** 复用 Ollama、LM Studio、llama.cpp 等本地推理引擎。
- **本地优先与可控云端回退：** 支持配置云 Provider、指定兜底模型、付费授权和预算约束。
- **Agent 友好：** 可显式指定模型，也支持 `auto`、`auto-fast`、`auto-balanced`、`auto-best` 等模型别名。
- **外部 Strata 推理引擎：** 连接 PC 上独立运行的 [Strata](https://github.com/Niko1221/Strata)，探测其模型并纳入本地路由；ComputeHarbor 不接管 Strata 的进程或模型权重。
- **实验性 Android 节点：** 通过 MNN 在 ARM64 Android 设备运行本地模型，并参与设备间请求调度。

**能力边界：** ComputeHarbor 当前按“一个独立请求对应一个执行目标”进行路由，并不把多台设备的显存合成统一显存，也不把单次推理拆成跨设备张量并行。项目当前不是面向公网的商业 API 中转计费平台。

## 架构概览

```text
AI Agent / 应用 / OpenAI 兼容客户端
                    |
                    v
         ComputeHarbor 统一网关
          127.0.0.1:14326/v1
                    |
              模型能力与策略路由
               /             \
              v               v
       本地设备与推理引擎     云端 Provider
              |            （主动启用）
         +-------------------------+
         | Ollama                  |
         | LM Studio               |
         | llama.cpp               |
         | Strata（外部）*          |
         | Android MNN*            |
         +-------------------------+
          * 实验性集成
```

云 Provider 不作为虚拟局域网节点；本地节点调度与付费云端执行分别处理。

## 项目状态

下表说明的是**当前仓库已有的实现**，不代表所有系统、设备和模型都通过了稳定发行验收。

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| Windows / Linux / macOS 桌面与 Go 服务 | 源码可用 | Electron、节点发现、配对和引擎管理 |
| Ollama / LM Studio / llama.cpp | 源码可用 | 具体可用性取决于系统、驱动和模型 |
| Strata（外部托管的本地引擎） | 实验性集成 | 已有配置界面、模型探测、Proxy Facade 与路由代码；仍需真实 Strata 实例及跨节点端到端验收 |
| OpenAI 兼容统一网关 | 源码可用 | 本机 `/v1/models`、`/v1/chat/completions` |
| 自动模型路由 | 源码可用 | `auto*` 系列及 `auto-local`、`auto-cloud` |
| 云 Provider 与本地优先回退 | 实验性 | 需要配置授权、选定兜底模型和预算；仍需部署级验证 |
| Android ARM64 + MNN | 实验性 | CPU、模型管理、局域网配对与路由；OpenCL 依赖设备和构建配置 |
| 多租户公网网关、账户与计费 | 尚未提供 | 需要额外安全网关、鉴权、限额及运营能力 |
| 多设备显存池 / 跨节点模型切分 | 不支持 | 单次请求不会拆到多台机器 |
| 生图、生视频工作流 | 规划中 | 当前统一网关主要面向文本聊天 |

部分 Android 实机验证记录见 [Android 文档](android/README.md) 和 [测试报告](docs/M12_5_TEST_REPORT.md)，不能据此推断所有设备都能正常运行。

## 快速开始：从源码运行桌面版

需要安装 Git、**Node.js 25.5+**、**Go 1.25+**、npm，并确保 `jq` 在 `PATH` 中。系统相关准备见[构建指南](docs/building.mdx)。仓库部分内部二进制名和历史文档仍沿用 `PAIR`、`nvpair` 命名，暂未整体重构。

```bash
git clone https://github.com/afantastic1/ComputeHarbor.git
cd ComputeHarbor/desktop
npm install
npm start
```

`npm start` 会从相邻的 `services/` 目录编译所需 Go 服务，再启动 Electron 桌面应用。启动后在 UI 中配置 Ollama、LM Studio 或 llama.cpp，准备至少一个可用模型；需要多设备调度时，再配对受信任的节点。

**这属于源码开发运行方式，不是正式签名安装包。** NVIDIA 官方发行版不包含 ComputeHarbor 自行开发的功能，请勿混用安装包。

### 调用统一 API

当运行时和网关已启动：

```bash
curl http://127.0.0.1:14326/v1/models
```

使用明确的模型 ID 或 `auto` 别名发送请求：

```bash
curl http://127.0.0.1:14326/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "auto",
    "messages": [{"role": "user", "content": "你好，ComputeHarbor！"}],
    "stream": false
  }'
```

这要求集群中至少有一个满足要求的运行中模型。启用云功能等场景可能要求网关鉴权，请按实际配置加入 `Authorization: Bearer ...` 请求头；本地回环接口接受的占位 Key **不构成公网安全鉴权**。

目前统一网关主要支持**文本聊天与模型发现**，不能默认认为图片、语音、视频、Embeddings 等 OpenAI 接口都已实现。

### 云端模型（可选）

桌面端代码支持 `local_only`、`prefer_local`、`cloud_only` 策略。使用前需要显式配置云 Provider、模型、凭证、付费授权，以及预算上限。自动回退使用受控的指定云模型，**不是**自动搜索全网最低价 API，更不会无限制地尝试不同付费供应商。

预算预留只是控制风险的机制，不等于按 Token 精确出账。若要对外提供收费服务，需要单独确认供应商的合同与转售权限。

## Strata：外部本地推理引擎（实验性）

[Strata](https://github.com/Niko1221/Strata) 在 ComputeHarbor 中定位为**由用户自行运行和管理的本地推理引擎**，不是云 API，也不是额外的物理节点。对于部署在 PC 上的 Strata 模型，ComputeHarbor 可以将其模型纳入本地可选执行目标，与 Ollama、LM Studio、llama.cpp 一同参与**请求级路由**。这不意味着把多台设备的显存合并，也不保证在特定硬件上运行某个参数规模的模型。

**目前源码中已经包含：** Strata 引擎标识、独立 Proxy Facade、Broker 模型探测与通告、桌面端 **Strata (External)** 设置卡片，以及连接验证和路由单测。**尚待验收：** 真实 Strata 进程的完整推理、流式输出与跨设备调度。ComputeHarbor 不负责安装、启动、升级、停止 Strata，也不负责管理 Strata 模型。

1. 参考 [Strata 上游说明](https://github.com/Niko1221/Strata)，先在同一台 PC 上**独立启动** Strata，确保 OpenAI 兼容服务仅监听本地回环地址。
2. 检查真实模型目录：`curl http://127.0.0.1:8080/v1/models`（端口应以你的 Strata 配置为准）。
3. 在 ComputeHarbor 桌面设置中找到 **Strata (External)**，配置本地 URL（根路径或 `/v1`）、可选的预期模型 ID；需要鉴权时填入的是**密钥环境变量名**，不是明文 API Key。
4. 点击 **Test connection**，开启集成并 **Save Strata settings**。然后通过 ComputeHarbor 的 `/v1/models` 检查模型列表，再使用明确的模型 ID 发起一次网关推理请求。

`GET /v1/models` 连接探测成功，只代表接口可达且模型标识通过校验；不代表生成、工具调用或流式输出已经在你的设备上验证。自动路由前应先测试真实模型与驱动环境。更多边界详见 [Strata 接入计划](docs/PAIR_Strata_Integration_Plan%20(1).md)。

## Android（实验性）

`android/` 目录包含 ARM64 Android 应用及 MNN 推理运行时，**仓库不附带模型权重**。配置、模型格式、Model Hub 限制、实机验收脚本与接口说明参见 [android/README.md](android/README.md)。

已配置好 Android SDK / NDK 后：

```bash
cd android
./gradlew :app:assembleDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug`。默认构建 MNN CPU 版本；OpenCL 需要单独启用并视设备支持情况验证。APK 编译成功不等于设备已经通过原生推理测试。

## 代码结构

```text
desktop/      Electron + React 桌面端
services/     Go Broker、发现、调度、引擎管理与代理
android/      Android UI、后台服务及 MNN 推理
docs/         架构、构建、协议与工程文档
scripts/      开发、测试和检查脚本
```

进一步阅读：[开发指南](docs/developing.mdx)、[构建指南](docs/building.mdx)、[服务端说明](services/readme.md)、[Android 说明](android/README.md)。迁移自上游的部分资料、图片及内部标识仍保留原名称，会逐步整理。

## 路线图

以下内容仅表示项目方向，不是交付承诺：

- [ ] 完成 ComputeHarbor 品牌迁移、文档和独立发行流程。
- [ ] 完善云端回退权限、预算、安全边界与多节点场景的端到端验收。
- [ ] 扩大 Android MNN 设备测试范围，改进硬件加速兼容性。
- [ ] 完成真实 Strata 服务的协议、流式聊天及多节点端到端验收，并校准模型能力后再扩大自动路由范围。
- [ ] 增强模型能力匹配、可观测性和成功请求的实际成本分析。
- [ ] 探索将高显存推理集群作为单一可调度目标。
- [ ] 按需要扩展更多推理后端及图片、视频类工作流。

发现 Bug 或提出建议请使用 [ComputeHarbor Issues](https://github.com/afantastic1/ComputeHarbor/issues)。修改底层通信协议和调度逻辑前，请先阅读现有开发规则并补充测试。

## 安全与部署

ComputeHarbor 目前是**本地／局域网优先**的软件，统一 HTTP 网关默认仅监听回环地址。**不要通过端口映射或无鉴权反向代理把它直接暴露到公网。** 商用公网服务应另加 TLS、真实用户鉴权、租户隔离、权限控制、配额、限流、计量与安全监控。

只与可信设备配对，谨慎处理模型请求与云端密钥。发现安全漏洞时，不要直接在公开 Issue 发布利用细节；可优先使用 GitHub 私密漏洞报告（若启用），否则先请求维护者提供私密沟通方式。仓库当前的 [SECURITY.md](SECURITY.md) 仍含 NVIDIA 官方报告渠道，**不代表 ComputeHarbor 已由 NVIDIA 提供安全支持**。

## 上游项目、归属与许可证

ComputeHarbor 基于 [NVIDIA Personal AI Router](https://github.com/NVIDIA/Personal-AI-Router) 修改和扩展，属于**独立衍生开源项目**，**与 NVIDIA 不存在隶属、赞助或官方背书关系**。

项目继承的原始代码遵循 [Apache License 2.0](LICENSE)，在适用位置保留原作者版权与许可证声明；其他组件的授权信息见 [第三方声明](THIRD_PARTY_NOTICES.md)。推理引擎、模型权重、SDK 与云 API 服务可能还有各自的授权条款。项目更名并不免除这些义务。

NVIDIA 及其相关名称属于各自权利人；本项目仅为说明技术来源而提及，并不暗示获得官方支持。
