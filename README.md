# Auto Checkin · Trae / WorkBuddy 安卓自动签到

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android\&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin\&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![License](https://img.shields.io/badge/license-MIT-green)

一个纯原生 Kotlin 安卓应用，用 WorkManager 每日定时完成 **Trae** 与 **WorkBuddy** 的自动化签到，并通过系统通知播报结果。

应用不常驻后台，每天只短暂唤醒一次发起 HTTP 请求，电量开销可以忽略。

> ⚠️ **免责声明**
> 本项目仅用于个人学习与自用，接口均由社区逆向获得，与 Trae / WorkBuddy 官方无关。
> 请仅对**你自己的账号**使用，不要用于批量、代签、倒卖等用途。
> 使用本项目产生的一切后果（包括但不限于账号被风控、积分被回收）由使用者自行承担。

***

## 目录

- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [工作原理](#工作原理)
- [Token 生命周期与刷新](#token-生命周期与刷新)
- [常见问题](#常见问题)
- [隐私与安全](#隐私与安全)
- [相关项目](#相关项目)
- [更新日志](#更新日志)

***

## 功能特性

| 功能                 | 说明                                                                               |
| ------------------ | -------------------------------------------------------------------------------- |
| **多账号签到**          | 每个平台支持任意数量的账号，自动编号为「账号N」，循环签到；账号之间间隔 3 秒 + 0~2 秒抖动防风控。⚠️ Trae 有**设备级去重**：同机多账号每天只有第一个能签成（见常见问题） |
| **Trae 每日签到**      | 自动查询签到状态 → 未签则领取（+150/+200 积分）→ 推送通知；遇到服务端限流（「当前参与用户太多，请稍后重试」）按固定节奏重试，超过次数改为提醒手动签到
| **WorkBuddy 每日签到** | 签到结果随通知一并展示；Token 通过官方插件接口自动续期（refreshToken 轮换写回，参考 [WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)） |
| **积分查询**           | App 内「查询积分」按钮，遍历全部账号：Trae 展示额度用量（已用 / 总额），WorkBuddy 展示今日签到、本期累计天数与积分、连签奖励日；结果以弹窗展示、可长按选中复制 |
| **一键提取 Token**     | `run.cmd` 双击完成「装依赖 → 提取 → 结果自动进剪贴板」，账号切换后一条命令拿到新 Token                           |
| **签到天数口径**       | 平台侧**没有「连续签到天数」字段**：`streak_days` / `checkin_dates` 都是**本期赛季累计**（活动换期一起归零），所以 App 不显示「连续 N 天」。成长中心的 `streak.days` 是**连续登录 PC 端**的天数、与签到无关，1.4.0 曾误用、1.4.1 已撤 |
| **同一账号覆盖更新**      | 按 JWT 里的稳定身份标识（Trae `data.id` / WorkBuddy `sub`）识别账号；Token 过期后重新提取再粘贴会**覆盖原账号并保留原编号**，不再重复追加   |
| **Token 自动刷新**     | Trae accessToken 失效时自动调用 `ExchangeToken` 换新，并写回**对应账号**；若刷新被拒则通知提醒重新提取           |
| **定时签到（8 点档）**    | 每天在**东八区 08:00 起、50 分钟内**随机错峰签到（不晚于 08:50，避开早高峰）；约束不满足（无网络 / 低电量）会自动延后补签，被系统清掉导致当天完全没跑过时开 App 也会补一次（**一天只补一次**）   |
| **手动触发**           | 手动签到按钮一键遍历全部账号，导入 Token 后可立即验证是否生效                                               |

***

## 环境要求

| 用途            | 依赖                                                                      |
| ------------- | ----------------------------------------------------------------------- |
| 提取 Token（电脑端） | Python 3.8+、`pycryptodome`（跑 `run.cmd` 会自动安装，无需手动准备）                    |
| 编译 APK        | JDK 17、Android SDK（`compileSdk 35` / `build-tools`）、Gradle Wrapper（已内置） |
| 安装到手机         | Android 8.0（API 26）及以上；无线调试需 Android 11+                                |
| Token 来源      | 电脑上已登录的 Trae 桌面端 / WorkBuddy 客户端                                        |

> 编译不强制要求 Android Studio，WSL2、Linux、macOS 或 Windows 命令行均可（下文以命令行方式说明）。
> 提取 Token 需要 Trae / WorkBuddy 的客户端数据文件，**Windows 端最省事**（路径见下）。

***

## 快速开始

### 1. 电脑端提取 Token

**方式 A：一键脚本（推荐）**

直接**双击项目根目录的** **`run.cmd`**，或在该目录打开 cmd 执行：

```cmd
run.cmd
```

脚本会自己按顺序做完四件事：

1. 找 Python（优先用 Windows 官方启动器 `py -3`，找不到再退回 `python`）
2. 检测 `pycryptodome`，缺了就用 pip 自动装上
3. 执行 `extract_tokens.py` 生成 `checkin_auth.json`（上一轮产物先改名成 `checkin_auth.json.bak`，避免提取失败时把旧文件当成新结果）
4. 把 `checkin_auth.json` 的**内容直接塞进剪贴板**，切到手机 App 长按粘贴即可

**方式 B：命令行手动执行**

```cmd
py -m pip install pycryptodome
py extract_tokens.py
```

> `py` 是 Python 官方 Windows 启动器。若提示找不到 `py`，把它换成 `python` 或 `python3`。
> macOS / Linux 用 `python3 -m pip install pycryptodome && python3 extract_tokens.py`。

**脚本会自动完成：**

- **Trae**：读取 `%APPDATA%\Trae CN\User\globalStorage\storage.json`，取出 `accessToken` / `refreshToken` / 设备号
  - 设备号取的是 `iCubeAuthInfo://icube-dc:` 键后缀的 **16 位纯数字 Aha 设备号**，
    而**不是** `telemetry.devDeviceId`（那是 UUID 格式的旧设备号，仅作兜底）
  - 服务端按注册指纹校验设备号，填 UUID 会被当成陌生设备、触发更严格的限流（签到报 `9074`）
- **WorkBuddy**：读取 `%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\auth\workbuddy-desktop.info`，
  取出 `accessToken` / `refreshToken` 与 `domain`

产物为 `checkin_auth.json`，结构如下（值均为占位符）：

```json
{
  "trae_access": "<Trae accessToken>",
  "trae_refresh": "<Trae refreshToken>",
  "trae_device": "<16 位纯数字设备号>",
  "trae_machine_id": "<telemetry.machineId，App 暂未使用，先留档>",
  "trae_expired_at": "<accessToken 到期时间>",
  "wb_token": "<WorkBuddy accessToken>",
  "wb_refresh": "<WorkBuddy refreshToken>",
  "wb_domain": "<WorkBuddy domain>"
}
```

> 客户端版本更新后文件位置可能变化，若脚本报「路径不存在」，请自行搜索同名文件。

> **账号切换后一键拿 Token** —— 这是最常用的操作，完整流程只有三步：
>
> 1. 在电脑端把 Trae / WorkBuddy **切换到目标账号并确认已登录**
> 2. **双击** **`run.cmd`**（自动装依赖 → 提取 → 结果进剪贴板）
> 3. 打开 App → 点「＋ 添加账号」→ 长按输入框**粘贴** → 点「确认导入」
>
> 结果：新账号自动追加并编号为「账号N」；如果是**已有账号续期**，重新提取后粘贴会**覆盖更新原账号**、编号保持不变。

### 2. 导入手机（多账号）

打开 App，点「＋ 添加账号」展开导入区，把 `checkin_auth.json` 的**完整内容**粘贴进输入框 → 点「确认导入」。

- 用 `run.cmd` 提取的话，内容已经在剪贴板里了，**长按输入框 → 粘贴**即可，不用手动开文件
- 每次导入会**追加**为新账号，自动编号（Trae / WorkBuddy 分别计数为「账号1」「账号2」……）
- 粘贴相同 Token 会**覆盖更新**对应账号并保留原编号（用于 Token 过期后续期）
- 每个平台的账号需在电脑上**分别登录**该账号后跑一次提取脚本（桌面端只保存当前登录账号，没有多账号历史记录）
- 账号卡片上会显示最近一次签到结果，不需要的账号可直接删除

导入后可用「签到 Trae / 签到 WorkBuddy」按钮立即验证全部账号。

### 3. 构建

```bash
cd <项目目录>/checkin-app

./gradlew assembleDebug     # 调试包
./gradlew assembleRelease   # 签名 release 包（需自备 keystore，见下）
# 产物：app/build/outputs/apk/{debug,release}/
```

**自签名 keystore**：仓库不包含任何签名文件，请自行生成一套（首次使用需替换 `keystore.properties` 中的口令）：

```bash
keytool -genkeypair -v \
  -keystore keystore/checkin-release.jks \
  -alias checkin \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=Your Name, O=Your Org, C=CN"
```

`checkin-app/keystore.properties` 格式：

```properties
storeFile=keystore/checkin-release.jks
storePassword=<你的库口令>
keyAlias=checkin
keyPassword=<你的密钥口令>
```

> `build.gradle.kts` 会在 `keystore.properties` 不存在时自动跳过签名配置，所以直接 `assembleDebug` 不需要任何签名文件。

***

## 项目结构

```
Auto_Checkin/
├── run.cmd                      # 电脑端一键脚本：装依赖 → 提取 → 结果复制到剪贴板（纯 ASCII + CRLF）
├── extract_tokens.py            # 电脑端：提取并解密两平台 Token → checkin_auth.json
├── checkin_auth.json            # 提取产物（敏感文件，务必勿提交 git）
└── checkin-app/                 # 安卓工程
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle.properties
    ├── keystore.properties      # 签名配置（敏感文件，务必勿提交 git）
    ├── keystore/                # 签名密钥库（敏感文件，务必勿提交 git）
    │   └── checkin-release.jks
    └── app/
        ├── build.gradle.kts     # 依赖与构建配置
        ├── proguard-rules.pro   # release 混淆与压缩保留规则
        └── src/main/
            ├── AndroidManifest.xml
            └── java/com/example/checkin/
                ├── MainActivity.kt          # UI：多账号列表 / 导入 / 手动签到 / 积分查询
                ├── data/
                │   ├── TokenStore.kt        # DataStore 多账号凭据存储
                │   └── Jwt.kt               # 读 JWT payload，取稳定账号标识（用于覆盖更新）
                ├── network/ApiClient.kt     # OkHttp 封装（保留 HTTP 状态码）
                ├── notify/Notifier.kt       # 系统通知（含 Android 13+ 权限处理）
                └── worker/
                    ├── DailySchedule.kt     # 每日排程：8 点档窗口（08:00-08:50）、错峰、补签、固定间隔重试
                    ├── CheckinOutcome.kt    # 单账号结果归类：OK / 可重试 / 终止
                    ├── Attempt.kt           # 单账号一次签到结果（两个 Worker 共用）
                    ├── WbCheckinGate.kt     # WorkBuddy 签到互斥闸门（手动签到 vs 定时链）
                    ├── CnTime.kt            # 活动时区（东八区）下的「今天」
                    ├── WbFields.kt          # WorkBuddy 服务端字段的安全读取（字段缺失 ≠ 值为 0）
                    ├── TraeApi.kt           # Trae 签到 / 积分 / Token 刷新 API（按账号实例化）
                    ├── TraeWorker.kt        # Trae 定时任务（遍历全部账号，含限流重试）
                    ├── WorkBuddyWorker.kt   # WorkBuddy 定时任务（遍历全部账号，含 Token 自动续期）
                    └── WorkBuddyApi.kt      # WorkBuddy 接口层：RT 续期 / 鉴权判定
```

主要依赖：`androidx.work`（任务调度）、`OkHttp`（网络）、`DataStore`（凭据存储），无第三方 UI 框架。
所有 UI 都是 Kotlin 代码直接构建的，没有 `res/layout` 布局文件。

***

## 工作原理

`MainActivity.onCreate` 调用 `DailySchedule.ensureScheduled`，为两个平台各排一条**一次性任务自链**（不是 `PeriodicWorkRequest`）：

- 执行窗口：**东八区 08:00 起、50 分钟内**（即 08:00-08:50，由 `JITTER_MINUTES` 决定），在窗口内随机取一个落点，避免和其他自动签到脚本在整点抢同一个高峰
- 约束：网络已连接 + 电量不低
- 每次跑完再把下一天排上（唯一任务名带目标日期，重复排程天然幂等）

**补签：一天只走一次轮询**

1. 窗口内条件不满足（无网络 / 低电量）时，WorkManager 会把任务一直挂在队列里，条件恢复后立刻执行，哪怕已经是下午
2. 服务端限流（`9074`）时 Worker 失败后自排 **10 分钟**后的下一轮，**最多 3 次**即收手：
   限流是服务端整体容量问题，长时间反复请求既签不上、又徒增风控暴露面，
   所以到次数就停，并推一条通知提醒你**手动到客户端签到**
3. 后台被国产 ROM 清掉、导致当天**一次都没跑过**时，下次打开 App 补跑一次（静默，已签则不打扰）

第 3 条只在「今天完全没有任务记录」时生效：当天已经跑过（**哪怕失败**）就不再补，
失败就靠通知提示手动签到 / 报服务端错误码。否则每打开一次 App 都会重发一整条重试链，
反而把「收紧请求量、别硬刚限流」的意图抵消掉。

WorkManager 负责「最终一定会执行」：App 被划掉、设备重启后任务都会自动恢复。Worker 执行完即释放进程，**没有常驻服务**。

**多账号流程**：Worker 启动后读取对应平台的全部账号，逐个签到；账号之间间隔 3 秒 + 0\~2 秒抖动（参考实现 [trae-check](https://github.com/inlayin/trae-check) 用的是固定 2 秒，这里更保守）。每个账号的签到结果（含失效、异常）会写回账号卡片展示，通知标题带账号名。Trae 的 Token 刷新结果也会写回对应账号自己，互不干扰。WorkBuddy 每次续期都会**轮换 refreshToken**，所以「手动签到」与定时链之间用互斥闸门（`WbCheckinGate`）串行，避免两边同时刷新把对方的凭据顶掉。

其余细节：

- **通知权限**：Android 13+ 在启动时运行时申请；未授权时签到照常静默执行，可进 App 手动查询结果
- **凭据存储**：多账号列表（JSON）存在应用私有 DataStore 中，不写外部存储；旧版单账号数据首次启动自动迁移
- **失败通知不刷屏**：Trae 遇限流会按固定节奏重试（10 分钟一轮、最多 3 次），失败通知只在「不再重试」的那一轮发出

***

## Token 生命周期与刷新

以下数值来自对本机桌面客户端凭据的实测（2026-09-15 首次验证，2026-09-17 复核修正），官方未公开文档承诺，平台更新可能调整：

| 平台        | accessToken | refreshToken | 刷新方式                      |
| --------- | ----------- | ------------ | ------------------------- |
| Trae      | 约 14 天      | 约 180 天（约 6 个月） | App 内自动调用 `ExchangeToken`；实测该接口**幂等**——重复调用返回同一个 Token，传入的 refreshToken **不轮换**，只顺延 `RefreshExpireAt` |
| WorkBuddy | 约 60 天      | 约 70 天       | App 内自动调用官方插件接口续期（参考 [WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)）；**每次续期 refreshToken 会轮换并自动写回**，RT 也失效时才需重新提取 |

> Trae 的 refreshToken 只要仍在有效期内，App 就能自动续期 accessToken，正常情况下无需人工干预；
> WorkBuddy 同样支持自动续期——accessToken 距过期不足 7 天时先主动刷新，鉴权失败也会自动刷新后重试。
> 仅当 refreshToken 也被服务端拒绝（提示「自动刷新被拒」）时，才需重新运行提取脚本。

> 两平台接口逆向过程中的踩坑细节（错误码 `9074` / `1001`、认证头格式、ClientID 匹配、连续天数口径等）
> 已迁移至 [CHANGELOG.md](CHANGELOG.md) 的 1.2.0「踩坑记录」小节。

***

## 常见问题

**Q：多账号的 Token 怎么获取？**
桌面端只保存当前登录账号，所以需要在电脑上**轮流登录**每个账号，每切换一次就双击一次 `run.cmd`，把产出的 JSON 依次粘贴进 App（`run.cmd` 会自动把内容放进剪贴板，直接长按粘贴）。同一个账号 Token 过期后重新提取，再次粘贴会覆盖更新原账号，不会重复。

**Q：双击** **`run.cmd`** **提示「Python not found」？**
说明 Python 没装或没加进 PATH。装 Python 3.8+ 时勾上「Add python.exe to PATH」，或者改用命令行手动跑 `py -m pip install pycryptodome` + `py extract_tokens.py`。

**Q：`run.cmd`** **跑完没生成** **`checkin_auth.json`？**
说明没读到凭据。确认 Trae / WorkBuddy 桌面端已经登录了目标账号，再重跑一次；上一轮的产物会保留在 `checkin_auth.json.bak`，不会被覆盖丢失。

**Q：通知里没看到结果？**
先检查应用通知权限（Android 13+ 需手动授权），再进 App 用「查询积分」确认签到状态——签到本身不受通知权限影响。

**Q：提示「请重新提取 Token」？**
说明 Trae 的 refreshToken 刷新被服务端拒绝了。在电脑上重新跑一次 `run.cmd`，把新产出的 `checkin_auth.json` 粘贴回 App 即可。

**Q：Trae 提示「当前参与用户太多，请稍后重试」？**
这是 Trae 服务端**限流**（错误码 `9074`）。**首要看 `x-device-id` 对不对**——
2026-09-17 的对照实验（同一账号、同一套请求头、同一分钟，只换设备号）：

| `x-device-id` | `status` | `claim` |
| --- | --- | --- |
| 真实 16 位 Aha 号 | `did_checked_in=true` | **`9095` 业务响应** |
| 真实号 + `-<userId>`（拼接） | `did_checked_in=false` | **`9074` 限流** |

也就是说 **`9074` 与设备号取值强相关**：只有客户端**注册过的**那个 16 位 Aha 号
（`storage.json` 里 `iCubeAuthInfo://icube-dc:` 的后缀）才会被正常处理，**伪造号一律 9074**
（UUID、乱造的 16 位、拼接串都试过，全部 9074）。所以「换个设备号重签」是**没用的**，
频繁换号本身还是风控高危信号。

App 的处理：每轮只发 1 次签到请求，失败后每 **10 分钟**自动重试一轮、**最多 3 次**
（约 20 分钟内跑完）即收手，并推一条通知提醒你**手动到客户端签到**。

> **2026-09-17 实测记录**
> - 服务端 `checkin_20260917` 奖励包的 `start_time` = **19:46:57**（奖励包的 `start_time`
>   就是那次签到发生的时刻）。这次**不是 App 干的**——用户确认当天安卓端已卸载、未运行，
>   是 **Trae 桌面客户端自动更新后自己签的**。
> - 从客户端源码（`resources/app/out/main.js` 的 `fb()`）读到它真实的签到头集合：
>   `Authorization: Cloud-IDE-JWT` / `x-device-id` / `x-device-brand` / `x-device-type` /
>   `x-os-version` / `x-app-version`(= `product.json.appVersion`，本机 `3.3.102`)；
>   **不发** `X-User-Region`、**不发** `Trae/...` 这种自造 UA。
>   我们已修正设备号取法与 `x-app-version`（此前误用了 VS Code 内核版本 `1.107.1`）。
> - **21:00 实测签成**：换成另一台电脑的真实注册设备号后，`claim` 返回 `code=0 success`、
>   复核 `checked_in` 变为 `true` —— **证明本项目的请求格式有效**，`9074` 的症结确实在设备号。
>   顺带说明：我们多发的 `X-User-Region` 与自造 UA **并未导致失败**，所以暂不对齐这两项。

**Q：多个账号能同时签到吗？**
**同一台设备每天只有第一个账号能签成**，这是平台的设备级去重（返回 `9095`
「当前设备今日已经签到，请明日再来哦～」，此时该账号自己的 `checked_in` 仍是 `false`，
即**账号没签、是设备的名额被占了**）。而且**没法用伪造设备号绕过**——伪造即 `9074`。

2026-09-17 实测确认：同一个账号、同一套请求头，只换设备号——
上一台电脑的设备号返回 `9095`（名额被本机另一个账号用掉），
另一台电脑的**真实注册设备号**则 **`code=0` 签成**。所以：

- **每个账号要配「它自己那台机器」的设备号**（即该账号在电脑上登录后提取出来的那个
  `trae_device`）；把 A 机的设备号配给 B 机提取的账号，就会撞上设备级去重
- 同一台机器提取的多个账号，每天只有第一个能签成，其余要等第二天（或换机器）
- 结论：多账号要都签上，现实解法是**一个账号配一台设备**

**Q：重新粘贴同一个账号，为什么编号没变/还是多了一个账号？**
编号没变说明识别成功、走的正是「覆盖更新」。识别依据是 Token 里的稳定用户标识
（Trae `data.id` / WorkBuddy `sub`），所以 Token 轮换后依然认得出来，**编号会保持原样、不会新增账号**。
注意：Trae 与 WorkBuddy 分别计数，粘贴一份同时含两平台字段的 JSON 会各更新/新增一个账号。

**Q：App 里为什么没有「连续签到天数」？**
因为 WorkBuddy 平台侧就没这个字段：`checkin-activity-status` 的 `streak_days` / `checkin_dates`
都是**本期赛季累计**（赛季起止见响应里的 `start_time` / `end_time`，换期一起归零），
它统计的是「本赛季一共签了几天」，断签时是否清零未实测，所以不能包装成「连续 N 天」。
「查询积分」里列的就是这个数（写着「**本期累计**」），请按累计理解。

成长中心（`www.workbuddy.cn/profile/growth-center`）页面上那个「连续 N 天」是**连续登录天数**
——数的是每天有没有登录 WorkBuddy PC 端，**跟签到毫无关系**，同一个账号本赛季签到两天、
成长中心却可能是 0（因为没连着登 PC 端）。1.4.0 一度调 `GET /v2/activity/growth/streak`
把它当「连续签到」显示，**1.4.1 已撤回**（代码与本文档均已更正）。

**Q：WorkBuddy 签到失败了？**
WorkBuddy 的 accessToken 约 60 天过期，但 App 会**自动续期**（accessToken 临近过期先刷，鉴权失败也会刷完重试），
无需人工干预；只有 refreshToken 也被服务端拒绝（约 70 天或刷新链断裂）时才重新跑 `run.cmd`。
注意：把同一账号导入多台设备会互相顶掉 refreshToken 轮换链，请只在一处使用。
反过来也一样：**重新粘贴桌面端提取的旧快照，会把 App 里已轮换到最新节点的 RT 覆盖回旧值**——
如果 App 的续期一直正常，就没必要重新导入；确需重导（比如换机 / 刷新被拒）时，先在桌面端登录并保持在线片刻再提取，
让桌面端自己把凭据刷新到最新节点。

**Q：签到时间准确吗？**
每天在**东八区 08:00 起、50 分钟内**（即 08:00-08:50）执行，具体时刻是当天随机取的（错峰），不保证整点。
WorkManager 的任务是「尽量」而非「精确」执行，会受系统省电策略影响。
如果这个时段没跑成：

- 任务还挂在队列里（没网、低电量）→ 条件恢复后会自动补跑；
- 任务已经跑完但失败（比如服务端限流）→ **当天不再自动补**，会推一条通知提醒你手动到客户端签到；
- 当天**完全没有任务记录**（比如被 ROM 清掉）→ 打开 App 时会补跑一次。

之所以不反复补：补一次就是一整条重试链（最多 3 次请求），每开一次 App 都补一轮
反而会把「收紧请求量、别硬刚限流」的意图抵消掉。

**Q：怎么把签到时间改到别的时段？**
改 `checkin-app/app/src/main/java/com/example/checkin/worker/DailySchedule.kt`：
`WINDOW_START_HOUR`（窗口起点小时，默认 8）、`JITTER_MINUTES`（窗口内随机错峰幅度，默认 50 分钟）、
`RETRY_DELAY_MINUTES`（失败后的重试间隔，默认 10 分钟）、`MAX_RUN_ATTEMPTS`（最多尝试次数，默认 3），
时区固定东八区。

**Q：接口突然开始报错？**
两平台接口均来自社区逆向，客户端 / 服务端更新后可能失效，需要跟进社区实现更新。

***

## 隐私与安全

**以下文件等同账号密码，绝对不能提交到公开仓库：**

- `checkin_auth.json`（含两平台的 accessToken / refreshToken）
- `checkin-app/keystore.properties`（含签名口令）
- `checkin-app/keystore/*.jks`（签名私钥）

仓库根目录已附带 `.gitignore`，覆盖上述敏感文件以及构建产物：

````gitignore

**推送前自检**（确认没有敏感文件被跟踪）：

```bash
git ls-files | grep -Ei "checkin_auth|keystore|\.jks|\.apk"
# 无输出 = 干净
````

已经误提交过？**仅加 .gitignore 是没用的**——历史提交里仍然能翻到。必须重写历史
（`git filter-repo`）或删除仓库重建，并且**立即去平台重新提取 / 作废旧 Token**。

App 自身的隐私行为：Token 只保存在应用私有存储中，仅用于向 Trae / WorkBuddy 官方域名发起签到请求，不上传到任何第三方服务器。

***

## 相关项目

本项目的接口实现参考了以下社区项目，感谢作者们的付出：

- [Trae-AutoCheckin](https://github.com/L0NE-6/Trae-AutoCheckin)（Trae 请求形态与抗 9074 的讨论；其中「换设备号解 9074」经本机实测不成立，见 CHANGELOG）
- [WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)（WorkBuddy 插件接口续期与智能续期节奏）
- [trae-check](https://github.com/inlayin/trae-check)
- [traework2api](https://github.com/Sliverkiss/traework2api)
- [trae-mate](https://github.com/luckymiaow/trae-mate)
- [workbuddy-switch](https://github.com/changexbc/workbuddy-switch)

***

## 许可证

本项目基于 [MIT License](LICENSE) 开源。

***

## 更新日志

详见 [CHANGELOG.md](CHANGELOG.md)。
