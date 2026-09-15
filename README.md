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
- [踩坑记录](#踩坑记录)
- [常见问题](#常见问题)
- [隐私与安全](#隐私与安全)
- [相关项目](#相关项目)
- [更新日志](#更新日志)

***

## 功能特性

| 功能                 | 说明                                                                               |
| ------------------ | -------------------------------------------------------------------------------- |
| **多账号签到**          | 每个平台支持任意数量的账号，自动编号为「账号N」，循环签到并在账号间间隔 3 秒防风控                                      |
| **Trae 每日签到**      | 自动查询签到状态 → 未签则领取（+200 积分）→ 推送通知                                                  |
| **WorkBuddy 每日签到** | 签到结果随通知一并展示                                                                      |
| **积分查询**           | App 内「查询积分」按钮，遍历全部账号，展示 Trae 额度用量（已用 / 总额）与 WorkBuddy 签到信息，结果以弹窗展示、可长按选中复制       |
| **一键提取 Token**     | `run.cmd` 双击完成「装依赖 → 提取 → 结果自动进剪贴板」，账号切换后一条命令拿到新 Token                           |
| **真实连续签到天数**       | 服务端 `streak_days` 返回的是本期活动**累计**天数（断签不清零），App 本地按 `checkin_dates` 逐日回溯，算出真正的连续天数 |
| **Token 自动刷新**     | Trae accessToken 失效时自动调用 `ExchangeToken` 换新，并写回**对应账号**；若刷新被拒则通知提醒重新提取           |
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

- **Trae**：读取 `%APPDATA%\Trae CN\User\globalStorage\storage.json`，取出 `accessToken` / `refreshToken` / `deviceId`
- **WorkBuddy**：读取 `%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\auth\workbuddy-desktop.info`，
  取出 `accessToken` 与 `domain`

产物为 `checkin_auth.json`，结构如下（值均为占位符）：

```json
{
  "trae_access": "<Trae accessToken>",
  "trae_refresh": "<Trae refreshToken>",
  "trae_device": "<Trae deviceId>",
  "wb_token": "<WorkBuddy accessToken>",
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
                ├── data/TokenStore.kt       # DataStore 多账号凭据存储（按平台分列表）
                ├── network/ApiClient.kt     # OkHttp 封装
                ├── notify/Notifier.kt       # 系统通知（含 Android 13+ 权限处理）
                └── worker/
                    ├── TraeApi.kt           # Trae 签到 / 积分 / Token 刷新 API（按账号实例化）
                    ├── TraeWorker.kt        # Trae 定时任务（遍历全部账号）
                    └── WorkBuddyWorker.kt   # WorkBuddy 定时任务（遍历全部账号，含连续签到天数计算）
```

主要依赖：`androidx.work`（任务调度）、`OkHttp`（网络）、`DataStore`（凭据存储），无第三方 UI 框架。
所有 UI 都是 Kotlin 代码直接构建的，没有 `res/layout` 布局文件。

***

## 工作原理

`MainActivity.onCreate` 中注册两个 `PeriodicWorkRequest`：

- 周期 1 天，带 30 分钟弹性窗口（系统可在窗口内择机执行，更省电）
- 约束：网络已连接 + 电量不低

WorkManager 负责「最终一定会执行」：App 被划掉、设备重启后任务都会自动恢复。Worker 执行完即释放进程，**没有常驻服务**。

**多账号流程**：Worker 启动后读取对应平台的全部账号，逐个签到；账号之间间隔 3 秒，避免连续请求触发风控。每个账号的签到结果（含失效、异常）会写回账号卡片展示，通知标题带账号名。Trae 的 Token 刷新结果也会写回对应账号自己，互不干扰。

其余细节：

- **通知权限**：Android 13+ 在启动时运行时申请；未授权时签到照常静默执行，可进 App 手动查询结果
- **凭据存储**：多账号列表（JSON）存在应用私有 DataStore 中，不写外部存储；旧版单账号数据首次启动自动迁移

***

## Token 生命周期与刷新

以下数值来自对本机桌面客户端凭据的实测（2026-09-15 验证），官方未公开文档承诺，平台更新可能调整：

| 平台        | accessToken | refreshToken | 刷新方式                      |
| --------- | ----------- | ------------ | ------------------------- |
| Trae      | 约 14 天      | 约 180 天（约 6 个月） | App 内自动调用 `ExchangeToken`；实测刷新签发的 refreshToken 可能更长（观测到约 16 个月） |
| WorkBuddy | 约 60 天      | 约 80 天       | 凭据文件中存有 refreshToken（Keycloak 风格），但社区尚无已验证的刷新接口，失效后重新提取 |

> Trae 的 refreshToken 只要仍在有效期内，App 就能自动续期 accessToken，正常情况下无需人工干预；
> WorkBuddy 到期后需重新运行提取脚本。

***

## 踩坑记录

### Trae

- 认证头是 `Authorization: Cloud-IDE-JWT <token>`，**不是** `Bearer`；并且必须携带 `X-Device-Id` 头（缺失返回 `9004`）
- 签到接口一律返回 HTTP 200，**成败要看响应体里的** **`code`** **字段**（`0` 表示成功）
- `ExchangeToken` 的 `ClientID` 必须与 Token 来源客户端匹配：
  Trae CN 桌面端 stable 为 `ono9krqynydwx5`，SOLO 版为 `en1oxy7wnw8j9n`。
  用错会返回 `refresh token is not matched to the client`。
  完整的 ClientID 表见 Trae 安装目录下 `resources/app/product.json` 的 `iCubeApp.authConfig`
- 刷新接口是**幂等**的：对同一个 refreshToken 重复调用，只会返回同一会话的新 accessToken，
  旧 refreshToken 不会作废，桌面端登录态也不受影响

### WorkBuddy

- `checkin-activity-status` 返回的 `streak_days` 是**本期活动累计**签到天数，断签不清零，不能直接当连续天数用
- 真实连续天数需根据 `checkin_dates` 数组在本地回溯计算（今天还没签则从昨天起算）

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

**Q：WorkBuddy 签到失败了？**
WorkBuddy 的 accessToken 约 60 天过期且无法自动刷新，重新跑 `run.cmd` 即可。

**Q：签到时间不固定？**
WorkManager 的周期任务是「尽量」而非「精确」执行，会受系统省电策略影响，通常会在窗口内完成，不保证整点。

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
