# 更新日志（CHANGELOG）

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循语义化版本。

## [1.4.1] · 2026-09-17

修正 1.4.0 当天引入的一处**口径错误**：把成长中心的「连续登录」当成了「连续签到」。

### 修复 / 变更

- **撤回「连续签到天数取成长中心」这条口径（App 不再显示任何「连续」）**
  - 用户指出：成长中心（`www.workbuddy.cn/profile/growth-center`）数的是**每天登录
    WorkBuddy PC 端**的天数、**要登录桌面端才会累加**，与加油站签到毫无关系；
    它那套 7d/14d/28d 档位、补登卡 `makeup_cards` 都是**登录**连击奖励
  - 同日实测（同一分钟、两个账号）：`checkin_dates` 都是 `["2026-09-17","2026-09-16"]`、
    `streak_days` 都是 2，成长中心却一个 `days=7`、一个 `days=0`（后者本月只在 PC 登录 1 天）
    → 拿它当连续签到必然误导
  - 删除 `WorkBuddyApi.fetchStreakDays()` 与 `GROWTH_PATH`；`WorkBuddyWorker` 的局部 `streak()`
    及 4 处调用一并删除，通知文案 `streakText(streak, credit)` → `creditText(credit)`，
    只报本次到账积分；`MainActivity.wbStatusLine()` 去掉 `streakDays` 参数与「连续 N 天」
  - `ApiClient.getRaw` / `get` 当初专为这个只读查询而加、已无调用方，一并删除
  - 「查询积分」保留签到接口自己的 **`streak_days`（本期赛季累计）**，文案标「本期」，
    不包装成「连续」——平台侧**根本没有「连续签到」字段**（断签是否清零仍未实测）
  - 「要不要自己算连续」本轮**决定不算**：本地历史回溯（1.2.0）只记 App 跑成功的那几天、
    必然失真；赛季内按 `checkin_dates` 回溯虽然精确，但窗口只有 14 天，等有需求再谈
- **文档与注释同步更正**：README 特性表 / FAQ（改为「App 里为什么没有连续签到天数」）、
  `WorkBuddyApi` / `WorkBuddyWorker` / `CnTime` / `TokenStore` 的注释
- `versionCode` 5 → 6、`versionName` → 1.4.1（1.4.0 的包已装机，必须递增才装得上）

### 踩坑记录

- **字段名很像、口径可能完全不同**：`streak_days`（本期赛季累计**签到**）与成长中心的
  `streak.days`（连续**登录**）都叫「streak」，一天之内把后者当前者用了一次。
  判定口径别只看字段名，要看它统计的是**哪个动作**（登录 ≠ 签到）
- 成长中心 `/streak` 在 `copilot.tencent.com` / `www.codebuddy.cn` / `www.workbuddy.cn`
  三个域名上返回**逐字相同**，同一 Token 连续调用（0.3 s ×3）也**无限流痕迹**——
  所以「多账号依次查不出来」不是限流问题（该接口现已不再调用）

### 实测记录

- 赛季起止就在签到响应里：`start_time` = 赛季开始（实测 season 9 = `2026-09-16 00:00:00`）、
  `end_time` = 赛季结束（`2026-09-29 23:59:59`），另有 `season` 号

## [1.4.0] · 2026-09-17

本次两块改动：① WorkBuddy「连续签到天数」口径收敛（**该口径当晚被推翻，见 1.4.1**）；
② Trae 设备号取值修正 + 重试策略收紧。

> ⚠️ ① 的定性（「成长中心 `streak.days` = 真实连续签到天数」）**是错的**——
> 那是连续**登录** PC 端的天数。相关调用与「连续 N 天」显示已在 **1.4.1 整体删除**，
> 详见 [1.4.1]。下文对应条目已标注处置结果。

### 修复

- **`extract_tokens.py` 一直取错了 Trae 设备号（`9074` 限流的一个真实成因）**
  - 原来取 `telemetry.devDeviceId`，实测它是 **UUID（36 字符）**；而 `storage.json` 里存在
    `has_device_id_updated_to_aha = true`，说明客户端**早已迁移到 Aha 设备号**——
    正确来源是 `iCubeAuthInfo://icube-dc:<16 位数字>` 这个 key 的后缀
  - 服务端按**注册指纹**校验设备号，UUID 不被识别为已注册设备，会被当成陌生设备
    触发更严格的限流（签到接口表现为 `9074`「参与用户太多」）
  - 现改为优先取 16 位纯数字设备号，UUID 仅作兜底；顺带新增 `trae_machine_id` 留档
