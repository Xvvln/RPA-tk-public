# 环境预检 + 运行时打断状态机

> 版本：0.2  
> 日期：2026-07-23  
> 状态：**规格 + Phase A/B 最小实现已落地**（预检事件、恢复日志字段、软 CTA 黑白名单）  
> 关联：[`POPUP_CATALOG.md`](./POPUP_CATALOG.md)、[`WARMUP.md`](./WARMUP.md)、[`RUNTIME_KERNEL_V2.md`](./RUNTIME_KERNEL_V2.md)、[`EXCEPTION_TESTS.md`](./EXCEPTION_TESTS.md)

## 1. 目标与非目标

### 1.1 目标

用行业通用的两层模型，把「各种弹窗 / 异常」做成**可工程化**能力，而不是穷举所有 UI：

| 层 | 名称 | 职责 |
| --- | --- | --- |
| L0 | **Preflight（环境预检）** | 开跑前消掉能消掉的环境问题：本 App 权限、屏幕、网络、TikTok 可启动 |
| L1 | **Interrupt Guard（打断护栏）** | 运行中统一分类：blocker 停 / interruption 有限恢复 / 未知落盘 |

成功标准：

1. **危险不误点**：权限 Allow、验证码、登录、隐私同意、未知系统框 → 永不自动接受  
2. **常见可逆能清**：soft CTA 弹窗、误开评论/sheet、错页 → 有界恢复  
3. **未知可迭代**：出现未知打断 → 自动截图 + UI 树 + code → 人工标类 → 补规则与单测  
4. **业务步骤不各自处理弹窗**：主流程只调「检测 / 恢复 / 继续」，不散落 if

### 1.2 非目标

- 不自动过验证码、不自动登录、不自动点系统权限 Allow  
- 不靠 APK 解包穷尽服务端 A/B 弹窗  
- 不承诺 100% 弹窗自动消失；未知且无 soft CTA 时 **stop + 人工** 是正确行为  
- 不在养号循环里改 Android 系统权限矩阵（那是运维/预配置）

### 1.3 行业对齐（为什么这样定）

成熟手机农场 / RPA 几乎都是：

```text
预置环境  →  全局 dialog watcher  →  白名单 soft-dismiss  →  未知停/上报
```

而不是「每个业务动作自己点弹窗」。本规格把该模型落到本仓库的 Android 引擎与 Python 诊断原型。

---

## 2. 总架构

```text
                    ┌─────────────────────┐
                    │  用户点「开始」      │
                    └──────────┬──────────┘
                               ▼
                 ┌──────────────────────────┐
                 │  L0 Preflight            │
                 │  本 App 权限 / 屏幕 /    │
                 │  网络 / TikTok 安装      │
                 └──────────┬───────────────┘
                            │ pass
                            ▼
                 ┌──────────────────────────┐
                 │  启动 TikTok + 开场恢复  │
                 │  prepareOpeningFeed      │
                 └──────────┬───────────────┘
                            │ READY_TO_WORK
                            ▼
              ┌─────────────────────────────┐
              │  业务步（看 / 赞 / 评 / 搜） │
              └──────────────┬──────────────┘
                             │ 每步前后
                             ▼
              ┌─────────────────────────────┐
              │  L1 Interrupt Guard         │
              │  detect → classify → act    │
              └──────────────┬──────────────┘
                             │
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                 ▼
      CONTINUE           RECOVER            STOP
      执行业务           有界恢复           人工处理
                         后再 detect
```

**铁律：** blockers 优先于 interruptions；任何恢复动作后必须重新 detect；恢复次数有界。

---

## 3. L0 环境预检清单

预检分两类权限，**不要混谈**：

| 主体 | 含义 |
| --- | --- |
| **RPA-TT App** | 无障碍、悬浮窗、通知——本 App 干活必需 |
| **TikTok App** | 通讯录/麦克风/相册等——养号浏览通常不需要；**不在循环里自动点系统框** |

### 3.1 检查项矩阵

