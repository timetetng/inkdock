# 架构（Architecture）

> InkDock / 墨台 · P0 文档
> 本文给出**建议架构**，并严格标注 **已验证（本机只读实测）** 与 **待验证（推断/设计）** 的界线。
> 公开版不含真实服务名、路径、地址、端口与环境变量。

---

## 1. 分层架构

```
┌──────────────────────────────────────────────┐
│  InkDock App (Kotlin / Compose / Material 3)  │  非特权 UI 进程
│  概览 · 服务 · 网络 · 文件 · (终端)             │
└───────────────▲──────────────────────────────┘
                │  类型化请求 / JSON 响应（本地、按需）
┌───────────────┴──────────────────────────────┐
│  root bridge（本地特权助手，按需启动）          │  仅 argv，不拼 shell
│  - 固定操作白名单                              │
│  - 超时 / 取消 / 结构化错误                     │
└───────────────▲──────────────────────────────┘
                │
   ┌────────────┴─────────────┐
   │ ① 宿主侧：Droidspaces CLI │  show/info/check/pid/start/stop/restart/enter/run
   └────────────┬─────────────┘
                │ ② `run` 进入运行时（Droidspace 容器）
   ┌────────────┴───────────────────────────────┐
   │  运行时内部：Docker Engine / Compose /       │
   │  systemd+journald / mihomo / 文件系统        │
   └────────────────────────────────────────────┘
```

**要点**

- App 永远不直接持有 root；只有 bridge 是特权组件。
- bridge 是**唯一**的特权入口，也是唯一的命令构造点。
- 宿主侧与运行时侧是**两个不同的执行域**：宿主直接调 Droidspaces CLI；
  运行时内部能力（Docker / systemd / mihomo / 容器文件）必须经 `run` 进去执行。

---

## 2. 已验证 / 待验证界线

### 2.1 已验证（本机只读实测，2026-10-18）

| 能力 | 结论 | 证据类型 |
| --- | --- | --- |
| Droidspaces CLI 版本 | 存在且版本为 7.0.0 | `droidspaces version` |
| CLI 子命令 | `start/stop/restart/enter/run/info/pid/show/scan/check/docs/version/daemon` | `--help` 输出 |
| **JSON 输出** | `--format` 支持 **`show` / `info` / `check`** 三个命令 | `--help` + 实跑返回合法 JSON |
| `check` 结构化能力位 | 返回布尔能力表（root/ns/loop/cgroup2/overlayfs…） | `--format check` 实跑 |
| `show` / `info` 结构化字段 | 名称/OS/主机名/IP/资源/NAT/port_forwards/bind_mount_count/env_var_count 等 | `--format show` / `--format info` |
| 进入运行时 | `enter [user]` 与 `run -- <cmd>` 均存在；`run` 实跑成功 | `--help` + `run /bin/true` |
| 配置字段 | `container.config` 为 `key=value`，含网络/挂载/端口/自启等字段 | 实读配置 |
| 端口机制 | `port_forwards=<Android端口>:<运行时端口>/<proto>`；另有 NAT 规则状态文件 | 实读配置 + `Net/pf_*.state` |
| 两级转发 | Docker 发布 → 运行时端口 → Android 转发 | 配置 + Docker 端口 + NAT 规则交叉核对 |
| 守护进程 | 守护进程可达；有启动模式标记与 pid 文件 | 实跑命令 + 实读标记/pid |
| 自启动 | 模块脚本按 `run_at_boot` + 优先级 + 网关依赖顺序启动容器 | 实读模块脚本 + boot 日志摘要 |
| Docker 客户端/引擎 | 客户端与引擎同为 29.7.2，API 1.55（最低 1.40） | `docker version` |
| containerd / runc | containerd 2.3.5 / runc 1.5.1 | `docker version` |
| Docker socket | unix socket，`0660 root:docker` | `stat` |
| Compose | Compose 版本存在，`docker compose ls` 可用 | `docker compose version` / `ls` |
| Compose 发现 | `com.docker.compose.project` / `.service` / `.project.config_files` 标签可靠；非 compose 容器标签为空 | `docker ps` 标签 |
| systemd / journald | 运行时内 PID1 为 systemd（261），journald 可用、有磁盘占用 | `systemctl`/`journalctl` |
| 监听元数据 | 可读本地监听表（`ss`），可读进程表（`ps`） | 只读命令 |
| 文件元数据 | 可读大小/权限/属主/类型 | `stat` |
| 代理内核 | controller 绑定回环地址；**无 secret 行**；另有周期守卫脚本会改写模式 | 实读配置键（不读内容）+ 读守卫脚本 |