- **Trae 参数类错误不再白跑重试链**
  - 新增 `PARAM_CODES = {9004}`：`9004` 是请求参数错误（实测为 claim 缺 `x-device-id` 时返回），
    重试只会拿到同一个结果，不该占满整条重试链
  - `TraeStatus.Failed` / `TraeClaim.Failed` 新增 `retryable` 字段，Worker 据此在
    「可重试（RETRYABLE）」与「不可重试（FATAL）」之间选择
- **`creditsOf` 不再凭空返回 150 积分**：抠不出积分数时返回 null，通知只报「签到成功」，
  不再编造一个「本次 +150 积分」
- **`x-app-version` 用错了版本号（Trae `9074` 的另一处嫌疑）**
  - 原来发的是 VS Code 内核版本 `1.107.1`；2026-09-17 从本机客户端源码
    （`resources/app/out/main.js` 的 `fb()`）确认，客户端发的是 `commonParams.app_version`
    = `product.json.appVersion` = **`3.3.102`**（也就是「关于」里显示的 3.3.x）
  - 版本号对不上会被服务端当成「未知 / 过旧客户端」，很可能是更严格限流的来源之一
- **设备级去重（`9095`）不再被当成「可重试失败」**
  - 实测文案「当前设备今日已经签到，请明日再来哦～」、业务码 `9095`：此时 `checked_in=false`
    ——**账号**没签，是**设备**今天的签到名额已经被用掉
  - 此前 `isAlreadyCheckedIn()` 会因「设备」字样把它排除，然后落到裸 `9095` 分支 → status 复核
    发现未签到 → 归为**可重试**，于是白跑 3 轮，文案还是错的（提示「设备号缺失」）
  - 现在 `kindOf()` 新增 `DEVICE_BLOCKED`：命中「设备 / device / machine」措辞即判为设备级去重，
    归**不可重试**；`TraeWorker` 的文案也按错误码区分（`9004` → 设备号缺失需重新提取；
    `9095` → 本设备今日名额已被用掉）
- **「查询积分」不再把非鉴权失败报成「Token 失效」（与当初把 Trae `9074` 当 Token 失效同一类毛病）**
  - 原判定是「`code != 0` 或没有 `data` → 报 Token 失效，自动刷新被拒」，但走这条路的不只是
    鉴权失败：活动已结束 / 未开始、限流、5xx 空响应体都会落进来
  - 更糟的是「自动刷新被拒」是**假的**——只有 `isAuthFailure` 为真时才会去续期，
    这些情况压根没刷过。拿一个错误结论把用户往「重新提取 Token」上推，排查时极易误判
  - 现拆成四态：**鉴权失效 / 响应不是合法 JSON / 正常 / 其它失败（如实报服务端给的原因）**；
    判定顺序固定为「先鉴权、再解析」——HTTP 401 且响应体为空时 `json` 是 null，
    若先看 `json` 会把真正的 Token 失效报成「响应不是合法 JSON」
  - 空 body 不再用 `JSONObject(body.ifBlank { "{}" })` 兜底成「`code=0` 但没有 `data`」，
    统一走新增的 `WorkBuddyApi.parseJson`
- **`WorkBuddyApi.isAuthFailure` 两处口径收紧**
  - 文案兜底（token / 登录 / 未授权）加上 `code != 0` 前置：否则响应里任何地方出现
    「登录」「token」字样（比如活动名）都会被判成鉴权失效。口径对齐 `TraeApi.isAuthFailed`
  - `WorkBuddyWorker` 的状态查询原先把 `null` 当解析结果传进去（只剩 HTTP 码一条路径），
    「HTTP 200 + 业务码 401」会漏判——于是带着已过期的 Token 又发一次 `daily-checkin`
    （**写操作**），到 claim 阶段才发现。现在先 parse 再判定
- **重试轮不再用固定名 + `REPLACE` 自排（那会取消正在运行的自己）**
  - 原先重试唯一名固定为 `{kind}_{链首日期}_retry`，而 Worker 自排下一轮时用的正是同一个名字，
    `REPLACE` 命中的就是**当前这个 RUNNING 的 work 自己**——只是恰好写在 `doWork` 最后一步、
    新任务又在取消之后才插入，才没丢链，属于**时机上的侥幸**
  - 现在唯一名带轮次（`..._retry_{轮次}`），名字天然唯一，改用 `KEEP`：
    既不重复入队、也**不会取消任何正在运行的任务**，顺带让每一轮都有自己的执行记录可查
  - `alreadyHandled` 的探测同步改为按轮次逐个探（另保留裸 `_retry` 兜住「刚升级时正挂着的旧链」；
    重试链从首轮到收手不到 20 分钟，下个版本可删）
