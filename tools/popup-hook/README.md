# Popup hook lab (解包 → 候选函数 → Frida 激活 → adb 采 UI 树)

面向 **TikTok 46.1.3**（`临时文件/tk-unpack-20260723/base.apk`）的实验工具，**不是**养号主路径。

## 已从解包/反编译得到的候选

见 [`candidates/from_unpack_46.1.3.json`](./candidates/from_unpack_46.1.3.json)：

| id | 类 | 说明 |
|----|----|------|
| email_popup_show | `...EmailPopUpFragment.show()` | TuxSheet 绑定邮箱弹层 |
| account_confirm_prompt_service | `AccountShowConfirmPromptServiceImpl` | 邮箱/手机 reconfirm，文案含 Not now |
| show_prompt_experiment | `ShowPromptToConfirmDynamicDataExperiment` | 弹窗文案 MetaData |
| tako_disclaimer_fragment | `TakoDisclaimerWithTosFragment` | AI Tako 声明 + Not now |
| poplayer_popup_manager | `com.bytedance.poplayer.core.PopupManager` | 字节弹窗队列 |
| framework_dialog_show | `android.app.Dialog.show` | 框架兜底，打栈发现调用方 |

本地反编译缓存：`临时文件/tk-hook-lab/jadx-classes{4,24,30}/`。

## 依赖

```bash
# 主机
conda activate rpa-tt
# === 默认路径（免 root · 不改包 · 外部注入）===
# 进程内 Frida/Xposed 对正式 TikTok 会 PermissionDenied（本机已实测）。
# 外部注入 = adb shell input 手势激活真实中断面 + uiautomator 抓树。
python tools/popup-hook/external_inject_activate.py --serial <serial>

# === 可选：进程内 hook（必须 root/Magisk 或 Gadget 改包，与上三约束互斥）===
pip install frida-tools   # 已装可用
# 设备：需能 attach 到 TikTok
# - Magisk + frida-server（推荐），或
# - 自签/Frida Gadget 重打包（重）
adb devices
```

当前这台 OnePlus **production 且无 root** 时，`adb root` / attach TikTok 会失败。脚本会 **自动降级**：`adb` 长按打开视频更多菜单，再抓真实 UI 树。

## 用法

```bash
# soft-ask 实验室弹窗（Frida）或降级 sheet
./tools/popup-hook/run_activate_and_dump.sh DEVICE_SERIAL softask

# 尝试真实 EmailPopUpFragment.show（需 live 实例 + Frida）
./tools/popup-hook/run_activate_and_dump.sh DEVICE_SERIAL email

# 仅 adb 长按 options sheet
./tools/popup-hook/run_activate_and_dump.sh DEVICE_SERIAL sheet

# 多场景套件：feed / sheet / share / comments（无需 root）
./tools/popup-hook/run_suite.sh DEVICE_SERIAL all

# dump → 精简 fixture
python3 tools/popup-hook/scripts/dump_to_fixture.py \
  tools/popup-hook/out/.../ui-hierarchy.xml \
  tools/popup-hook/out/fixtures/xxx.json
```

输出目录：`tools/popup-hook/out/<timestamp>-<mode>/` 或 `out/suite-*/`

- `ui-hierarchy.xml` — adb uiautomator 完整树  
- `screen.png` — 截图  
- `visible-labels.txt` / `soft-cta-hits.txt`  
- `candidates.json` — 解包候选函数  
- `suite-summary.json` — 各场景命中标记  

真机已采到的 **底部工作表** fixture：  
`out/fixtures/video_options_sheet_20260723.json`（`markers.video_options_sheet=true`）。

## 单步 adb 采树

```bash
./tools/popup-hook/scripts/capture_ui_tree.sh DEVICE_SERIAL ./out/manual manual
```

## 推送 frida-server（需 root）

```bash
# 版本需与主机 frida 一致（当前 17.16.4）
adb push frida-server /data/local/tmp/
adb shell "su -c 'chmod 755 /data/local/tmp/frida-server && /data/local/tmp/frida-server &'"
```

## 安全边界

- 仅用于**本机**弹窗策略回归与 UI 树采集  
- 不用于验证码/登录绕过  
- 生产养号仍走 `PageAnalyzer` 文字匹配 + soft CTA，不依赖 hook  
