package app.fintrack.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fintrack.FinTrackApp
import app.fintrack.MainActivity
import app.fintrack.R
import app.fintrack.core.report.NotificationContent
import app.fintrack.core.report.Notifications
import app.fintrack.core.report.Period
import app.fintrack.core.report.ReportBuilder
import app.fintrack.data.Repository
import app.fintrack.data.Settings
import app.fintrack.sms.hasSmsPermission
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Builds notifications from the same ReportBuilder the screens use, never sends the same period twice, and keeps
 * amounts off the lock screen unless the owner enabled that.
 */
class Notifier(private val context: Context, private val repository: Repository, private val settings: Settings) {

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_SUMMARY, "Spending summaries", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Spending alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private suspend fun alreadySent(key: String): Boolean {
        val sent = repository.kv(SENT_KEY)?.split('\n').orEmpty()
        return key in sent
    }

    private suspend fun markSent(key: String) {
        val sent = (repository.kv(SENT_KEY)?.split('\n').orEmpty() + key).takeLast(400)
        repository.putKv(SENT_KEY, sent.joinToString("\n"))
    }

    @SuppressLint("MissingPermission")
    private suspend fun post(content: NotificationContent, channel: String): Boolean {
        if (alreadySent(content.key)) return false
        if (!canNotify()) return false
        val showAmounts = settings.state.value.showAmountsOnLockScreen
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(if (showAmounts) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
        if (!showAmounts) {
            builder.setPublicVersion(
                NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(content.title).setContentText(content.publicText).build(),
            )
        }
        NotificationManagerCompat.from(context).notify(content.key.hashCode(), builder.build())
        markSent(content.key)
        return true
    }

    private fun report(period: Period, asOf: LocalDate) =
        repository.state.value.let { ReportBuilder(it.profile, it.entries, it.notices).build(period, asOf) }

    /** Called after new messages arrive: a once-a-day alert when today's spending passes the owner's limit. */
    suspend fun afterNewData() {
        val limit = settings.state.value.dailyLimitPaise
        if (limit <= 0) return
        val today = LocalDate.now(settings.zone)
        Notifications.thresholdExceeded(report(Period.day(today), today), limit)?.let { post(it, CHANNEL_ALERT) }
    }

    /** The scheduled summaries for [date]: daily always (if enabled), weekly on the week's last day, monthly on the month's. */
    suspend fun runScheduled(date: LocalDate) {
        val s = settings.state.value
        val coverage = if (hasSmsPermission(context)) null else "SMS access is off, so these totals may be incomplete."
        if (s.dailyEnabled) post(Notifications.daily(report(Period.day(date), date), coverage), CHANNEL_SUMMARY)
        // A week ends on Sunday, or on the last day of the month when the month ends mid-week.
        val week = Period.week(date)
        if (s.weeklyEnabled && week.end == date) post(Notifications.weekly(report(week, date)), CHANNEL_SUMMARY)
        if (s.monthlyEnabled && date == YearMonth.from(date).atEndOfMonth()) {
            post(Notifications.monthly(report(Period.month(date), date)), CHANNEL_SUMMARY)
        }
    }

    companion object {
        const val CHANNEL_SUMMARY = "summaries"
        const val CHANNEL_ALERT = "alerts"
        private const val SENT_KEY = "sent_notifications"
    }
}

object Scheduler {
    private const val WORK = "daily-summary"
    private const val TARGET = "target_date"

    enum class Mode { REPLACE, KEEP, CONTINUE }

    /**
     * Schedules the next summary at the owner's chosen time.
     * REPLACE after the time or toggles change, KEEP at app start (do nothing if already scheduled),
     * CONTINUE from the worker itself to queue the following day.
     */
    fun schedule(context: Context, mode: Mode = Mode.REPLACE) {
        val app = context.applicationContext as FinTrackApp
        val settings = app.container.settings
        val s = settings.state.value
        val zone = settings.zone
        val now = ZonedDateTime.now(zone)
        var next = now.with(LocalTime.of(s.notifyHour, s.notifyMinute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = OneTimeWorkRequestBuilder<DailyWorker>()
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putString(TARGET, next.toLocalDate().toString()).build())
            .build()
        val policy = when (mode) {
            Mode.REPLACE -> ExistingWorkPolicy.REPLACE
            Mode.KEEP -> ExistingWorkPolicy.KEEP
            Mode.CONTINUE -> ExistingWorkPolicy.APPEND_OR_REPLACE
        }
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)

    fun targetOf(data: Data): LocalDate? = data.getString(TARGET)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as FinTrackApp
        return try {
            app.container.ensureLoaded()
            val date = Scheduler.targetOf(inputData) ?: LocalDate.now(app.container.settings.zone)
            app.container.notifier.runScheduled(date)
            Scheduler.schedule(applicationContext, Scheduler.Mode.CONTINUE)
            Result.success()
        } catch (e: Exception) {
            Scheduler.schedule(applicationContext, Scheduler.Mode.CONTINUE)
            Result.failure()
        }
    }
}