| id | 检查项 | 严重度 | 通过条件 | 失败策略 | 现状 |
| --- | --- | --- | --- | --- | --- |
| `P01` | 无障碍服务 | **hard** | `WarmupAccessibilityService` 已连接 | 禁止开始；引导系统设置 | **已实现** `WarmupEngine` 开场 + `ApplicationSettingsActivity` |
| `P02` | 屏幕点亮 | **hard** | `screenInteractive()` | stop `screen_off` | **已实现** |
| `P03` | 未锁屏 | **hard** | `!deviceLocked()` | stop `device_locked` | **已实现** |
| `P04` | 网络（可选） | **hard**（`requireNetwork`） | Android validated network | stop `network_unavailable` | **已实现** |
| `P05` | TikTok 已安装 | **hard** | `launchTikTok()` 成功 | stop `tiktok_not_installed` | **已实现** |
| `P06` | TikTok 前台 | **hard**（有冷启动宽限） | 窗口仲裁确认目标包 | 有界 relaunch；超时 `tiktok_launch_timeout` | **已实现** `waitForTikTokForeground` |
| `P07` | 悬浮窗权限 | **soft** | `Settings.canDrawOverlays` | 警告 `overlay_unavailable`，可继续跑 | **已实现**（warning） |
| `P08` | 本 App 通知权限 | **soft** | POST_NOTIFICATIONS（API 33+） | 警告；前台服务体验变差 | **UI 有**；引擎侧未统一报告 |
| `P09` | 配置合法 | **hard** | `WarmupConfig.validate()` | 禁止开始 | **已实现** |
| `P10` | 开场可回到 feed | **hard** | `prepareOpeningFeed` 成功 | stop `opening_*` | **已实现** |
| `P11` | TikTok 已登录（启发式） | **hard**（建议） | 开场 detect 无 `login_required` | stop，人工登录 | **运行时有**；未单独列为 preflight 报告项 |
| `P12` | TikTok 危险权限预检 | **info** | 列出通讯录/麦克风/相机/位置/相册/通知 | **主界面展示 + 预检日志；不自动改** | **已实现** `TikTokPermissionProbe` + MainActivity 卡片 |
| `P13` | TikTok 版本提示 | **info** | 与 catalog 分析版本一致更佳 | 版本漂移警告，不强制停 | **缺口** |

### 3.2 Preflight 结果模型（建议固化）

```text
PreflightReport
  status: pass | fail | pass_with_warnings
  checks: [{ id, severity, ok, code?, message? }]
  hard_failures: [...]   // 任一则禁止 run
  warnings: [...]
```

事件落盘建议：

- `preflight_started`
- `preflight_check`（逐项或汇总）
- `preflight_failed` / `preflight_passed`
- 现有 `opening_check_started` / `ready_to_work` 保留，语义对齐：preflight ⊆ opening

### 3.3 权限策略（写死）

#### 本 App（RPA-TT）

| 权限 | 策略 |
| --- | --- |
| 无障碍 | 必须；未开 hard fail |
| 悬浮窗 | 强烈建议；缺则 soft warn |
| 通知 | 建议；缺则 soft warn |

#### TikTok（目标 App）

| 场景 | 策略 |
| --- | --- |
| 系统 `permissioncontroller` 对话框 | **blocker `permission_required` → STOP**；不点 Allow / 仅使用时允许 |
| App 内「开启通知」营销询问 | interruption → soft-deny（Not now / 以后再说） |
| 同步通讯录 / 找好友 | interruption → soft-deny |
| 关联邮箱 | interruption → soft-deny |
| 运维预配置（可选，PC/adb） | 需要时用 `pm grant`；**默认不在 App 内做** |

原则：**运行时自动拒绝软询问；系统权限框停机；永久权限矩阵靠人/运维预置。**

### 3.4 Python 诊断侧 preflight