### 2.2 待验证（不得写入“已支持”）

| 项 | 为什么待验证 | 计划 |
| --- | --- | --- |
| 宿主侧 `check` 能力位 | 本机实测是在**运行时内部**跑的，宿主值可能不同 | 宿主 root 通道对比 |
| `pid` 的语义 | 在运行时内返回的是**命名空间内 PID**，与宿主 PID 不同 | 宿主侧对照 |
| 非 JSON 命令的文本稳定性 | `start/stop/enter/run/pid/scan` 仅文本输出 | 多版本观察；能 JSON 就不用文本 |
| `--format show` 计数语义 | `total` 与 `running` 计数不一致、`uptime_sec=0` 疑似缺陷 | 采样对比 + `/proc` 兜底 |
| Android 侧真实监听 | 只能看到配置与 NAT 规则，未必等于实际 LISTEN | 宿主侧受控核对 |
| 端口/挂载写入热生效 | 涉及 iptables/挂载，风险高 | P3 受控实验 + 回滚预案 |
| 守卫是否覆盖人工切换 | 需一次写入才能证实；当前为脚本+计时器的强推断 | P3 专门验证 |

---

## 3. root bridge 生命周期与威胁模型

### 3.1 生命周期

1. **冷启动**：App 启动不拉起任何特权常驻；bridge 处于“未连接”。
2. **按需拉起**：用户进入需权限的页面/执行操作时，App 通过 `su`（KernelSU-Next 域）启动 bridge。
3. **连接边界**：stdio 由 App 启动的 su 子进程继承，不伪称可从 JSON 握手验证调用 UID。若未来使用 socket，必须以系统提供的 peer credentials 验证调用方，不能信任请求自报 UID。
4. **请求/响应**：类型化 JSON；bridge 校验并映射到**固定操作白名单**，以 argv 执行。
5. **超时/取消**：每个请求带 deadline；取消时终止子进程树，回收资源。
6. **销毁**：空闲超时即退出；不驻留、不监听、不写常驻状态。

### 3.2 推荐实现形态

- **优先：无监听 socket 的“按需 spawn + stdin/stdout JSON”**。
  App 用 `su -c <固定 helper 路径>` 启动 helper，把请求写 stdin；helper 不监听任何端口。
  这是最小的攻击面。
- **备选：本地 Unix domain socket**（仅当需要流式日志时）。
  socket 位于 App 私有目录（0700），抽象命名不暴露到网络；每会话 nonce；空闲超时关闭。
- **禁止**：Docker TCP、无认证 root HTTP、任何 0.0.0.0 监听。
- P1 当前实现是固定 su 命令集合与严格容器 ID 校验，不是已经实现了上文 JSON helper；见 IMPLEMENTATION-STATUS.md。
- 服务扩展契约以 [extensions.md](extensions.md) 为准。Compose 标签仅是发现线索，不足以证明可安全重建项目。

### 3.3 威胁模型

| 资产 | 威胁 | 缓解 |
| --- | --- | --- |
| root 权限 | 任意命令注入 | 固定白名单；argv 传递；**绝不 `sh -c` 拼接用户输入** |
| 本地 socket/helper | 同机其它 App 冒充 | 校验调用方 UID/包名；每会话 nonce；socket 0700 |
| 代理 controller | 同机进程可能访问无鉴权回环接口 | 回环不是鉴权；bridge 不转发端口；接入前另行决定凭据加固，不擅自改现有配置 |
| 日志 | 正文含密钥/环境变量/命令行 | 默认不展示可能含密钥的正文；字段白名单；限长 |
| 配置/凭据 | 落盘或入库泄漏 | 不入公开仓库；导出脱敏；`private/` gitignore |
| 网络写入 | iptables/挂载错误导致系统不可用 | L3 预览+确认+备份+回滚；禁止在 PID1 mount ns 内挂载 |
| 供应链 | 依赖/构建污染 | 只 GitHub Actions 构建；不装 SDK/NDK 到设备 |

