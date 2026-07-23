# RPA-TT Android App

`android-app/` 是 `docs/WARMUP.md` 对应的本机 Android 实现。旧的 Python + ADB 代码继续作为选择器验证和诊断工具，不参与 App 运行。

## 构建

```bash
cd android-app
./gradlew :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次使用

1. 安装并打开 TK养号助手。
2. 从首页右上角“设置”进入应用设置，开启无障碍、悬浮窗和通知权限。
3. 手动打开并登录 TikTok。
4. 在方案页查看执行顺序，按模块开启需要的功能并设置概率、上限和相关性条件；自定义方案还可重排中间动作，然后保存。
5. 在应用设置点“仅显示悬浮窗”，返回 TikTok 后从悬浮窗开始。

配置、`events.jsonl`、`videos.jsonl`、`summary.json` 和阻断快照都保存在 App 私有目录 `files/runs/`。App 不声明网络权限，也没有服务端代码。

## 当前边界

- 已实现：固定状态机、兴趣搜索、推荐流、评论及二级评论读取、点赞、收藏、候选池评论、关注、Repost 转发、不感兴趣、预算、暂停/停止、阻断停机和本地汇总。
- 每个操作模块独立开关；概率、会话上限、相关性条件和具体参数互不耦合。
- 轻量、常规、积极方案会展示各自固定顺序；自定义方案的顺序持久化到 `workflow_order`，引擎按该顺序执行已启用动作。
- Android App 不提供预演模式；关闭模块即不执行。写操作结果不明确时立即停止且不重试。
