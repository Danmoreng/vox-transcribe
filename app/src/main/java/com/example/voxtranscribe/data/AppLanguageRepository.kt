package com.example.voxtranscribe.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class AppLanguage(val localeTag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    GERMAN("de"),
}

@Singleton
class AppLanguageRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _appLanguage = MutableStateFlow(readAppLanguage())
    val appLanguage: StateFlow<AppLanguage> = _appLanguage.asStateFlow()

    suspend fun setAppLanguage(language: AppLanguage) {
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(KEY_APP_LANGUAGE, language.name)
                .apply()
            _appLanguage.value = language
        }
    }

    private fun readAppLanguage(): AppLanguage {
        val storedValue = prefs.getString(KEY_APP_LANGUAGE, null) ?: return AppLanguage.SYSTEM
        return AppLanguage.entries.firstOrNull { it.name == storedValue } ?: AppLanguage.SYSTEM
    }

    companion object {
        const val PREFS_NAME = "app_language"
        const val KEY_APP_LANGUAGE = "app_language"

        fun readStoredLanguage(context: Context): AppLanguage {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val storedValue = prefs.getString(KEY_APP_LANGUAGE, null) ?: return AppLanguage.SYSTEM
            return AppLanguage.entries.firstOrNull { it.name == storedValue } ?: AppLanguage.SYSTEM
        }
    }
}
