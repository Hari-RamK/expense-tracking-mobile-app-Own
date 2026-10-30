package app.fintrack.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId

data class SettingsState(
    val dailyEnabled: Boolean = true,
    val weeklyEnabled: Boolean = true,
    val monthlyEnabled: Boolean = true,
    val notifyHour: Int = 22,
    val notifyMinute: Int = 0,
    /** Amounts on the lock screen are off unless the owner turns them on. */
    val showAmountsOnLockScreen: Boolean = false,
    /** 0 disables the spending-limit notification. */
    val dailyLimitPaise: Long = 0,
    val appLockEnabled: Boolean = false,
    /** Hides the app from the recent-apps screenshot and blocks screen capture. */
    val secureScreen: Boolean = true,
    val importDays: Int = 90,
)

/** Non-sensitive preferences. Anything financial lives in the encrypted database. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    val zone: ZoneId get() = prefs.getString("zone", null)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun setZone(id: String) = prefs.edit().putString("zone", id).apply()

    private fun read() = SettingsState(
        dailyEnabled = prefs.getBoolean("daily", true),
        weeklyEnabled = prefs.getBoolean("weekly", true),
        monthlyEnabled = prefs.getBoolean("monthly", true),
        notifyHour = prefs.getInt("hour", 22),
        notifyMinute = prefs.getInt("minute", 0),
        showAmountsOnLockScreen = prefs.getBoolean("lockAmounts", false),
        dailyLimitPaise = prefs.getLong("limit", 0),
        appLockEnabled = prefs.getBoolean("appLock", false),
        secureScreen = prefs.getBoolean("secure", true),
        importDays = prefs.getInt("importDays", 90),
    )

    fun update(change: (SettingsState) -> SettingsState) {
        val s = change(_state.value)
        prefs.edit()
            .putBoolean("daily", s.dailyEnabled).putBoolean("weekly", s.weeklyEnabled).putBoolean("monthly", s.monthlyEnabled)
            .putInt("hour", s.notifyHour).putInt("minute", s.notifyMinute)
            .putBoolean("lockAmounts", s.showAmountsOnLockScreen).putLong("limit", s.dailyLimitPaise)
            .putBoolean("appLock", s.appLockEnabled).putBoolean("secure", s.secureScreen).putInt("importDays", s.importDays)
            .apply()
        _state.value = s
    }

    fun clear() {
        prefs.edit().clear().apply()
        _state.value = read()
    }
}
