package com.geoffchan.glucosewidget

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "glucose")

data class Settings(val lowMmol: Double, val highMmol: Double)

/** Cached state + settings. Credentials live in EncryptedSharedPreferences. */
object Store {
    private val KEY_MGDL = intPreferencesKey("mgdl")
    private val KEY_TS = longPreferencesKey("timestampMs")
    private val KEY_TREND = stringPreferencesKey("trend")
    private val KEY_LOW = doublePreferencesKey("lowMmol")
    private val KEY_HIGH = doublePreferencesKey("highMmol")

    const val DEFAULT_LOW = 3.9
    const val DEFAULT_HIGH = 10.0

    suspend fun saveReading(context: Context, r: Reading) {
        context.dataStore.edit {
            it[KEY_MGDL] = r.mgdl
            it[KEY_TS] = r.timestampMs
            it[KEY_TREND] = r.trend
        }
    }

    suspend fun reading(context: Context): Reading? {
        val p = context.dataStore.data.first()
        val mgdl = p[KEY_MGDL] ?: return null
        return Reading(mgdl, p[KEY_TS] ?: 0L, p[KEY_TREND] ?: "None")
    }

    suspend fun saveSettings(context: Context, s: Settings) {
        context.dataStore.edit {
            it[KEY_LOW] = s.lowMmol
            it[KEY_HIGH] = s.highMmol
        }
    }

    suspend fun settings(context: Context): Settings {
        val p = context.dataStore.data.first()
        return Settings(p[KEY_LOW] ?: DEFAULT_LOW, p[KEY_HIGH] ?: DEFAULT_HIGH)
    }

    private val KEY_LAST_MED = stringPreferencesKey("lastMed")

    suspend fun lastMedication(context: Context): String =
        context.dataStore.data.first()[KEY_LAST_MED] ?: "short-acting"

    suspend fun saveLastMedication(context: Context, name: String) {
        context.dataStore.edit { it[KEY_LAST_MED] = name }
    }

    private val KEY_ROUTINE_DAY = stringPreferencesKey("routineLoggedDay")

    /** Local date ("YYYY-MM-DD") the morning routine was last auto-logged for. */
    suspend fun routineLoggedDay(context: Context): String? =
        context.dataStore.data.first()[KEY_ROUTINE_DAY]

    suspend fun saveRoutineLoggedDay(context: Context, day: String) {
        context.dataStore.edit { it[KEY_ROUTINE_DAY] = day }
    }

    // --- credentials + session ---

    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context,
        "credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun saveCredentials(context: Context, username: String, password: String) {
        securePrefs(context).edit().putString("u", username).putString("p", password).remove("s").apply()
    }

    fun credentials(context: Context): Pair<String, String>? {
        val p = securePrefs(context)
        val u = p.getString("u", null) ?: return null
        val pw = p.getString("p", null) ?: return null
        return u to pw
    }

    fun saveSession(context: Context, session: String) {
        securePrefs(context).edit().putString("s", session).apply()
    }

    fun session(context: Context): String? = securePrefs(context).getString("s", null)
}
