package com.example.checkin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

data class TraeAccount(
    val id: String,
    val name: String,
    val access: String,
    val refresh: String,
    val device: String,
    val lastResult: String = "",
)

data class WbAccount(
    val id: String,
    val name: String,
    val token: String,
    val domain: String,
    /** refreshToken：插件接口续期凭据，每次续期会轮换，必须写回（见 WorkBuddyApi）。 */
    val refresh: String = "",
    val lastResult: String = "",
)

/** 导入结果：区分「新增」与「覆盖更新」，UI 据此给出准确提示。 */
enum class ImportOutcome { ADDED, UPDATED }

data class ImportResult(val name: String, val outcome: ImportOutcome)

/**
 * 多账号凭据存储：按平台各存一个 JSON 数组，账号名自动编号（账号1、账号2……）。
 * 旧版单账号扁平 key 在首次访问时一次性迁移为一个账号。
 *
 * 「同一个账号」的判定（1.2.0 修正）：
 * 不再比对 accessToken / refreshToken 字符串是否相等——Token 一过期重新提取必然是新字符串，
 * 那样只会把同一个账号重复追加。改为比对 [Jwt] 解析出的稳定用户标识
 * （Trae `data.id` / WorkBuddy `sub`），Token 怎么轮换都能认出来并覆盖更新。
 */
class TokenStore(private val context: Context) {

    companion object {
        private val TRAE_ACCOUNTS = stringPreferencesKey("trae_accounts_json")
        private val WB_ACCOUNTS = stringPreferencesKey("wb_accounts_json")

        // 旧版单账号 key，仅用于迁移
        private val LEGACY_TRAE_ACCESS = stringPreferencesKey("trae_access_token")
        private val LEGACY_TRAE_REFRESH = stringPreferencesKey("trae_refresh_token")
        private val LEGACY_TRAE_DEVICE = stringPreferencesKey("trae_device_id")
        private val LEGACY_WB_TOKEN = stringPreferencesKey("wb_access_token")
        private val LEGACY_WB_DOMAIN = stringPreferencesKey("wb_domain")
    }

    // ---------- 迁移 ----------

    private suspend fun ensureMigrated() {
        context.dataStore.edit { prefs ->
            if (!prefs.contains(TRAE_ACCOUNTS)) {
                val access = prefs[LEGACY_TRAE_ACCESS] ?: ""
                if (access.isNotBlank()) {
                    val arr = JSONArray().put(
                        JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("name", "账号1")
                            .put("access", access)
                            .put("refresh", prefs[LEGACY_TRAE_REFRESH] ?: "")
                            .put("device", prefs[LEGACY_TRAE_DEVICE] ?: "")
                            .put("last_result", "")
                    )
                    prefs[TRAE_ACCOUNTS] = arr.toString()
                }
                prefs.remove(LEGACY_TRAE_ACCESS)
                prefs.remove(LEGACY_TRAE_REFRESH)
                prefs.remove(LEGACY_TRAE_DEVICE)
            }
            if (!prefs.contains(WB_ACCOUNTS)) {
                val token = prefs[LEGACY_WB_TOKEN] ?: ""
                if (token.isNotBlank()) {
                    val arr = JSONArray().put(
                        JSONObject()
                            .put("id", UUID.randomUUID().toString())
                            .put("name", "账号1")
                            .put("token", token)
                            .put("domain", prefs[LEGACY_WB_DOMAIN] ?: "")
                            .put("last_result", "")
                    )
                    prefs[WB_ACCOUNTS] = arr.toString()
                }
                prefs.remove(LEGACY_WB_TOKEN)
                prefs.remove(LEGACY_WB_DOMAIN)
            }
        }
    }

    // ---------- 账号身份键 ----------

    /**
     * Trae：优先用 accessToken 里的用户 ID；解析不出来再退到 refreshToken 摘要，
     * 最后退到 accessToken 摘要（此时 Token 一换就认不出，与旧行为一致）。
     *
     * 注意这里是**读取时实时计算**，所以老库里没有身份键的历史账号同样能算出键，
     * 不需要额外的数据迁移。
     */
    private fun traeKeyOf(access: String, refresh: String): String? {
        Jwt.traeUserId(access)?.let { return "trae:uid:$it" }
        if (refresh.isNotBlank()) return "trae:rf:" + digest(refresh)
        if (access.isNotBlank()) return "trae:tok:" + digest(access)
        return null
    }

    private fun wbKeyOf(token: String): String? {
        Jwt.wbUserId(token)?.let { return "wb:uid:$it" }
        if (token.isNotBlank()) return "wb:tok:" + digest(token)
        return null
    }

    /** Token 已经是敏感数据，这里只取摘要，且不落盘，仅用于同一进程内的匹配。 */
    private fun digest(value: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(24)
    } catch (e: Exception) {
        value.hashCode().toString()
    }

    // ---------- Trae ----------