| 命令 | 作用 | 现状 |
| --- | --- | --- |
| `python -m rpa_tt doctor` | 设备、TikTok 安装、分辨率等 | **部分**：`DeviceReport`，无统一 hard/soft 表 |
| `python -m rpa_tt state` | 当前页 + blockers | **已实现** |
| `state --recover` | 可逆恢复 | **已实现** |

建议 doctor 输出与 Android 共用同一 code 词汇（见第 5 节），便于对照。

### 3.5 人做的一次性准备（产品文案可复用）

与 [`WARMUP.md` §3.2](./WARMUP.md) 一致，强化如下：

1. 安装 RPA-TT；打开无障碍 / 悬浮窗 / 通知  
2. **手动**打开 TikTok 并登录；完成任何强制引导  
3. 系统设置中按需拒绝 TikTok 通讯录/电话等（减少运行中权限框）  
4. 固定 TikTok 版本（catalog 当前基线：`46.1.3`）更利于规则稳定  
5. 配置方案后显示悬浮窗，回 TikTok 再开始  

---

## 4. L1 运行时打断状态机

### 4.1 状态

| 状态 | 含义 |
| --- | --- |
| `OBSERVE` | 稳定采样两次 UI（`inspectStable` / `PageStability`） |
| `CLASSIFY` | 产出 `page` + `blockers[]` + `interruptions[]` + confidence |
| `CONTINUE` | 无阻断、无需要恢复的打断 → 执行业务步 |
| `RECOVER` | 存在可恢复 interruption → 执行**一个**恢复动作 |
| `STOP_HUMAN` | blocker 或恢复耗尽 / 未知不可恢复 → 停机、落盘、振动/通知 |
| `WAIT_TRANSIENT` | `UNKNOWN` 且无 blocker（动画/瞬态）→ 短等后重新 OBSERVE |

### 4.2 转移（伪代码）

```text
function guard(expected_page, budget):
  loop:
    state = OBSERVE()                          // 连续两次快照
    if state.blockers not empty:
      dump(blocker)
      return STOP_HUMAN(state.blockers[0])

    if is_transient_unknown(state) and within_deadline:
      WAIT_TRANSIENT
      continue

    if page == expected and not needs_recover(state, expected):
      return CONTINUE

    if auto_recover and recover_count < max_recovery_actions:
      if recover_one(state, expected):         // 只做 1 个动作
        recover_count++
        sleep_jitter()
        continue                               // 必须重新 detect
      else:
        dump(unexpected)
        return STOP_HUMAN("recovery_noop")

    dump(unexpected)
    return STOP_HUMAN("unexpected_page" | "recovery_exhausted")
```

### 4.3 与现有代码的对应

| 规格概念 | Android | Python |
| --- | --- | --- |
| OBSERVE | `WarmupEngine.inspectStable` + `PageStability.consecutive` | `inspect_device_state`（单次；稳定采样较弱） |
| CLASSIFY | `PageAnalyzer.analyze` | `state.detect_ui_state` |
| needs_recover | `hasRecoverableInterruption` | `plan_recovery` 非空 |
| recover_one | `WarmupEngine.recoverOne` | `SafeRecovery._execute(plan[0])` |
| max 次数 | `WarmupConfig.maxRecoveryActions`（默认 3） | `SafeRecovery.recover(max_actions=3)` |
| 开场更宽 | `prepareOpeningFeed`（max≥6，18s） | 无对等「开场宽限」 |
| 中途 ensure | `ensureExpected` | browse 循环内 `SafeRecovery` |
| STOP 落盘 | `captureBlocker` → `blocker-nodes.json` + 截图 | runtime 目录 snapshot（部分） |
| soft CTA | `dismissSoftPopup` | `dismiss_popup` 文案表 |

### 4.4 分类优先级

1. **blockers**（任一存在 → 不生成恢复计划）  
2. **结构化 interruption**（评论误开、错页、input method、options sheet）  
3. **命名 soft popup**（通讯录、好友、邮箱、通知、promo、got_it、interest_survey…）  
4. **generic_dismiss_popup**（仅当存在 soft CTA 文案）  
5. **transient UNKNOWN**（无 blocker → 等待，不乱点）  
6. **真正未知且像对话框、无 soft CTA** → 按 blocker / unexpected 停  