- **手动签到与定时链不再互相顶掉 WorkBuddy 的 refreshToken**
  - 两个入口唯一任务名不同（`wb_once` vs `workbuddy_<日期>`），WorkManager 层面不互斥、
    会真正并发；而 WorkBuddy 每次续期都会**轮换 RT**，两边同时刷会把对方刚拿到的顶失效
    （最坏要在电脑上重新提取）；`lastResult` 的「读全量 → 改一条 → 写全量」并发也会丢更新
  - 新增 `WbCheckinGate`（独立 object 里的 `Mutex`——**不能**用各 Worker 的
    `companion object`，那样两个类拿到的是两把不同的锁，等于没锁），
    把「读账号 → 签到 → 写回」整段串行；账号列表也在锁内重读，
    避免拿锁外读到的旧 Token 白吃一次 401
  - Trae 侧不加锁：`ExchangeToken` 幂等（重复刷新返回同一会话、旧 RT 不作废）

### 变更

- **⚠️ 连续签到天数改用成长中心接口，删除本地回溯与历史持久化 —— 该口径已被撤回（见 [1.4.1]）**
  - **撤回原因**：成长中心的 `streak.days` 是**连续登录 PC 端**的天数，不是连续签到
    （2026-09-17 用户更正）。签到侧**根本没有「连续」字段**，`streak_days` 是本期赛季
    累计口径，所以最终结论是：**App 不显示连续天数**，只显示「本期累计」。
  - 以下为该次尝试的过程记录，相关代码（`fetchStreakDays` 等）已全部移除：
  - 新增 `WorkBuddyApi.fetchStreakDays()`：`GET {domain}/v2/activity/growth/streak`，
    取 `data.streak.days`。该接口与签到**同域名**（`copilot.tencent.com`）、
    只需 Bearer Token、**不需要 `X-User-Id` 或任何设备指纹**
  - 为什么当时这么换：`checkin-activity-status` 的 `streak_days` / `checkin_dates` 是按**活动赛季**
    计数的，换期一起归零（2026-09-17 实测：season 9 从 9/16 开跑，两个字段都只有 2）；
    当时以为成长中心的 `streak.days` 跨赛季连续、与客户端成长中心页面显示一致（同一次实测为 7 天）
  - 1.2.0 那套「本地持久化日期 ∪ 服务端日期再回溯」**整体删除**（**这条保留，别再恢复**：
    它只记「App 自己跑成功的那几天」，用户手动签的、换设备期间签的都不知道，必然失真）：
    `TokenStore` 的 `wb_checkin_history_json` / `wbHistory` / `mergeWbHistory` 与
    `CheckinStreak.kt` 的回溯函数全部移除，文件改名为 `CnTime.kt` 只留东八区时间基准
- **「查询积分」新增三个服务端字段**
  - `total_credits`（展示为「本期累计」）、`is_streak_day`、`next_streak_day`
  - `total_credits` 是**本期活动**口径、不是账号总积分：2026-09-17 实测 200 恰为
    `streak_days (2) × daily_credit (100)`。文案必须写明「本期」，
    否则会像社区脚本那样被读成账号总资产
  - `next_streak_day = 0` 的语义是「本期没有下一档奖励日」而非「第 0 天」，只在 `> 0` 时提示
  - 服务端字段一律经新增的 `WbFields.kt`（`JSONObject.intOrNull` / `boolOrNull`）读取：
    「字段不存在」和「字段值是 0 / false」是两件事，用 `optInt(key, 0)` 会把未知显示成已知
- **鉴权失败判定收拢为一份**：`WorkBuddyWorker` 与 `MainActivity` 原先各写一份
  「HTTP/业务码 401/403 + token / 登录 / 未授权文案」的判定、靠注释维持一致，
  现统一到 `WorkBuddyApi.isAuthFailure`。两处一旦漂移，
  同一种失败会在「定时签到」里自动续期后重试成功、在「查询积分」里却直接报 Token 失效
- **修复「查询积分」与定时签到口径不一致**：原先只在 `today_checked_in == true` 时
  才处理服务端本期日期，而 Worker 是**无条件**收下——今天还没签就来查询会白丢记录
  （相关逻辑已随本地历史一并删除）
- **`ApiClient` 的 GET 能力（已在 [1.4.1] 移除）**：曾为成长中心接口加过 `getRaw` / `get`，
  随该接口一并删除（全项目没有别的调用方），`ApiClient` 回到只有 POST 的状态
