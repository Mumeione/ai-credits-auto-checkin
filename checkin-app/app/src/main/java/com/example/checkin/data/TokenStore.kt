package com.example.checkin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

class TokenStore(private val context: Context) {

    companion object {
        val TRAE_ACCESS = stringPreferencesKey("trae_access_token")
        val TRAE_REFRESH = stringPreferencesKey("trae_refresh_token")
        val TRAE_DEVICE = stringPreferencesKey("trae_device_id")
        val WB_TOKEN = stringPreferencesKey("wb_access_token")
        val WB_DOMAIN = stringPreferencesKey("wb_domain")
    }

    suspend fun saveTraeToken(access: String, refresh: String, deviceId: String) {
        context.dataStore.edit { prefs ->
            if (access.isNotEmpty()) prefs[TRAE_ACCESS] = access
            if (refresh.isNotEmpty()) prefs[TRAE_REFRESH] = refresh
            if (deviceId.isNotEmpty()) prefs[TRAE_DEVICE] = deviceId
        }
    }

    suspend fun getTraeAccess(): String =
        context.dataStore.data.first()[TRAE_ACCESS] ?: ""

    suspend fun getTraeRefresh(): String =
        context.dataStore.data.first()[TRAE_REFRESH] ?: ""

    suspend fun getTraeDevice(): String =
        context.dataStore.data.first()[TRAE_DEVICE] ?: ""

    suspend fun saveWorkBuddyToken(token: String, domain: String) {
        context.dataStore.edit { prefs ->
            if (token.isNotEmpty()) prefs[WB_TOKEN] = token
            if (domain.isNotEmpty()) prefs[WB_DOMAIN] = domain
        }
    }

    suspend fun getWorkBuddyToken(): Pair<String, String> {
        val prefs = context.dataStore.data.first()
        return Pair(prefs[WB_TOKEN] ?: "", prefs[WB_DOMAIN] ?: "")
    }
}
