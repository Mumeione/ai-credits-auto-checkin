# 更新日志（CHANGELOG）

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循语义化版本。

## [Unreleased]

## [1.1.1] · 2026-09-15

### 新增

- **多账号签到**：每个平台支持任意数量的账号，按平台独立管理，自动编号为「账号N」（Trae / WorkBuddy 分别计数，删除中间账号后新账号自动续号、不重名）
- 导入即追加：每次粘贴 `checkin_auth.json` 内容自动追加为新账号；相同 Token 再次粘贴会覆盖更新原账号并保留编号（用于 Token 过期续期）
- 账号卡片显示每个账号最近一次签到结果，支持单独删除账号
- 手动签到与「查询积分」均自动遍历全部账号，结果带账号名前缀（如 `Trae·账号2`）
- 每日定时签到在账号之间间隔 3 秒发起请求，避免连续请求触发风控
- **`run.cmd` 一键提取 Token**：双击完成「找 Python → 自动装 `pycryptodome` → 执行 `extract_tokens.py` → 把 `checkin_auth.json` 内容写入剪贴板」，账号切换后一条命令即可拿到新 Token 并直接粘贴进 App
  - 提取前先把上一轮产物改名为 `checkin_auth.json.bak`，避免提取失败时把旧文件当成新结果
  - 纯 ASCII + CRLF 编码，规避 cmd.exe 用 OEM 代码页解析 `.cmd` 导致的中文乱码连带解析错误

### 变更

- **存储结构**：`TokenStore` 由单账号扁平 key 重构为按平台的 JSON 账号列表（DataStore）；旧版单账号数据在首次访问时自动迁移为「账号1」，无需重新导入
- **Token 刷新写回**：Trae `ExchangeToken` 换新后的 accessToken / refreshToken 写回**对应账号**，多账号互不干扰（此前为全局覆盖）
- `TraeApi` 改为按账号实例化，刷新逻辑抽为独立的 `exchangeToken()` 函数
- 通知标题带账号名（如「Trae 签到成功 · 账号2」）
- `versionCode` 1 → 2、`versionName` → 1.1.1（v1.0.0 已占用 `versionCode` 1，必须递增否则覆盖安装会失败）

### UI 重构

- 顶部固定操作区：标题 + 三个功能按钮（签到 Trae / 签到 WorkBuddy / 查询积分）常驻，不再被账号列表顶出屏幕
- Tab 切换 Trae / WorkBuddy 账号列表，Tab 上实时显示账号数量
- 账号改为圆角卡片样式，统一按钮配色
- 导入区默认折叠（「＋ 添加账号」展开）

### UI 重构（第二轮）

- **按钮尺寸统一**：所有按钮改由同一个 `button()` 工厂生成（`TextView` + `RippleDrawable` 自绘），彻底绕开系统 `Button` 默认 `minWidth` / `minHeight` / inset 造成的尺寸漂移
  - 固定高度：主操作 46dp、次操作 44dp；统一 12dp 圆角、文字居中单行、`ellipsize` 兜底，不再出现「文字长的按钮被撑成两行、比旁边高」的问题
  - 收敛为四套语义化样式：`PRIMARY`（蓝底白字）/ `SOFT`（浅蓝底）/ `OUTLINE`（白底描边）/ `DANGER`（浅红底），同类操作视觉完全一致
  - 按钮排布改为两行：第一行两个签到按钮等宽并排，第二行「查询积分」整行同高，消除三等分时文字拥挤
- **状态提示从底部移到顶部**：原先固定在屏幕底部的状态栏（丑且挤占空间）改为顶部的**状态条**
  - 三种语气配色：信息（浅蓝）/ 成功（浅绿）/ 错误（浅红）
  - 「已提交」「已添加」等瞬时提示 4 秒后自动收起；错误提示常驻到下一次操作
  - 无内容时整条隐藏，不占布局空间
- **查询结果改用弹窗**：`查询积分` 的多行结果不再塞进底部状态栏，改为居中弹窗展示
  - 内容可长按选中复制；内容短时贴内容高度，内容长时封顶到屏幕 45% 高度后内部滚动
- 其余视觉打磨：页面改用 `#F5F7FB` 底色 + 白色描边卡片；平台切换改为分段控件（选中态白底 + 阴影）；输入框、空状态、账号卡片统一间距与字号层级

### 构建

- release 构建开启代码压缩与资源压缩，并补上 `proguard-rules.pro`（保留 `ListenableWorker` 子类的反射构造入口，以及 `androidx.work` / `androidx.datastore` 与凭据存储相关包），避免压缩后定时任务或凭据存储运行时崩溃
- 剔除依赖带入的 `META-INF` 冗余文件，关闭 APK / Bundle 中的 Play 依赖元数据块
- 不改依赖、不改架构，`assembleDebug` 不受影响

## [1.0.0] · 2026-09-15（tag: v1.0.0）

### 首个版本

- Trae 每日签到：自动查询签到状态 → 未签则领取（+200 积分）→ 系统通知播报
- WorkBuddy 每日签到：签到结果随通知展示
- 积分查询：展示 Trae 额度用量（已用 / 总额）与 WorkBuddy 签到信息
- 真实连续签到天数：本地按 `checkin_dates` 逐日回溯计算（服务端 `streak_days` 是活动累计天数，不可直接使用）
- Token 自动刷新：Trae accessToken 失效时自动调用 `ExchangeToken` 换新；刷新被拒则通知提醒重新提取
- WorkManager 每日定时（1 天周期 + 30 分钟弹性窗口，网络连通 + 电量不低约束），无常驻后台
- 电脑端提取脚本 `extract_tokens.py`：解密 Trae 凭据（AES-128-CBC + HMAC）与 WorkBuddy 明文凭据 → `checkin_auth.json`

[Unreleased]: https://github.com/Mumeione/ai-credits-auto-checkin/compare/v1.1.1...HEAD
[1.1.1]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.1.1
[1.0.0]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.0.0