- **Trae 重试策略收紧**（`DailySchedule`）
  - 签到窗口 `09:00-10:00` → **`08:00-09:00`**（避开早高峰）
    - 实际落点是 **08:00 起、`JITTER_MINUTES`（50 分钟）内随机**，即不晚于 **08:50**；
      「08:00-09:00」是含 10 分钟余地的说法，不会排到 09:00 之后
    - 另：原先还有个 `WINDOW_MINUTES = 60` 的常量，全项目无人引用、改它没有任何效果，
      却声称窗口 08:00-09:00——与真实行为对不上的死常量只会误导，已删除
  - 失败重试间隔 `30 分钟` → **`10 分钟`**
  - 最多尝试 `32 次` → **`3 次`**；到次数即收手，推一条通知提醒**手动到客户端签到**，
    不再长时间反复请求（既签不上、又徒增风控暴露面）
  - 新增 `TraeWorker.retryableText()`，统一生成「还会自动重试」/「已到上限请手动签到」的文案
- **兜底补签改为「一天只走一次轮询」**
  - 原先的判定是「未完成或已成功才算处理过」，失败/取消**不拦补签**（本意是给当天最后一次机会）——
    结果是当天跑失败后，**每打开一次 App 就重发一整条重试链**（最多 3 次请求），
    与「收紧请求量、别硬刚限流」的意图正好相反
  - 现在改为「只要今天相关任务**存在过**就算数」（成功 / 失败 / 取消一视同仁）：
    补签只在**今天完全没有任务记录**时发生（典型场景是后台任务被 ROM 清掉、
    昨天没能把今天的任务排上），失败后不再靠反复补签撞运气，
    改由通知提示手动签到（Trae）/ 报服务端错误码（WorkBuddy）
- **`Attempt` 收敛为一份**：`TraeWorker` 与 `WorkBuddyWorker` 原先各自私有了一份字段完全相同的
  data class（`outcome` / `title` / `message` / `alreadyDone`）。两处一旦漂移
  （一边加了标记、另一边没加），通知与收尾逻辑就会各按各的理解走；
  现提为 `worker/Attempt.kt` 共用
- **Trae 响应判定口径收敛**：`status` 与 `claim` 原先各写一套 code 分类，且顺序与语义不一致
  （`Busy` 只有 claim 有）；现抽成共用的 `TraeApi.kindOf()`，顺序固定为
  「已签到 → 限流 → 参数 → 鉴权 → 其它」
- **删除 `TraeApi.claimWith(device)` 死代码**：设备号轮换机制删除后遗留的无用间接层
- `versionCode` 4 → 5、`versionName` → 1.4.0

### 踩坑记录（新增）

- **`claim_button_text` 不可靠**：2026-09-17 实测 `today_checked_in = true` 时
  它仍返回「立即领取」，不能拿来判断今天签没签
- **成长中心接口在 `copilot.tencent.com` 上、带 `/v2/activity/growth` 前缀**：
  与部分社区资料「成长中心域名是 www.workbuddy.cn、路径不带 `/v2`」的说法不同——
  至少 `/streak` 不是，实测同一域名 + 同一个 Bearer Token 即返回 200
  （该接口现已不再调用；顺带实测 `copilot.tencent.com` / `www.codebuddy.cn` /
  `www.workbuddy.cn` 三个域名的 `/streak` 返回**逐字相同**，同一 Token 连续调用也无限流痕迹）
- `checkin_dates` 是**降序**数组（最新在前）。只做集合成员判断时不受影响，
  但要拿它「取最近一条」就必须先排序
- **`JSONObject(body.ifBlank { "{}" })` 会把「服务端没给 JSON」伪装成「成功」**：
  空 body 解析成 `{}` 后 `optInt("code")` 是 `0`、`data` 是 null，
  调用方按「code 为 0 但没 data」去归因，结论必然是错的。
  响应体解析一律走 `WorkBuddyApi.parseJson`（空 / 非 JSON 返回 null，由调用方如实报）

### 实测记录

