package com.zima26.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ajustes")

/**
 * Guarda y lee los ajustes del usuario con DataStore.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val TEXT = stringPreferencesKey("texto_borde")
        val SHOW_TEXT = booleanPreferencesKey("mostrar_texto")
        val SHOW_DATE = booleanPreferencesKey("mostrar_fecha")
        val WEAR = stringPreferencesKey("desgaste")
        val TONE = stringPreferencesKey("tono")
        val SHOW_BORDER = booleanPreferencesKey("mostrar_borde")
        val SAVE_ORIGINAL = booleanPreferencesKey("guardar_original")
        val FOLDS = booleanPreferencesKey("pliegues")
        val STAINS = booleanPreferencesKey("manchas")
        val DOG_EAR = booleanPreferencesKey("esquina_doblada")
        val TAPE = booleanPreferencesKey("cinta")
    }

    val settings: Flow<FilterSettings> = context.dataStore.data.map { p ->
        FilterSettings(
            borderText = p[Keys.TEXT] ?: FilterSettings.DEFAULT_TEXT,
            showText = p[Keys.SHOW_TEXT] ?: true,
            showDate = p[Keys.SHOW_DATE] ?: false,
            wear = p[Keys.WEAR]?.let { runCatching { WearLevel.valueOf(it) }.getOrNull() }
                ?: WearLevel.NORMAL,
            tone = p[Keys.TONE]?.let { runCatching { PhotoTone.valueOf(it) }.getOrNull() }
                ?: PhotoTone.SEPIA,
            showBorder = p[Keys.SHOW_BORDER] ?: true,
            saveOriginal = p[Keys.SAVE_ORIGINAL] ?: false,
            folds = p[Keys.FOLDS] ?: false,
            stains = p[Keys.STAINS] ?: false,
            dogEar = p[Keys.DOG_EAR] ?: false,
            tape = p[Keys.TAPE] ?: false
        )
    }

    suspend fun setBorderText(text: String) = context.dataStore.edit { it[Keys.TEXT] = text }
    suspend fun setShowText(show: Boolean) = context.dataStore.edit { it[Keys.SHOW_TEXT] = show }
    suspend fun setShowDate(show: Boolean) = context.dataStore.edit { it[Keys.SHOW_DATE] = show }
    suspend fun setWear(wear: WearLevel) = context.dataStore.edit { it[Keys.WEAR] = wear.name }
    suspend fun setTone(tone: PhotoTone) = context.dataStore.edit { it[Keys.TONE] = tone.name }
    suspend fun setShowBorder(show: Boolean) = context.dataStore.edit { it[Keys.SHOW_BORDER] = show }
    suspend fun setFolds(on: Boolean) = context.dataStore.edit { it[Keys.FOLDS] = on }
    suspend fun setStains(on: Boolean) = context.dataStore.edit { it[Keys.STAINS] = on }
    suspend fun setDogEar(on: Boolean) = context.dataStore.edit { it[Keys.DOG_EAR] = on }
    suspend fun setTape(on: Boolean) = context.dataStore.edit { it[Keys.TAPE] = on }
    suspend fun setSaveOriginal(save: Boolean) = context.dataStore.edit { it[Keys.SAVE_ORIGINAL] = save }
}
