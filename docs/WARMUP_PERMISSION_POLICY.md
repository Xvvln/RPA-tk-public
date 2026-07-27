# 养号场景 · TikTok 权限策略（固化）

> 版本：0.1  
> 基线包：TikTok 国际版 `com.zhiliaoapp.musically` **46.1.3**（`versionCode=2024601030`）  
> 证据：`临时文件/tk-unpack-20260723/base.apk`（`aapt dump permissions`）+ [`POPUP_CATALOG.md`](./POPUP_CATALOG.md)

## 原则

1. **只读检测，不自动改权限**（改权限只走系统设置，由人操作）。  
2. 养号主路径是 **浏览推荐 / 搜索 / 轻互动**，不拍视频、不录语音、不扫通讯录。  
3. 策略优先服务两件事：减少系统权限弹窗、降低隐私面。  
4. 解包能证明「App 声明了哪些权限」与「文案上有哪些 soft-deny」；**不能**证明服务端何时再要权限。

## 从 APK 固化的危险运行时权限（声明）

| Android permission | 养号期望 | 理由 |
| --- | --- | --- |
| `READ_CONTACTS` | **关闭** | Manifest 声明；catalog 有 `contacts_sync_prompt` / 找好友，策略 soft-deny |
| `RECORD_AUDIO` | **关闭** | 声明 + `FOREGROUND_SERVICE_MICROPHONE`；浏览不需麦克风 |
| `CAMERA` | **关闭** | 声明 + 解包文案 “想要访问你的相机”；`FOREGROUND_SERVICE_CAMERA`；养号不拍 |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | **关闭** | Manifest 声明；浏览不需定位 |
| `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE` | **关闭** | 媒体读取；养号不发帖不选图 |
| `POST_NOTIFICATIONS` | **任意** | 声明；App 内「开启通知」走 soft-deny；系统通知开关不作为养号硬条件 |

其他 `INTERNET` / `WAKE_LOCK` / `FOREGROUND_SERVICE_*` 等属正常运行依赖，**不检测、不展示**。

## 与弹窗目录的对应

| 权限 | 相关 interruption / 策略 |
| --- | --- |
| 通讯录 | `contacts_sync_prompt`、`follow_friends_popup` → auto soft-deny |
| 通知 | `notification_prompt` → soft-deny；系统 `permission_required` → **stop** |
| 相机/麦克风 | 系统权限框 `permission_required` → **stop**（绝不点 Allow） |
| 位置 | 同上 |

## 合规判定（给 UI / 预检）

- **符合养号**：所有「期望关闭」项均为未授权。  
- **建议处理**：任一「期望关闭」项已授权 → 首页摘要警告，详情列出；**不阻止开跑**。  
- **未安装 TikTok**：红色摘要。

## UI

- 首页：一行摘要 + 版本提示。  
- 点开：策略说明 + 逐项期望/实际 + 跳转系统权限页。

实现：`WarmupPermissionPolicy` + `TikTokPermissionProbe` + `MainActivity` 详情对话框。
