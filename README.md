# RPA-TT

面向 Android 真机的 TikTok 日常浏览 RPA。仓库现在包含两层实现：

- [`android-app/`](android-app/)：按照 [`docs/WARMUP.md`](docs/WARMUP.md) 实现的
  Android 本机 App、无障碍服务、悬浮窗、模块化方案和固定状态机；无服务端。
- `src/rpa_tt/`：早期 Python +
  [`uiautomator2`](https://github.com/openatx/uiautomator2) 原型，继续用于选择器验证、
  页面诊断和开发期真机探测。

正式运行入口以 Android App 为准。下面的命令是 Python 诊断原型的使用方式。

Python 原型提供以下基础命令：

- `doctor`：检查 ADB 设备、Android 版本和 TikTok 安装状态。
- `launch`：启动国际版 TikTok，不执行页面互动。
- `state`：只读识别当前页面、内容类型、异常、警告及可执行能力。
- `snapshot`：保存当前屏幕截图及 UI 层级，用于开发页面定位器。
- `search`：提交一个搜索关键词，保存搜索状态、截图及 UI 层级。
- `browse`：读取视频作者和简介、打开并读取评论、滑到下一条；可按概率预演或提交点赞、收藏和评论。

## 环境

项目使用 Conda 环境 `rpa-tt`：

```bash
conda activate rpa-tt
python -m pip install -e .
```

## 使用

```bash
python -m rpa_tt doctor
python -m rpa_tt launch
python -m rpa_tt state
python -m rpa_tt snapshot
python -m rpa_tt search GPT
python -m rpa_tt browse --count 1 --watch-min 5 --watch-max 8
```

搜索结果中的 `status` 有三种：`results` 表示结果页已加载，`empty` 表示 TikTok
明确返回空结果，`unknown` 表示页面状态无法确认。空结果不会被误报成命令失败。

`state` 会区分推荐、评论、搜索、商城、个人页、设置和未知页面，并检测 Android
有效网络、验证码、登录失效、页面网络错误、频率限制、内容不可用、权限弹窗、强制
更新及年龄确认。即使 TikTok 仍显示缓存视频，Android 没有有效物理网络时也会停止。
它只读取状态，不点击页面；浏览任务遇到阻断状态时会记录原因并立即停止。

状态检测还会识别共享收藏、关注朋友、关联邮箱、推广横幅、消息横幅、推荐插页、
意外评论页、收件箱、商城、个人页、搜索页、输入法选择框及长按视频底部菜单。浏览
任务默认只恢复已知且可逆的干扰，最多执行 3 次动作且每次动作后重新检测。只读 UI
层级获取瞬断时会重启 UIAutomator 并重试，但不会重复点击或重复提交：

```bash
# 只读查看检测结果和建议动作
python -m rpa_tt state

# 显式执行安全恢复，目标是回到推荐页
python -m rpa_tt state --expected-page feed --recover

# 浏览时关闭自动恢复
python -m rpa_tt browse --count 1 --no-auto-recover
```

隐私确认、验证码、年龄、权限和未知系统弹窗不会自动点击，而是停止并等待人工处理。

评论状态拆分为 `comment_entry_available`（评论入口）、
`inline_comment_composer`（视频底部可选的快捷评论框）和
`comments_panel_open`（完整评论弹层）。没有快捷评论框不代表该视频不能评论。

`browse` 默认不会执行“不感兴趣”。传入兴趣关键词后，不匹配的视频只会在日志中
记录为 `would_mark_not_interested`：

```bash
python -m rpa_tt browse --count 3 \
  --watch-min 7 --watch-max 15 \
  --comment-min 2 --comment-max 6 \
  --interest-keyword technology \
  --interest-keyword programming
```

检查预演结果无误后，只有显式增加 `--apply-not-interested` 才会真的提交反馈。

点赞、收藏和评论默认概率均为 `0`，即完全不触发。下面先运行预演，日志会写入
`would_like`、`would_favorite` 和 `would_comment`，不会在 TikTok 上提交：

```bash
python -m rpa_tt browse --count 3 \
  --like-rate 0.5 --favorite-rate 0.3 --comment-rate 0.2 \
  --comment-text "这里填写你准备公开发送的评论"
```

确认日志和评论文案后，增加 `--apply-interactions` 才会真正执行。单次会话默认最多
点赞 3 条、收藏 2 条、评论 1 条，可用 `--max-likes`、`--max-favorites`、
`--max-comments` 下调。代码不会自动生成评论，也不会在发送结果不明确时重试。

浏览手势由多段触点组成，单次手势内部包含加速或减速，并会在快速 flick、普通
swipe 和慢速 drag 之间选择。评论区默认最多展开一个可见的二级回复入口，可通过
`--reply-threads` 调整；设为 `0` 时不展开。

多台设备同时连接时，通过 `--serial` 指定设备：

```bash
python -m rpa_tt --serial <device-serial> doctor
```

运行产物写入 `runtime/`，该目录不应提交到版本控制。

真机及合成异常验证记录见 [`docs/EXCEPTION_TESTS.md`](docs/EXCEPTION_TESTS.md)。