### 4.5 恢复动作优先级（单次只执行队首一类）

与现有 Android `recoverOne` / Python `plan_recovery` 对齐，规范顺序：

| 优先级 | 条件 | 动作 | 禁止 |
| --- | --- | --- | --- |
| 1 | `contacts_sync_prompt` | 点「不允许 / Don't allow / Not now」→ soft 表 → Back | Allow / 同意 |
| 2 | `system_input_method_dialog` / `video_options_sheet` / 评论非目标 | `press_back` | 点列表项 |
| 3 | `notification_banner` | 顶部上滑清掉 → Back | 点进消息 |
| 4 | `got_it_popup` | 我知道了 / Got it → soft 表 | Continue 主流程若不确定 |
| 5 | `interest_survey` | 跳过 / Not now → Back | 乱选兴趣标签 |
| 6 | collections / follow_friends / link_email / notification_prompt / promo / generic | `dismissSoftPopup` | Allow / 开启 / 同意 / Update |
| 7 | `suggestion_page` 且 expected=feed | 上滑跳过 | — |
| 8 | 错页且 expected=feed | 点「首页」或 Back | 深链乱点 |

### 4.6 Soft CTA 白名单 / 主接受黑名单

**白名单（可点）：**

```text
以后再说 | 下次再说 | 稍后再说 | 暂不 | 先不了 | 不用了 | 跳过 | 不允许
Not now | Maybe later | No thanks | No, thanks | Don't allow | Dismiss | Skip | Close | 关闭
我知道了 | 知道了 | Got it
```

**黑名单（永不作为通用关闭键）：**

```text
Allow | Continue | Yes | Update | OK | 好的 | 同意 | 开启 | 允许 | 仅在使用该应用时允许
始终允许 | 更新 | 登录 | 注册 | 继续 | 确定（在未知系统框语境）
```

说明：黑名单用于 **generic / 未知** 路径；命名 interruption 仍只点其专用 soft 文案。

### 4.7 Blocker 表（STOP_HUMAN）

完整语义见 [`POPUP_CATALOG.md`](./POPUP_CATALOG.md)。引擎侧必须保证这些 code **不会**进入 `recoverOne`：

| code | 人工提示要点 |
| --- | --- |
| `verification_required` | 完成验证后点继续 |
| `login_required` | 重新登录 TikTok |
| `rate_limited` | 冷却后再跑，降互动强度 |
| `account_restricted` | 账号风险，停止自动化 |
| `permission_required` | 在系统设置处理权限，勿让脚本点 |
| `privacy_consent_required` | 法定确认，人处理 |
| `age_gate` | 人处理 |
| `update_required` | 更新或锁定版本策略 |
| `network_error` / `network_unavailable` | 恢复网络；不点「重试」除非产品明确允许 |
| `unknown_system_dialog` | 截图人工判断 |
| `tiktok_not_foreground` | 中途切走：停；开场：有限 relaunch |
| `accessibility_disabled` / `disconnected` | 重开无障碍 |
| `screen_off` / `device_locked` | 亮屏解锁 |
| `opening_*` / `unexpected_page` | 看 blocker dump |

`content_unavailable`：可按产品选择 **跳过本条视频** 而非整任务停；若当前实现为 blocker，后续可降级为「video-level skip」（见缺口）。

### 4.8 调用点（业务不得绕过）

Android 引擎应在以下边界调用 guard（已基本具备）：

| 边界 | 方法 |
| --- | --- |
| 开场 | `prepareOpeningFeed` |
| 进入 feed 业务前 | `ensureExpected(FEED)` |
| 单视频互动前 | 现有 blockers 检查 / ensure |
| 搜索提交前后 | 搜索阶段 state 检查 |
| 翻下一条前 | ensure feed |
| 写操作事务前 | 窗口仲裁 + page 契约（RUNTIME_KERNEL_V2） |

