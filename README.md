# Auto Checkin · Trae / WorkBuddy 安卓自动签到

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin-100%25-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![License](https://img.shields.io/badge/license-MIT-green)

一个纯原生 Kotlin 安卓应用，用 WorkManager 每日定时完成 **Trae** 与 **WorkBuddy** 的自动化签到，并通过系统通知播报结果。

应用不常驻后台，每天只短暂唤醒一次发起 HTTP 请求，电量开销可以忽略。

> ⚠️ **免责声明**
> 本项目仅用于个人学习与自用，接口均由社区逆向获得，与 Trae / WorkBuddy 官方无关。
> 请仅对**你自己的账号**使用，不要用于批量、代签、倒卖等用途。
> 使用本项目产生的一切后果（包括但不限于账号被风控、积分被回收）由使用者自行承担。

---

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

---

## 功能特性

| 功能 | 说明 |
|---|---|
| **Trae 每日签到** | 自动查询签到状态 → 未签则领取（+200 积分）→ 推送通知 |
| **WorkBuddy 每日签到** | 签到结果随通知一并展示 |
| **积分查询** | App 内「查询积分」按钮，展示 Trae 额度用量（已用 / 总额）与 WorkBuddy 签到信息 |
| **真实连续签到天数** | 服务端 `streak_days` 返回的是本期活动**累计**天数（断签不清零），App 本地按 `checkin_dates` 逐日回溯，算出真正的连续天数 |
| **Token 自动刷新** | Trae accessToken 失效时自动调用 `ExchangeToken` 换新；若刷新被拒则通知提醒重新提取 |
| **手动触发** | 两个手动签到按钮，导入 Token 后可立即验证是否生效 |

---

## 环境要求

| 用途 | 依赖 |
|---|---|
| 提取 Token（电脑端） | Python 3.8+、`pycryptodome` |
| 编译 APK | JDK 17、Android SDK（`compileSdk 35` / `build-tools`）、Gradle Wrapper（已内置） |
| 安装到手机 | Android 8.0（API 26）及以上；无线调试需 Android 11+ |
| Token 来源 | 电脑上已登录的 Trae 桌面端 / WorkBuddy 客户端 |

> 编译不强制要求 Android Studio，WSL2、Linux、macOS 或 Windows 命令行均可（下文以命令行方式说明）。
> 提取 Token 需要 Trae / WorkBuddy 的客户端数据文件，**Windows 端最省事**（路径见下）。

---

## 快速开始

### 1. 电脑端提取 Token

```powershell
pip install pycryptodome
python extract_tokens.py
```

脚本会自动完成：

- **Trae**：读取 `%APPDATA%\Trae CN\User\globalStorage\storage.json`，解密其中的 `iCubeAuthInfo://icube.cloudide`
  （AES-128-CBC，密钥由 SHA-512 派生 + HMAC 校验），取出 `accessToken` / `refreshToken` / `deviceId`
- **WorkBuddy**：读取 `%LOCALAPPDATA%\CodeBuddyExtension\Data\Public\auth\workbuddy-desktop.info`（明文 JSON），
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

### 2. 导入手机

打开 App，把 `checkin_auth.json` 的**完整内容**粘贴进输入框 → 点「保存 Token」。
保存成功后可直接用两个手动签到按钮验证。

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

### 4. 安装（无线调试）

手机与电脑连同一个 Wi-Fi，打开「开发者选项 → 无线调试」，配对一次即可：

```bash
adb pair <IP>:<配对端口>       # 按提示输入配对码
adb connect <IP>:<主页面端口>
adb install -r app/build/outputs/apk/release/app-release.apk
```

---

## 项目结构

```
Auto_Checkin/
├── extract_tokens.py            # 电脑端：提取并解密两平台 Token → checkin_auth.json
├── checkin_auth.json            # 提取产物（敏感文件，务必勿提交 git）
└── checkin-app/                 # 安卓工程
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── keystore.properties      # 签名配置（敏感文件，务必勿提交 git）
    ├── keystore/                # 签名密钥库（敏感文件，务必勿提交 git）
    │   └── checkin-release.jks
    └── app/src/main/
        ├── AndroidManifest.xml
        └── java/com/example/checkin/
            ├── MainActivity.kt          # UI：Token 导入 / 手动签到 / 积分查询
            ├── data/TokenStore.kt       # DataStore 凭据存储
            ├── network/ApiClient.kt     # OkHttp 封装
            ├── notify/Notifier.kt       # 系统通知（含 Android 13+ 权限处理）
            └── worker/
                ├── TraeApi.kt           # Trae 签到 / 积分 / Token 刷新 API
                ├── TraeWorker.kt        # Trae 定时任务
                └── WorkBuddyWorker.kt   # WorkBuddy 定时任务（含连续签到天数计算）
```

主要依赖：`androidx.work`（任务调度）、`OkHttp`（网络）、`DataStore`（凭据存储），无第三方 UI 框架。

---

## 工作原理

`MainActivity.onCreate` 中注册两个 `PeriodicWorkRequest`：

- 周期 1 天，带 30 分钟弹性窗口（系统可在窗口内择机执行，更省电）
- 约束：网络已连接 + 电量不低

WorkManager 负责「最终一定会执行」：App 被划掉、设备重启后任务都会自动恢复。Worker 执行完即释放进程，**没有常驻服务**。

其余细节：

- **通知权限**：Android 13+ 在启动时运行时申请；未授权时签到照常静默执行，可进 App 手动查询结果
- **UI**：targetSdk 35 强制 edge-to-edge，界面通过 `WindowInsets` 为状态栏 / 导航栏留白
- **凭据存储**：Token 存在应用私有 DataStore 中，不写外部存储

---

## Token 生命周期与刷新

| 平台 | accessToken | refreshToken | 刷新方式 |
|---|---|---|---|
| Trae | 约 10 天 | 约 14 个月 | App 内自动调用 `ExchangeToken` |
| WorkBuddy | 约 60 天 | 约 90 天 | 接口不支持自动刷新，失效后重新提取 |

---

## 踩坑记录

### Trae

- 认证头是 `Authorization: Cloud-IDE-JWT <token>`，**不是** `Bearer`；并且必须携带 `X-Device-Id` 头（缺失返回 `9004`）
- 签到接口一律返回 HTTP 200，**成败要看响应体里的 `code` 字段**（`0` 表示成功）
- `ExchangeToken` 的 `ClientID` 必须与 Token 来源客户端匹配：
  Trae CN 桌面端 stable 为 `ono9krqynydwx5`，SOLO 版为 `en1oxy7wnw8j9n`。
  用错会返回 `refresh token is not matched to the client`。
  完整的 ClientID 表见 Trae 安装目录下 `resources/app/product.json` 的 `iCubeApp.authConfig`
- 刷新接口是**幂等**的：对同一个 refreshToken 重复调用，只会返回同一会话的新 accessToken，
  旧 refreshToken 不会作废，桌面端登录态也不受影响

### WorkBuddy

- `checkin-activity-status` 返回的 `streak_days` 是**本期活动累计**签到天数，断签不清零，不能直接当连续天数用
- 真实连续天数需根据 `checkin_dates` 数组在本地回溯计算（今天还没签则从昨天起算）

---

## 常见问题

**Q：通知里没看到结果？**
先检查应用通知权限（Android 13+ 需手动授权），再进 App 用「查询积分」确认签到状态——签到本身不受通知权限影响。

**Q：提示「请重新提取 Token」？**
说明 Trae 的 refreshToken 刷新被服务端拒绝了。在电脑上重新运行 `python extract_tokens.py`，把新产出的 `checkin_auth.json` 粘贴回 App 即可。

**Q：WorkBuddy 签到失败了？**
WorkBuddy 的 accessToken 约 60 天过期且无法自动刷新，重新跑提取脚本即可。

**Q：签到时间不固定？**
WorkManager 的周期任务是「尽量」而非「精确」执行，会受系统省电策略影响，通常会在窗口内完成，不保证整点。

**Q：接口突然开始报错？**
两平台接口均来自社区逆向，客户端 / 服务端更新后可能失效，需要跟进社区实现更新。

---

## 隐私与安全

**以下文件等同账号密码，绝对不能提交到公开仓库：**

- `checkin_auth.json`（含两平台的 accessToken / refreshToken）
- `checkin-app/keystore.properties`（含签名口令）
- `checkin-app/keystore/*.jks`（签名私钥）

仓库根目录已附带 `.gitignore`，覆盖上述敏感文件以及构建产物：

```gitignore
# 凭据与签名（敏感）
checkin_auth.json
keystore.properties
**/keystore/
*.jks
*.keystore

# 构建产物
build/
.gradle/
local.properties

# 本地备份与发布产物
README_local.md
release/

# IDE
.idea/
*.iml
.DS_Store
```

**推送前自检**（确认没有敏感文件被跟踪）：

```bash
git ls-files | grep -Ei "checkin_auth|keystore|\.jks|\.apk"
# 无输出 = 干净
```

已经误提交过？**仅加 .gitignore 是没用的**——历史提交里仍然能翻到。必须重写历史
（`git filter-repo`）或删除仓库重建，并且**立即去平台重新提取 / 作废旧 Token**。

App 自身的隐私行为：Token 只保存在应用私有存储中，仅用于向 Trae / WorkBuddy 官方域名发起签到请求，不上传到任何第三方服务器。

---

## 相关项目

本项目的接口实现参考了以下社区项目，感谢作者们的逆向工作：

- [trae-check](https://github.com/inlayin/trae-check)
- [traework2api](https://github.com/Sliverkiss/traework2api)
- [trae-mate](https://github.com/luckymiaow/trae-mate)
- [workbuddy-switch](https://github.com/changexbc/workbuddy-switch)

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源。
