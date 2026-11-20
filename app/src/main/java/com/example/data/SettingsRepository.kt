package com.example.data

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "landrop_settings"
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_NAME = stringPreferencesKey("server_name")
        val PORT = intPreferencesKey("port")
        val PIN_ENABLED = booleanPreferencesKey("pin_enabled")
        val ACCESS_PIN = stringPreferencesKey("access_pin")
        val ALLOW_UPLOADS = booleanPreferencesKey("allow_uploads")
        val COOL_MODE = booleanPreferencesKey("cool_mode")
    }

    private val defaultServerName: String
        get() {
            val model = Build.MODEL?.trim().orEmpty()
            return if (model.isNotBlank()) "$model LanDrop" else "LanDrop Server"
        }

    val configFlow: Flow<ServerConfig> = context.settingsDataStore.data.map { prefs ->
        ServerConfig(
            serverName = prefs[Keys.SERVER_NAME] ?: defaultServerName,
            port = (prefs[Keys.PORT] ?: 8080).coerceIn(1024, 65535),
            pinEnabled = prefs[Keys.PIN_ENABLED] ?: false,
            accessPin = prefs[Keys.ACCESS_PIN] ?: "2468",
            allowBrowserUploads = prefs[Keys.ALLOW_UPLOADS] ?: true,
            coolMode = prefs[Keys.COOL_MODE] ?: false,
        )
    }

    suspend fun saveConfig(config: ServerConfig) {
        val sanitizedName = config.serverName.trim().ifBlank { defaultServerName }
        val validPort = config.port.coerceIn(1024, 65535)
        val validPin = config.accessPin.filter { it.isDigit() }.take(6).ifBlank { "2468" }

        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SERVER_NAME] = sanitizedName
            prefs[Keys.PORT] = validPort
            prefs[Keys.PIN_ENABLED] = config.pinEnabled
            prefs[Keys.ACCESS_PIN] = validPin
            prefs[Keys.ALLOW_UPLOADS] = config.allowBrowserUploads
            prefs[Keys.COOL_MODE] = config.coolMode
        }
    }
}