禁止：在 `recoverOne` 外再写一套「点掉弹窗」的临时逻辑。

---

## 5. 统一 code 词汇

两端（Android / Python）与文档共用同一组字符串 code（已大部分对齐）：

```text
# blockers
verification_required, login_required, rate_limited, account_restricted,
permission_required, privacy_consent_required, age_gate, update_required,
network_error, network_unavailable, unknown_system_dialog,
tiktok_not_foreground, content_unavailable,
accessibility_disabled, accessibility_disconnected,
screen_off, device_locked, tiktok_not_installed, tiktok_launch_timeout,
opening_page_unresolved, opening_stability_timeout, unexpected_page

# interruptions
generic_dismiss_popup, got_it_popup, contacts_sync_prompt, follow_friends_popup,
collections_popup, link_email_popup, notification_prompt, promo_banner,
notification_banner, suggestion_page, interest_survey, video_options_sheet,
system_input_method_dialog, comments_open, inbox_page, foreground_uncertain
```

新增 code 必须：catalog 一行 + analyzer 标记 + recover 策略（或明确 stop）+ 合成单测。

---

## 6. 未知弹窗捕获闭环

### 6.1 触发

- 任意 `STOP_HUMAN`
- `recovery_exhausted` / `recovery_noop`
- `page == UNKNOWN` 超过 deadline
- （建议）连续 N 次 soft dismiss 后状态不变

### 6.2 落盘最小集

| 文件 | 内容 |
| --- | --- |
| `blocker-nodes.json` / snapshot JSON | 节点树 + 窗口仲裁证据 |
| `blocker-screen.png` | 截图 |
| 事件日志 | code、expected_page、recover_count、前后 page |

Android：`captureBlocker` **已实现**。  
建议：Python 阻断路径同样强制落盘到 `runtime/`。

### 6.3 人工分拣流程

```text
1. 打开截图 + 节点 JSON
2. 标类：auto_soft | auto_nav | stop | ignore
3. 抽 markers（text / content-desc / package / 结构）
4. 补 PageAnalyzer / state.py
5. 补 recoverOne / plan_recovery（若 auto）
6. 合成单测 + 可选真机回归
7. 更新 POPUP_CATALOG 一行
```

### 6.4 KPI（建议）

| 指标 | 含义 |
| --- | --- |
| 未知打断率 | STOP 中 `unknown*` / `unexpected_page` 占比 |
| 误点主接受次数 | 必须为 0（靠黑名单 + 单测） |
| 可恢复打断成功率 | RECOVER 后回到 expected 的比例 |
| dump → 规则落地周期 | 捕获到合入的时间 |

---

## 7. 测试策略（能测什么）

详见 [`EXCEPTION_TESTS.md`](./EXCEPTION_TESTS.md)。本规格补充分层：

| 层 | 手段 | 验证内容 |
| --- | --- | --- |
| L0 单测 | 合成 UI 树 | 识别 code、分级、不点黑名单 |
| L1 单测 | 假 gateway | recover 计划顺序、有界次数、blocker 无 plan |
| L2 dump 回放 | 历史 `blocker-nodes.json` | 回归不漂 |
| L3 真机可逆 | 切后台、开评论、长按 sheet、断网 | 端到端恢复/停机 |
| L4 真机危险 | **不做主动制造** | 合成覆盖识别即可 |

**解包字符串**只用于扩充 CTA/markers 语料，不充当 E2E。

---

## 8. 缺口与实现分期

### 8.1 已具备（保持）

- 开场 hard 检查：无障碍、亮屏、锁屏、网络、拉起 TT、开场 feed 恢复  
- `PageAnalyzer` / `state.py` 大量 blocker & interruption markers  
- `recoverOne` / `SafeRecovery` soft-dismiss 与错页导航  
- `maxRecoveryActions`、稳定采样、blocker 落盘  
- 设置页本 App 权限引导  
- `POPUP_CATALOG` 策略表  

### 8.2 建议实现分期