    suspend fun getTraeAccounts(): List<TraeAccount> {
        ensureMigrated()
        val raw = context.dataStore.data.first()[TRAE_ACCOUNTS] ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TraeAccount(
                id = o.optString("id"),
                name = o.optString("name"),
                access = o.optString("access"),
                refresh = o.optString("refresh"),
                device = o.optString("device"),
                lastResult = o.optString("last_result"),
            )
        }
    }

    /**
     * 追加账号；若判定为同一账号（稳定身份键相同，或退一步 Token 完全相同）
     * 则**覆盖更新并保留原编号与账号名**。
     */
    suspend fun addTraeAccount(access: String, refresh: String, device: String): ImportResult {
        val current = getTraeAccounts()
        val newKey = traeKeyOf(access, refresh)
        val idx = current.indexOfFirst { existing ->
            val oldKey = traeKeyOf(existing.access, existing.refresh)
            (newKey != null && oldKey != null && newKey == oldKey) ||
                (access.isNotBlank() && existing.access == access) ||
                (refresh.isNotBlank() && existing.refresh.isNotBlank() && existing.refresh == refresh)
        }
        if (idx >= 0) {
            val old = current[idx]
            val updated = old.copy(
                access = access.ifBlank { old.access },
                refresh = refresh.ifBlank { old.refresh },
                device = device.ifBlank { old.device },
                lastResult = "",
            )
            saveTraeAccounts(current.toMutableList().apply { set(idx, updated) })
            return ImportResult(updated.name, ImportOutcome.UPDATED)
        }
        val name = nextName(current.map { it.name })
        saveTraeAccounts(
            current + TraeAccount(UUID.randomUUID().toString(), name, access, refresh, device)
        )
        return ImportResult(name, ImportOutcome.ADDED)
    }

    suspend fun updateTraeAccount(account: TraeAccount) {
        val current = getTraeAccounts()
        val idx = current.indexOfFirst { it.id == account.id }
        if (idx >= 0) {
            saveTraeAccounts(current.toMutableList().apply { set(idx, account) })
        }
    }

    suspend fun removeTraeAccount(id: String) {
        saveTraeAccounts(getTraeAccounts().filterNot { it.id == id })
    }

    private suspend fun saveTraeAccounts(accounts: List<TraeAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id).put("name", a.name)
                    .put("access", a.access).put("refresh", a.refresh)
                    .put("device", a.device).put("last_result", a.lastResult)
            )
        }
        context.dataStore.edit { it[TRAE_ACCOUNTS] = arr.toString() }
    }

    // ---------- WorkBuddy ----------

    suspend fun getWbAccounts(): List<WbAccount> {
        ensureMigrated()
        val raw = context.dataStore.data.first()[WB_ACCOUNTS] ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            WbAccount(
                id = o.optString("id"),
                name = o.optString("name"),
                token = o.optString("token"),
                domain = o.optString("domain"),
                refresh = o.optString("refresh"),
                lastResult = o.optString("last_result"),
            )
        }
    }

    /** 追加账号；同一账号（身份键相同或 Token 相同）覆盖更新并保留原编号。 */
    suspend fun addWbAccount(token: String, domain: String, refresh: String = ""): ImportResult {
        val current = getWbAccounts()
        val newKey = wbKeyOf(token)
        val idx = current.indexOfFirst { existing ->
            val oldKey = wbKeyOf(existing.token)
            (newKey != null && oldKey != null && newKey == oldKey) ||
                (token.isNotBlank() && existing.token == token) ||
                (refresh.isNotBlank() && existing.refresh.isNotBlank() && existing.refresh == refresh)
        }
        if (idx >= 0) {
            val old = current[idx]
            val updated = old.copy(
                token = token.ifBlank { old.token },
                domain = domain.ifBlank { old.domain },
                refresh = refresh.ifBlank { old.refresh },
                lastResult = "",
            )
            saveWbAccounts(current.toMutableList().apply { set(idx, updated) })
            return ImportResult(updated.name, ImportOutcome.UPDATED)
        }
        val name = nextName(current.map { it.name })
        saveWbAccounts(current + WbAccount(UUID.randomUUID().toString(), name, token, domain, refresh))
        return ImportResult(name, ImportOutcome.ADDED)
    }

    suspend fun updateWbAccount(account: WbAccount) {
        val current = getWbAccounts()
        val idx = current.indexOfFirst { it.id == account.id }
        if (idx >= 0) {
            saveWbAccounts(current.toMutableList().apply { set(idx, account) })
        }
    }

    suspend fun removeWbAccount(id: String) {
        saveWbAccounts(getWbAccounts().filterNot { it.id == id })
    }

    private suspend fun saveWbAccounts(accounts: List<WbAccount>) {
        val arr = JSONArray()
        accounts.forEach { a ->
            arr.put(
                JSONObject()
                    .put("id", a.id).put("name", a.name)
                    .put("token", a.token).put("domain", a.domain)
                    .put("refresh", a.refresh)
                    .put("last_result", a.lastResult)
            )
        }
        context.dataStore.edit { it[WB_ACCOUNTS] = arr.toString() }
    }

    // ---------- WorkBuddy 签到日期历史 ----------
    //
    // 1.4.0 删除。
    // 这里原本把每天的签到日期攒在本地（`wb_checkin_history_json`），用于绕过
    // 「服务端 streak_days 只统计本期活动、换期归零」的问题。
    // 当时改成去问成长中心 `GET /v2/activity/growth/streak` 的 `streak.days`，
    // 以为它跨赛季连续——**这个定性当天就被用户推翻**：那是「连续登录 PC 端」的天数，
    // 不是连续签到（详见 WorkBuddyApi 类注释），该调用在 1.4.0 当晚一并删除。
    // 所以现在**没有任何**连签天数的持久化或计算：本地历史删了就不该加回来
    // （它只记「App 自己跑成功的那几天」会失真），成长中心也不该再当签到数据源。
    //
    // 老版本写在 DataStore 里的 `wb_checkin_history_json` 键不再读写；
    // 它是应用私有存储里的一小段死数据，留着无副作用，故意不做迁移清理（避免多余的写操作）。

    // ---------- 命名 ----------

    /** 账号名续号：取现有"账号N"的最大 N+1，避免删除中间账号后重名。 */
    private fun nextName(names: List<String>): String {
        val max = names.mapNotNull {
            Regex("^账号(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "账号${max + 1}"
    }
}