- **从 Trae CN 客户端源码读到了它签到时实际发什么**（`D:\Program Files\Trae CN\resources\app\`
  未打包，`out/main.js` 里签到走 `eb()`，加设备头在 `fb()`）：

  | 头 | 取值来源 |
  | --- | --- |
  | `Authorization` | `Cloud-IDE-JWT <token>` |
  | `x-device-id` | `guaranteedDeviceId`（即 `storage.json` 里 `icube-dc` 的 16 位数字） |
  | `x-device-brand` | `commonParams.device_model` |
  | `x-device-type` | `commonParams.os_name` |
  | `x-os-version` | `commonParams.os_version` |
  | `x-app-version` | `commonParams.app_version` = `product.json.appVersion`（`3.3.102`） |

  body 为 `{"req_source":1}`（IDE=1 / Lite=2），客户端自身另带 `retry:2`。
  **客户端不发** `X-User-Region`、**不发** `Trae/...` 这种自造 UA，
  也没有 `x-machine-id`（该项只出现在别的接口上）——我们这边多发了前两个、少了 `x-device-brand`
- **服务端「签到奖励」奖励包的 `start_time` 就是那一次签到的发生时刻**（`entitlement_id`
  形如 `checkin_YYYYMMDD_<uid>`，按天一个包）——查「到底几点签的」用它最可靠。
  2026-09-17 实测 `checkin_20260917` 的 `start_time` = **19:46:57**
- **2026-09-17 的签到是 Trae 桌面客户端自己完成的，不是本 App**：用户确认当天安卓端
  已卸载、未运行，是客户端自动更新后自己签的。
  （最初按 30 分钟重试网格反推，得出「App 重试链熬到傍晚才签成」，前提错误，该结论作废）
- **✅ `9074` 的真正成因查清了：设备号必须是客户端注册过的那个（推翻旧结论）**
  - 2026-09-17 对照实验：同一账号、同一套请求头、同一分钟，**只换 `x-device-id`**

    | `x-device-id` | `status` 返回 | `claim` 返回 |
    | --- | --- | --- |
    | 真实 16 位 Aha 号 | `did_checked_in=true` | **`9095` 业务响应** |
    | 真实号 + `-<userId>`（拼接） | `did_checked_in=false` | **`9074` 限流** |

  - 结论：**`9074` 与设备号取值强相关**——只有注册过的真实设备号才会被正常处理。
    此前「`9074` 与设备号无关」的结论**是错的**：当时试的全是**伪造号**
    （UUID、乱造 16 位、拼接串），**从没试过真实的那个**
  - 顺带确认语义：`did_checked_in` 是**设备口径**（该设备今日是否已领），
    `checked_in` 是**账号口径**（该账号今日是否已签）
- **⚠️ 平台限制：同一设备每天只有第一个账号能签成**
  - 设备号相同的多账号会吃 `9095`；而**伪造设备号绕过是行不通的**（伪造即 `9074`）
  - 即「单机单设备号」下多账号签到天然只能成功一个。这是平台策略，不是 App 的缺陷
- **✅ 首次实测签成（2026-09-17 21:00，本项目的请求格式被验证有效）**
  - 同一个账号、同一套请求头，只换**设备号**：
    上一台电脑的设备号 → `9095`（该设备今日名额已被另一个账号用掉）；
    另一台电脑的**真实注册设备号** → **`code=0 success`**，复核 `checked_in` 变 `true`
  - 也就是说：**只要能拿到该账号所在机器那个「注册过的 16 位设备号」，本项目就能签成**，
    9074 的症结确实在设备号；`X-User-Region`、自造 UA 这些差异**并未导致失败**
  - 使用推论：**每个账号要配它自己那台机器的设备号**；同一台机器提取的多个账号，
    每天只有第一个能签成（其余换机器才有名额）
- `status` / `claim` 均可正常工作；**已签到状态下 `claim` 幂等返回 `code=0`（响应无积分字段）**
- `exchangeToken` 幂等：重复调用返回同一个 Token，传入的 refreshToken 不轮换，只顺延到期时间

## [1.3.0] · 2026-09-16

### 变更

- **限流重试链重构：固定 30 分钟自排，替代 `Result.retry()`**
  - WorkManager 自带 `BackoffPolicy.LINEAR` 的真实语义是「间隔 × 轮次」（30min → 60min →
    90min…），第 9 轮就被推到次日 03:00、32 轮累计约 11 天，且每天再开一条新链多链并行放大请求量
  - 改为 Worker 失败后调用 `DailySchedule.enqueueRetry` 自排 30 分钟后的下一轮：
    9:00-10:00 落点起严格每 30 分钟一轮，成功即停，最晚到次日 01:00 前后，
    覆盖凌晨 00:15~00:45 低峰窗口（参考实现实测全天最容易签成的时段）
  - 重试链唯一名带链首日期（`{kind}_{date}_retry`），跨天重试不换名、各天的链互不串扰；
    兜底补签的状态去重也会检查重试链是否仍在队列中
- **删除 Trae「9074 换新设备号重签」机制（实测证伪）**
  - 参考 Trae-AutoCheckin 声称「反复 9074 = 设备号被记住，换新号立刻能签成」，但本机
    2026-09-16 两次独立实测证伪：上午乱造设备号仍 9074，下午 3 个从未出现过的全新
    16 位设备号也全部 9074——换头 / 换 body / 换设备号都绕不开，9074 就是服务端
    「参与用户太多」的容量闸门
  - 该机制还会把新号持久化覆盖 `acc.device`，导致客户端真实设备号丢失；频繁更换设备号
    本身也是风控高危信号。保留每轮 1 次 claim + 全天 30 分钟节奏熬低峰
- **WorkBuddy Token 自动续期**（参考同作者的 [L0NE-6/WorkBuddy-Daily](https://github.com/L0NE-6/WorkBuddy-Daily)，
  推翻 1.2.0「WorkBuddy 无已验证刷新接口」的结论）
  - 新增 `WorkBuddyApi`：官方插件接口 `POST /v2/plugin/auth/token/refresh` 续期，
    凭据走 `X-Refresh-Token` + `X-Auth-Refresh-Source: plugin` 头（不占 Authorization）；
    **每次续期 refreshToken 会轮换**，新值自动写回账号存储形成续期链
  - 智能续期节奏对齐参考实现：accessToken 距过期不足 7 天才主动刷新，其余等鉴权失败再刷
    （多处同时用同一 RT 刷新会互相顶掉对方，不无脑天天刷）
  - Worker / 查询积分在鉴权失败（HTTP 401/403 或业务码/文案命中）时自动续期后重试一次，
    仍失败才提示「自动刷新被拒，请重新提取」；此前直接报 Token 失效
  - 续期响应的新 RT 兼容 `refreshToken` / `refresh_token` 两种字段名，防止服务端换命名
    导致拿到空串、回落到已作废的旧 RT
  - `extract_tokens.py` 开始提取 `wb_refresh`；导入 JSON 覆盖更新时保留原 RT（字段为空不覆盖）
  - 注意：同一账号的凭据文件只在一处使用，App 内轮换后桌面端旧 RT 会失效（重新提取即可同步）；
    反过来，续期正常时也别随意重贴桌面端旧快照，会把 App 的最新 RT 覆盖回旧值
- **WorkBuddy 签到规则对齐参考实现的社区口径**
  - billing 签到成功码放宽为 `0` / `200`（参考实现实测两种都出现）
  - 签到与状态接口改用 `postRaw` 保留 HTTP 状态码，鉴权失败可被精确识别并触发续期，
    不再把「Token 过期」混进「值得重试」的轮次里空转
- **Trae 签到请求形态对齐社区验证有效的最小头集合**（参考 [L0NE-6/Trae-AutoCheckin](https://github.com/L0NE-6/Trae-AutoCheckin) 的 `ug_headers`）
  - `TraeApi` 请求头精简为 `Authorization` + `X-User-Region: CN` + UA `Trae/1.107.1` +
    `x-device-id` / `x-device-type` / `x-os-version` / `x-app-version`；此前按桌面端抓包对齐的
    网关头（`x-lscbd-*` / `x-lgw-*` / `Package-Type` / 双版本头）没有收益，
    且同一头出现大小写变体会被风控判为异常客户端
- **Trae `9095`（裸业务码「已签」）加 status 复核**：该码来自参考仓库口径、本机未实证，
  为防把失败码误报成「已签到」，命中时用 status 复核 `checked_in` 确认（文案明说「已签到」的
  仍直接采信）
- **`9004` 重新归类**：实测它是缺 `x-device-id` 的**参数错误**而非鉴权失效，移出
  `AUTH_CODES`——此前会误报「Token 失效」并白白刷一次 Token
- **修复 `registerDaily` 主线程阻塞**：`enqueue` 与 `getWorkInfos...get()` 都是阻塞数据库操作，
  移入后台线程执行，规避 StrictMode / ANR 风险
- **Trae `enable=false` 不再静默**：服务端明确未开放签到的账号跳过 claim，但照常通知
  「未开放签到」，用户不再莫名少一条通知也不知道今天没签
- `versionCode` 3 → 4、`versionName` → 1.3.0（此前忘改，覆盖安装会报「应用未安装」）

## [1.2.0] · 2026-09-16

本次集中修四个实测出来的问题：Trae 手动签到被服务端限流卡住、WorkBuddy 连续天数算错、
同一账号重复粘贴却新增账号、签到时间不可控且没有补签。

### 修复

- **Trae「当前参与用户太多，请稍后重试」不再当成失败或 Token 失效**
  - 该提示对应错误码 `9074`，是服务端**限流/过载**（官方论坛多人反馈高峰期必现），不是账号问题
  - `TraeApi` 新增 `TraeStatus` / `TraeClaim` 结果类型，把「限流」「鉴权失效」「其它业务失败」彻底分开；
    此前任何 `code != 0` 都被当作鉴权失败，会误报「Token 失效，自动刷新被拒绝」
  - 限流时两级重试：本次运行内间隔随机 15\~30 秒重试 2 次（对齐社区实测有效区间），
    仍失败则 `Result.retry()` 交给 WorkManager 以 5 分钟起步的指数退避继续，最多 3 轮
  - 失败通知：定时签到只在「不再重试」的那一轮发出，避免退避重试把通知刷屏；
    **手动签到（App 内按钮）每个账号的结果当场通知、失败不进入退避**，方便导入 Token 后立即验证
  - 兼容 `success: true` 与 `code == 200` 两种成功口径；识别「已签到 / 明日再来 / 已领取」文案为成功（幂等），
    但明确排除带「设备」字样的设备级去重拦截
  - `1001` 仍然按本项目的实测结论归为**鉴权失效**，没有照抄社区把 `1001` 当「已签到」的写法
- **WorkBuddy 连续签到天数计算错误**（⚠️ 本节做法在 1.4.0 已整体废弃；
  1.4.0 换的成长中心口径也在当晚被推翻、1.4.1 已删除——**如今已无任何连签计算**）
  - 根因：`streak_days` 与 `checkin_dates` **都只覆盖本期活动**，活动换期会一起归零
    （实测 2026-09-16 活动「高校新生攻略」season 9 当天开跑，两个字段都只剩 1 天），
    所以只按服务端日期回溯同样得不到「真正连续多少天」
  - 改为把服务端日期**并入本地持久化的签到日期集合**（DataStore，按账号存，最多留 400 天）再回溯：
    跨活动期依然连续，真断签依然会断
  - 「今天」一律按**东八区**判定（此前用设备本地时区，半夜或时区异常时会整体错一天）
  - `checkin_dates` 缺失时退回服务端的 `streak_days`；此前直接 `return 0`，字段一没就显示「连续 0 天」
  - 日期解析兼容 ISO / `yyyy/MM/dd` / 带时间后缀 / `yyyyMMdd` / 秒与毫秒时间戳
  - 领取成功后也会把当天写进历史并显示连续天数（此前新签当天只显示积分、看不到天数）
  - 「查询积分」同时展示本地连续天数与服务端本期累计天数，两者可对照核对
- **重复导入同一账号会新增账号，而不是覆盖原账号**
  - 根因：判重只比对 `accessToken` / `refreshToken` 字符串是否相等，
    而 Token 过期后重新提取必然是新字符串（Trae 的 refreshToken 还会轮换），于是同一个账号被当成新账号追加
  - 新增 `Jwt.kt` 解析 Token payload，取稳定身份标识匹配：Trae `data.id`、WorkBuddy `sub`
    （退化为 `preferred_username`，再退化为 Token 摘要），Token 怎么轮换都认得出来
  - 身份键在读取时实时计算，老库里的历史账号无需数据迁移同样能被匹配
  - 导入提示区分「已添加」与「已更新」，不再一律显示「已添加」
- **签到时间改为东八区 09:00-10:00，并补齐补签**

### 变更

- **定时排程由 `PeriodicWorkRequest` 改为一次性任务自链**（`DailySchedule`）
  - 周期任务的弹性窗口固定在周期末尾（`[T-flex, T]`），而首次执行的 `T` 由 `initialDelay + period` 决定，
    导致「窗口落在 9-10 点」和「今天就要跑一次」无法同时成立——对齐 10:00 就会把首次执行推迟一整天，天天漏一天
  - 改为每次跑完再排下一天，唯一任务名带目标日期（重复排程幂等），窗口内随机取落点错峰
  - 启动时取消 1.1.x 的 `trae_checkin` / `workbuddy_checkin` 周期任务，避免新旧排程重复执行
  - 补签三层兜底：约束不满足时 WorkManager 挂在队列里等条件恢复；限流走指数退避；
    后台被 ROM 清掉时开 App 在「今日落点已过且当天任务没跑过」时补跑一次（静默，已签不打扰）
- 账号间隔由固定 3 秒改为 **3 秒 + 0\~2 秒抖动**（参考实现 trae-check 用固定 2 秒，此处更保守）；
  限流来自服务端整体负载，加大间隔并不能消除，真正起作用的是错峰与重试
- Trae 多账号支持**按账号区分 `X-Device-Id`**（拼用户 ID），规避 claim 接口的「一设备一天一次」去重；
  仅用于 claim，status / 积分 / 刷新仍用原始 deviceId，且带后缀被拒时会自动退回原始值重试
- `ApiClient` 保留 HTTP 状态码（`postRaw`），便于把 429 / 5xx 识别为「值得重试」而非鉴权失败
- `versionCode` 2 → 3、`versionName` → 1.2.0

### 踩坑记录

两平台接口逆向过程中实测出的关键细节，避免后人重蹈覆辙。

#### Trae

- 认证头是 `Authorization: Cloud-IDE-JWT <token>`，**不是** `Bearer`；并且必须携带 `X-Device-Id` 头（缺失返回 `9004`）
- 签到接口一律返回 HTTP 200，**成败要看响应体里的** **`code`** **字段**（`0` 表示成功）
- **`9074` =「当前参与用户太多，请稍后重试」**：这是服务端**限流/过载**，不是 Token 或账号问题。
  官方论坛上多人反馈高峰期必现，社区做法是错峰 + 随机 15\~30 秒重试若干次。
  所以签到状态查询与「Token 失效」必须区分开——把 `9074` 当成鉴权失败会让 App 误报「请重新提取 Token」
- **`1001` 是本项目的鉴权失效码，不是「已签到」**（部分社区实现把 `1001` 当已签到，直接照抄会把 Token 过期误报成签到成功）
- **设备级去重**：claim 接口按 `X-Device-Id` 做「一设备一天一次」，
  同机导入多个 Trae 账号时会互相挤掉。社区方案（trae-check）是在 deviceId 后拼用户 ID 让它们看起来像不同设备；
  本项目**只对 claim 用拼后缀的 deviceId**，status / 积分 / 刷新仍用原始 deviceId，
  万一带后缀被拒还会自动退回原始 deviceId 重试一次
- `ExchangeToken` 的 `ClientID` 必须与 Token 来源客户端匹配：
  Trae CN 桌面端 stable 为 `ono9krqynydwx5`，SOLO 版为 `en1oxy7wnw8j9n`。
  用错会返回 `refresh token is not matched to the client`。
  完整的 ClientID 表见 Trae 安装目录下 `resources/app/product.json` 的 `iCubeApp.authConfig`
- 刷新接口是**幂等**的：对同一个 refreshToken 重复调用，只会返回同一会话的新 accessToken，
  旧 refreshToken 不会作废，桌面端登录态也不受影响

#### WorkBuddy

- `checkin-activity-status` 的 `streak_days` 与 `checkin_dates` **都只覆盖本期活动**，
  断签不清零、换期却会一起归零。实测（2026-09-16，活动「高校新生攻略」season 9 当天开跑）：

  ```json
  { "today_checked_in": true, "streak_days": 1, "today_credit": 100,
    "checkin_dates": ["2026-09-16"],
    "start_time": "2026-09-16 00:00:00", "activity_name": "高校新生攻略", "season": 9 }
  ```

  也就是说，只靠 `checkin_dates` 回溯同样算不出「真正连续多少天」——
  活动一换期就归零。1.2.0 起改为把服务端日期**并入本地持久化的日期集合**再回溯：
  换期不再清零，真断签依然会断
  - **（已被 1.4.0 废弃，且那条「权威接口」的说法本身也是错的）** 当时以为成长中心有权威接口
    `GET /v2/activity/growth/streak`、其 `streak.days` 跨赛季连续，于是 1.4.0 直接改成调它。
    **2026-09-17 用户更正：那个 `days` 是「连续登录 PC 端」的天数，跟签到无关**
    （同一分钟实测：两个账号 `checkin_dates` 都是 2 天，成长中心一个 `days=7`、一个 `days=0`），
    该调用当晚已整体删除。**结论：签到侧没有「连续」字段，App 也不显示连续天数**——
    这里保留原委，是为了说明这段弯路是怎么走出来的，别再照着它恢复成长中心这条路
- 「今天」必须按**东八区**判定。用设备本地时区算，在半夜或时区设置异常时会整体错一天
- 从签到接口的 `daily-checkin` 判定结果更可靠：签到状态的 `today_checked_in` 在活动未开始
  （`active=false`）时不可信，社区实现因此改成「直接调签到接口，按响应判定」

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

[Unreleased]: https://github.com/Mumeione/ai-credits-auto-checkin/compare/v1.4.1...HEAD
[1.4.1]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.4.1
[1.4.0]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.4.0
[1.3.0]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.3.0
[1.2.0]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.2.0
[1.1.1]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.1.1
[1.0.0]: https://github.com/Mumeione/ai-credits-auto-checkin/releases/tag/v1.0.0