#### Phase A — 规格落地与可观测（优先，改动小）✅

1. 新增本文件为权威打断规格；`POPUP_CATALOG` / `WARMUP` 交叉引用  
2. 开场检查输出结构化 `PreflightReport` 事件（`preflight_started` / `preflight_passed|failed` + P06/P10 补充）  
3. 统一 recovery 事件字段：`interruption_code`、`action`、`attempt`、`expected`  
4. Python `doctor` 输出 `PreflightReport` schema（hard/soft + device）  

#### Phase B — 护栏收紧（部分完成）

1. ✅ 抽出共享常量：`InterruptPolicy`（Java）/ `interrupt_policy.py`（Python）  
2. ✅ `dismissSoftPopup` / `filter_soft_labels` 过滤主接受黑名单  
3. `content_unavailable` 评估降级为「跳过本条」而非整任务 blocker  
4. 中途 `tiktok_not_foreground` 与开场 relaunch 策略文档化并单测  

#### Phase C — 预检增强

1. Preflight 报告 TikTok 版本 vs catalog 基线（info）  
2. 可选：列出 TikTok 危险权限 granted 状态（info only）  
3. 主界面「开始」前用同一 Preflight，与引擎双重保险  

#### Phase D — 捕获与回归

1. dump 目录约定 + 回放测试脚手架（Android `ResolverReplayAudit` 扩展 / Python）  
2. catalog 全表参数化单测矩阵（缺行补行）  
3. 未知打断率统计进 summary  

### 8.3 明确不在近期做

- 自动点击系统权限 Allow  
- 自动打码 / 自动登录  
- 云端弹窗模型下发  
- 多机协同人工工单队列（单机产品可后续再加「历史里查看 dump」）

---

## 9. 对照改动点清单（给实现用）

| 文件 | 改动意图 |
| --- | --- |
| `android-app/.../WarmupEngine.java` | 开场包装 `runPreflight`；recovery 日志带 interruption code；可选 blacklist 校验 |
| `android-app/.../PageAnalyzer.java` | 新 markers 只从 dump 闭环进入；保持 blockers 优先 |
| `android-app/.../PageState.java` / `PageStability.java` | 一般不动；瞬态 UNKNOWN 语义保持 |
| `android-app/.../ApplicationSettingsActivity.java` | 展示 preflight 结果或 TikTok 权限 info（Phase C） |
| `android-app/.../WarmupConfig.java` | 已有 `autoRecover` / `maxRecoveryActions` / `stopOnBlockers` / `requireNetwork` |
| `src/rpa_tt/state.py` | 与 Android code 对齐；黑白名单常量 |
| `src/rpa_tt/recovery.py` | 与 `recoverOne` 优先级对齐；禁止主接受 |
| `src/rpa_tt/device.py` + `cli.py` | doctor → PreflightReport |
| `tests/test_state.py` / `PageAnalyzerTest.java` | catalog 矩阵补全 |
| `docs/POPUP_CATALOG.md` | 链到本文件「状态机」 |
| `docs/WARMUP.md` §3.2–3.3 | 链到 Preflight 表 |

---

## 10. 验收标准

规格视为落地完成（Phase A+B 最小集）当且仅当：

1. 文档：本文件 + catalog/warmup 交叉引用齐全  
2. 行为：任意 blocker 路径零 soft-accept 点击（单测锁定黑名单）  
3. 行为：catalog 中 auto 类 interruption 均有合成识别测例  
4. 行为：恢复动作后必重新 detect；超过 `maxRecoveryActions` 必 stop + dump  
5. 可观测：一次失败 run 的目录内必有 blocker 节点与截图（Android 已满足）  
6. 权限：系统权限框只 stop，不自动 Allow（单测 + 代码审查）

---

## 11. 一句话

> **预检把环境问题挡在门外；打断护栏只自动处理可逆干扰；其余停机落盘等人。**  
> 弹窗系统的产品是「安全与可迭代」，不是「全能点掉一切」。
