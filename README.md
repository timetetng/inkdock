# InkDock / 墨台

> 完全个性化、**只服务这一台已 root Android 手机**的 Droidspaces / 服务运维面板。

InkDock（墨台）不是通用运维软件，也不打算服务第二台设备。它的唯一职责是：在这台手机上，
用最短的路径看清并操作本机的 Droidspaces 容器、其中的服务、端口、文件，以及已有的网络代理内核。

---

## 当前状态

- **P0：文档 + 只读兼容性验证**。
- 已有 P1 首批 Android 源码，待 GitHub Actions 编译与真机验收；不宣称已可用。实现状态见 `docs/IMPLEMENTATION-STATUS.md`。
- 现有能力由 `droidspaces --format ...`、Docker CLI、systemd/journal、已有代理守卫脚本等外部组件提供；
  App 只是它们的**本地前端**，不复制它们的能力。
- 阶段路线与验收标准见 [`docs/roadmap.md`](docs/roadmap.md)。

## 定位

- **单机 / 单用户 / 本地**：没有账号系统、没有远端主机、没有多机发现、没有插件市场。
- **原生 Kotlin + Jetpack Compose + Material 3**：墨绿主色、现代圆角、克制的轻量动画、深/浅色。
- **四个首期信息域**：概览、服务、网络、文件；终端**后置且非硬需求**，不占用首期主导航。
- **本地 root bridge**：按需连接、参数安全传递、超时/取消/结构化错误；
  不开放 Docker TCP，不开放无认证的 root HTTP 服务。

## 非目标（明确的“不做”）

- 不做远程主机管理、多机发现、批量下发。
- 不做账号 / 云同步 / 团队协作。
- 支持受控声明式服务扩展，详见 `docs/extensions.md`；不做插件市场和任意代码热加载。
- 不实现 Android VPN；代理只接**已有内核**，不接管系统 VPN。
- 不开放 Docker TCP 或无认证的 root HTTP 端口。
- 不承诺未经本机验证的“热生效”（改端口 / 改挂载 / 改转发一律先预览+确认+备份+回滚）。
- 不把本机诊断、凭据、日志、真实配置提交到公开仓库。

## 设计原则

1. **只读优先**：P0/P1 先做只读观测闭环，写入能力逐级放开。
2. **危险操作分级**：见 [`docs/requirements.md`](docs/requirements.md) 的 L0–L3 分级。
3. **不拼 shell**：App 用户输入永远以参数（argv）传递，绝不拼接进 `sh -c`。
4. **凭据不出设备**：密钥、订阅、token 不落公开仓库、不出现在界面导出里。
5. **可解释**：每个状态展示都要能说清它来自哪个命令 / 哪个字段，避免“记忆当现场”。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [`docs/requirements.md`](docs/requirements.md) | 已确认需求、待决策、页面与交互、危险操作分级、阶段验收标准 |
| [`docs/architecture.md`](docs/architecture.md) | 建议架构、已验证/待验证界线、root bridge 生命周期与威胁模型、CLI/API 选择、流式日志、文件与缓存、采样策略 |
| [`docs/roadmap.md`](docs/roadmap.md) | P0 只读兼容性 → P1 Docker 闭环 → P2 systemd/Compose/文件 → P3 网络/代理 → P4 打磨 |
| [`docs/compatibility.md`](docs/compatibility.md) | **脱敏**能力表（不含真实服务名/路径/地址/端口/配置） |
| [`docs/privacy.md`](docs/privacy.md) | 公开仓库的隐私保护规则 |
| `private/compatibility.md` | 本机只读验证记录（真实命令与证据），**已被 `.gitignore` 排除** |

## 构建

- **只通过 GitHub Actions 构建**。
- 设备与 Droidspaces 容器内**绝不安装** Android SDK / Gradle / NDK。
- P0 阶段不产生任何构建产物。

## 隐私

本仓库允许公开，但公开内容**完全脱敏**；一切真实诊断只进 `private/`，且 `private/` 永不入库。
规则与自检清单见 [`docs/privacy.md`](docs/privacy.md)。
