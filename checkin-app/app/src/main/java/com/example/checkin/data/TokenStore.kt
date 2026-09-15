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
    val lastResult: String = "",
)

/**
 * 多账号凭据存储：按平台各存一个 JSON 数组，账号名自动编号（账号1、账号2……）。
 * 旧版单账号扁平 key 在首次访问时一次性迁移为一个账号。
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

    /** 追加账号（access/refresh 与已有账号相同则覆盖更新并保留原名），返回账号名。*/
    suspend fun addTraeAccount(access: String, refresh: String, device: String): String {
        val current = getTraeAccounts()
        val idx = current.indexOfFirst {
            (access.isNotBlank() && it.access == access) ||
                (refresh.isNotBlank() && it.refresh.isNotBlank() && it.refresh == refresh)
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
            return updated.name
        }
        val name = nextName(current.map { it.name })
        saveTraeAccounts(
            current + TraeAccount(UUID.randomUUID().toString(), name, access, refresh, device)
        )
        return name
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
                lastResult = o.optString("last_result"),
            )
        }
    }

    /** 追加账号（token 相同则覆盖更新并保留原名），返回账号名。*/
    suspend fun addWbAccount(token: String, domain: String): String {
        val current = getWbAccounts()
        val idx = current.indexOfFirst { token.isNotBlank() && it.token == token }
        if (idx >= 0) {
            val old = current[idx]
            val updated = old.copy(
                token = token.ifBlank { old.token },
                domain = domain.ifBlank { old.domain },
                lastResult = "",
            )
            saveWbAccounts(current.toMutableList().apply { set(idx, updated) })
            return updated.name
        }
        val name = nextName(current.map { it.name })
        saveWbAccounts(current + WbAccount(UUID.randomUUID().toString(), name, token, domain))
        return name
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
                    .put("last_result", a.lastResult)
            )
        }
        context.dataStore.edit { it[WB_ACCOUNTS] = arr.toString() }
    }

    // ---------- 命名 ----------

    /** 账号名续号：取现有"账号N"的最大 N+1，避免删除中间账号后重名。*/
    private fun nextName(names: List<String>): String {
        val max = names.mapNotNull {
            Regex("^账号(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
        return "账号${max + 1}"
    }
}
