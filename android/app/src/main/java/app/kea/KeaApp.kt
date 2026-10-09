package app.kea

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.kea.service.Notifications

class KeaApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Created up front so a geofence alert raised from a cold start still
        // has somewhere to go.
        Notifications.createChannels(this)

        // Per-app language. Persisted choice wins; otherwise follow the system.
        val stored = runCatching {
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        }.getOrNull()

        AppCompatDelegate.setApplicationLocales(
            stored?.takeIf { it.isNotBlank() }
                ?.let { LocaleListCompat.forLanguageTags(it) }
                ?: LocaleListCompat.getEmptyLocaleList(),
        )
    }

    companion object {
        const val PREFS = "kea_locale"
        const val KEY_LANGUAGE = "language_tag"

        /** Persist a language choice, or "" to follow the system. */
        fun persistLanguage(context: android.content.Context, tag: String) {
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_LANGUAGE, tag).apply()
            AppCompatDelegate.setApplicationLocales(
                if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag),
            )
        }
    }
}