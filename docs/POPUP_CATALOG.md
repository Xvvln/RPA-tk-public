# TikTok 弹窗目录与自动处理策略

- 包名：`com.zhiliaoapp.musically`
- 分析版本：`46.1.3`（`versionCode=2024601030`）
- 抽取来源：真机安装包 `base.apk` + `split_config.zh.apk` + `split_config.en.apk`
- 工件目录：`临时文件/tk-unpack-20260723/`
- 实现位置：
  - Android：`PageAnalyzer` 识别 + `WarmupEngine.recoverOne` 恢复
  - Python：`state.py` 识别 + `recovery.py` 恢复
- **工程规格（环境预检 + 运行时打断状态机）**：[INTERRUPT_AND_PREFLIGHT.md](./INTERRUPT_AND_PREFLIGHT.md)
- **养号权限策略（解包固化）**：[WARMUP_PERMISSION_POLICY.md](./WARMUP_PERMISSION_POLICY.md)

## 重要限制（必须先读）

1. **无法靠解包穷尽全部弹窗**  
   TikTok 大量运营/A-B 文案是服务端下发，不在 APK 字符串表里。静态分析只能覆盖内置资源 + 已知运行时文案。
2. **运行时 UI 树才是最终依据**  
   资源 id 会变；自动化以可见节点 `text` / `content-desc` / 结构契约匹配。
3. **高风险弹窗禁止自动点**  
   验证码、登录、限流、封号、年龄门、隐私同意、系统权限、未知系统对话框 → **停止任务并人工处理**。
4. **只自动点“软拒绝/稍后”类 CTA**  
   绝不自动点 `Allow` / `Continue` / `同意` / `开启` 等主接受按钮。

## 从 APK 挖到的安全关闭 CTA（短标签）

以下出现在 46.1.3 字符串资源中，适合作为 soft-dismiss 候选：

| 中文 | 英文 |
| --- | --- |
| 以后再说 | Not now |
| 稍后再说 | Maybe later |
| 跳过 | Skip |
| 关闭 | Close / Dismiss |
| 取消 | Cancel |
| 知道了 / 我知道了 | Got it |

**不要**把 `Allow` / `Continue` / `Yes` / `Update` / `OK` / `好的` 当通用关闭键（容易误接受权限/更新）。

## 自动处理总表

| code | 类别 | 策略 | 识别要点 | 自动动作 |
| --- | --- | --- | --- | --- |
| `generic_dismiss_popup` | interruption | auto | 以后再说 / 暂不 / Not now / Maybe later / No thanks / Dismiss | 点 soft CTA → 关 → Back |
| `got_it_popup` | interruption | auto | 我知道了 / 知道了 / Got it | 点确认/关闭 |
| `contacts_sync_prompt` | interruption | auto | 同步通讯录 / access your contacts | 不允许 / Not now |
| `follow_friends_popup` | interruption | auto | 关注你的朋友 / find contacts | soft CTA |
| `collections_popup` | interruption | auto | 共享收藏 / shared collections | soft CTA |
| `link_email_popup` | interruption | auto | 关联邮箱 / link email | soft CTA |
| `notification_prompt` | interruption | auto | 开启通知 / turn on notifications | soft CTA（拒绝） |
| `promo_banner` | interruption | auto | streak 宠物 / 新功能 | soft CTA / 关闭 |
| `notification_banner` | interruption | auto | 给你发来了新消息 | 上滑清掉 / Back |
| `suggestion_page` | interruption | auto | 上滑跳过 | 上滑 |
| `interest_survey` | interruption | auto | 选择你感兴趣 / 内容偏好 | 跳过 / Back |
| `video_options_sheet` | interruption | auto | Bottom sheet + 下载/举报/速度/不感兴趣 | Back |
| `system_input_method_dialog` | interruption | auto | 选择输入法 | Back |
| `comments_open` | interruption | auto（非目标页时） | 评论面板契约 | 关评论 / Back |
| 错页 inbox/shop/profile/search/settings | interruption | auto（目标为 feed） | 页分类 | 首页 / Back |
| `verification_required` | blocker | **stop** | 验证 / captcha / 滑块 | 人工 |
| `login_required` | blocker | **stop** | 登录以继续 | 人工 |
| `rate_limited` | blocker | **stop** | 操作过于频繁 | 人工 |
| `account_restricted` | blocker | **stop** | 封禁/限制 | 人工 |
| `permission_required` | blocker | **stop** | 系统权限控制器 | 人工 |
| `privacy_consent_required` | blocker | **stop** | EEA 数据传输等 | 人工 |
| `age_gate` | blocker | **stop** | 年龄/生日 | 人工 |
| `update_required` | blocker | **stop** | 强制更新 | 人工 |
| `network_error` / `network_unavailable` | blocker | **stop** | 无网/加载失败 | 恢复网络后重试 |
| `unknown_system_dialog` | blocker | **stop** | android parentPanel 未知框 | 人工 |
| `tiktok_not_foreground` | blocker | **stop**（冷启动有限恢复） | 前台非 TT | 回前台/人工 |

## 实现原则

1. **先分类再动作**：blockers 优先于 interruptions；blockers 不生成自动恢复计划。
2. **稳定采样**：关键恢复前后用连续快照，避免动画中误判。
3. **有界恢复**：`maxRecoveryActions` 限制次数，失败则落盘 `blocker-nodes.json` + 截图。
4. **新弹窗接入流程**：
   - 真机抓 UI 树 → 归类 auto / stop
   - 补 `detect_markers` 与 soft CTA
   - 加单测（合成层级）
   - 再上真机回归

## 解包命令备忘

```bash
ADB=.../platform-tools/adb
AAPT=.../build-tools/35.0.0/aapt
PKG=com.zhiliaoapp.musically

$ADB shell pm path $PKG
$ADB pull <base.apk> ./base.apk
$AAPT dump strings ./base.apk | rg 'Not now|Maybe later|以后再说|Got it'
$AAPT dump --values resources ./split_config.zh.apk > zh-resources.txt
```

## 现实预期

- **可自动处理**：软营销弹窗、好友/邮箱/通知软询问、底部工作表误开、推荐插页、评论/错页偏离。
- **不可/不应自动处理**：风控验证、登录失效、权限与隐私法定确认、封号限流。
- 服务端新文案出现时，若带 soft CTA（以后再说/Not now 等），会落入 `generic_dismiss_popup`；若只有主接受按钮，会变成 `unknown`/人工路径，而不是乱点。