---

## 4. CLI / API 选择

### 4.1 Droidspaces（宿主侧）

- **首选 `--format` JSON**：`show` / `info` / `check`。
- 其余命令（`pid/start/stop/restart/enter/run/scan/version`）只有文本：
  - 能 JSON 就不解析文本；
  - 必须解析时，用宽松正则 + 版本指纹 + 失败即降级（不猜）；
  - `pid` 注意**命名空间相对性**。
- 运行时内执行：`droidspaces --name=<容器> run -- <argv...>`（不经 shell）。

### 4.2 Docker（运行时侧）

- 数据面优先 **Docker Engine HTTP API**（unix socket），天然结构化 + 支持流式日志；
  最低 API 1.40、服务端 1.55 已验证。CLI 作为兜底。
- 本 App 无法直接访问运行时内的 socket（它在运行时网络/文件命名空间内），
  必须经 bridge → `run` 在运行时内执行。

### 4.3 Compose

- 项目列表：`docker compose ls`。
- 归属判定：优先 Docker labels（已验证可靠），而非猜目录。
- 配置文件来源：`com.docker.compose.project.config_files`。

### 4.4 systemd / journal

- systemd：优先 D-Bus（`org.freedesktop.systemd1`，`systemctl show -p ...` 的 JSON-ish 或属性接口）；
  CLI 文本兜底。
- journal：优先结构化输出并**只取白名单字段**；默认不下发正文。

---

## 5. 两级端口模型

```
Docker 容器端口  --(docker publish / -p)-->  运行时端口
运行时端口        --(Droidspaces port_forwards)-->  Android 端口
```

- **配置意图**：`port_forwards` 字符串（`--format info` 可读）。
- **内核规则**：NAT 规则状态文件（可读，逐条 `addrtype/local_out/local_post`）。
- **实际监听**：运行时内用监听表；Android 侧待验证。
- 网络页必须**同时展示三者**并标注来源，避免把“规则存在”当成“服务在听”。

---

## 6. 流式日志边界

- **双上限**：行数上限 + 字节上限，先到先停。
- **时间窗**：默认最近 N 分钟；可扩窗但显式提示成本。
- **背压**：UI 消费慢时丢弃旧行并提示“已丢弃”，不无限缓冲。
- **脱敏**：默认字段白名单；**不展示**进程命令行、环境变量、可能含 token/secret 的正文。
- **取消**：离开页面即断开流，回收子进程。
- **来源标注**：Docker / journal / 模块日志分别标注，不混流。

---

## 7. 文件分享与缓存治理

- **只读打开优先**：`FileProvider`（`FLAG_GRANT_READ_URI_PERMISSION`），不复制大文件到公共目录。
- **写回滞后**：P2 之后再考虑 SAF/编辑回写。
- **范围区分**：Android 存储（App 可直接访问）与运行时内文件系统（经 `run`）要明确区分。
- **缓存**：私有 cache 目录设 TTL 与总大小上限；导出后清理；缓存中不得含凭据。
- **命名空间安全**：所有路径来自用户输入时，做规范化与根目录约束，防目录穿越。

---

## 8. 监控采样策略

- **前台**：概览页 1–5s 采样；离开前台降到 0 或极低频。
- **数据源**：
  - 轻量：`/proc/loadavg`、`/proc/meminfo`/cgroup、热区；
  - 容器级：`--format show`（注意 `uptime_sec=0` 缺陷，交叉校验）。
- **节流**：合并刷新、避免忙轮询；尊重省电/热限。
- **一致性**：采样值标注时间戳与来源；异常值（0/负/突变）不直接展示为事实。

---

## 9. 与现有守卫脚本的协同（代理）

- 现状（已验证）：controller 在回环、**无 secret**；存在周期性守卫，会依据“可用节点数 / 人工标记”改写 mode。
- 影响（强推断，待真实验证）：GUI 直接改 mode 可能在守卫周期内被覆盖。
- 设计对策：
  1. 代理页明确显示“存在守卫，可能覆盖”的提示；
  2. 切换前读取并展示守卫判据（节点数阈值、人工标记）；
  3. 提供“暂停/覆盖守卫”的显式、可回滚操作（P3，高风险，需确认）；
  4. 绝不显示 secret/订阅/节点/目的地址。
