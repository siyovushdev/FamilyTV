package ru.familytv.parent

import android.content.Context
import android.content.SharedPreferences
import ru.familytv.interaction.HouseholdAgeBand
import ru.familytv.interaction.ReadingSupportMode
import java.security.MessageDigest
import java.security.SecureRandom

/** Local parent PIN — never sent anywhere. */
class ParentPinStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun isSet(): Boolean = prefs.contains(KEY_HASH)

    fun setPin(pin: String): Boolean {
        if (!pin.matches(Regex("\\d{4,6}"))) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, salt.joinToString("") { "%02x".format(it) })
            .putString(KEY_HASH, hash)
            .commit()
        return true
    }

    fun verify(pin: String): Boolean {
        val saltHex = prefs.getString(KEY_SALT, null) ?: return false
        val expected = prefs.getString(KEY_HASH, null) ?: return false
        val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return hash(pin, salt) == expected
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val PREFS = "familytv_parent_pin"
        private const val KEY_SALT = "salt"
        private const val KEY_HASH = "hash"
    }
}

data class ScreenTimeSettings(
    val enabled: Boolean = true,
    val dailyLimitMinutes: Int? = null,
    val sessionReminderMinutes: Int? = 20,
    val restReminderMinutes: Int? = 25,
    val bedtimeStartHour: Int? = null, // 0-23 local
    val bedtimeEndHour: Int? = null,
    val warnMinutesBefore: Int = 5,
)

data class FamilyContentFilter(
    val ageMin: Int? = null,
    val ageMax: Int? = null,
)

data class AccessibilitySettings(
    val narrationEnabled: Boolean = true,
    val largeText: Boolean = false,
    val highContrastFocus: Boolean = true,
    val captionsEnabled: Boolean = true,
    /** Independent of age filter — presentation preference for the household. */
    val readingSupportMode: ReadingSupportMode = ReadingSupportMode.AUDIO_AND_TEXT,
)

class FamilySettingsStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun screenTime(): ScreenTimeSettings = ScreenTimeSettings(
        enabled = prefs.getBoolean("st_enabled", true),
        dailyLimitMinutes = prefs.getInt("st_daily", -1).takeIf { it > 0 },
        sessionReminderMinutes = prefs.getInt("st_session", 20).takeIf { it > 0 },
        restReminderMinutes = prefs.getInt("st_rest", 25).takeIf { it > 0 },
        bedtimeStartHour = prefs.getInt("st_bed_start", -1).takeIf { it in 0..23 },
        bedtimeEndHour = prefs.getInt("st_bed_end", -1).takeIf { it in 0..23 },
        warnMinutesBefore = prefs.getInt("st_warn", 5).coerceIn(1, 15),
    )

    fun saveScreenTime(s: ScreenTimeSettings) {
        prefs.edit()
            .putBoolean("st_enabled", s.enabled)
            .putInt("st_daily", s.dailyLimitMinutes ?: -1)
            .putInt("st_session", s.sessionReminderMinutes ?: -1)
            .putInt("st_rest", s.restReminderMinutes ?: -1)
            .putInt("st_bed_start", s.bedtimeStartHour ?: -1)
            .putInt("st_bed_end", s.bedtimeEndHour ?: -1)
            .putInt("st_warn", s.warnMinutesBefore)
            .apply()
    }

    fun contentFilter(): FamilyContentFilter = FamilyContentFilter(
        ageMin = prefs.getInt("age_min", -1).takeIf { it > 0 },
        ageMax = prefs.getInt("age_max", -1).takeIf { it > 0 },
    )

    fun saveContentFilter(f: FamilyContentFilter) {
        prefs.edit()
            .putInt("age_min", f.ageMin ?: -1)
            .putInt("age_max", f.ageMax ?: -1)
            .apply()
    }

    fun householdAgeBand(): HouseholdAgeBand {
        val f = contentFilter()
        return HouseholdAgeBand(f.ageMin, f.ageMax)
    }

    fun readingSupportMode(): ReadingSupportMode =
        ReadingSupportMode.parse(prefs.getString("reading_mode", ReadingSupportMode.AUDIO_AND_TEXT.name))

    fun saveReadingSupportMode(mode: ReadingSupportMode) {
        prefs.edit().putString("reading_mode", mode.name).apply()
    }

    fun accessibility(): AccessibilitySettings = AccessibilitySettings(
        narrationEnabled = prefs.getBoolean("narration", true),
        largeText = prefs.getBoolean("large_text", false),
        highContrastFocus = prefs.getBoolean("high_focus", true),
        captionsEnabled = prefs.getBoolean("captions", true),
        readingSupportMode = readingSupportMode(),
    )

    fun saveAccessibility(a: AccessibilitySettings) {
        prefs.edit()
            .putBoolean("narration", a.narrationEnabled)
            .putBoolean("large_text", a.largeText)
            .putBoolean("high_focus", a.highContrastFocus)
            .putBoolean("captions", a.captionsEnabled)
            .putString("reading_mode", a.readingSupportMode.name)
            .apply()
    }

    fun heroContentId(): String? = prefs.getString("hero_id", null)
    fun setHeroContentId(id: String?) {
        prefs.edit().putString("hero_id", id).apply()
    }

    companion object {
        const val PREFS = "familytv_family_settings"
    }
}
