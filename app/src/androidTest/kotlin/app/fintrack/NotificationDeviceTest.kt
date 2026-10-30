package app.fintrack

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fintrack.core.model.AccountKind
import app.fintrack.core.model.OwnAccount
import app.fintrack.core.model.OwnerProfile
import app.fintrack.data.Database
import app.fintrack.data.KeyVault
import app.fintrack.data.Repository
import app.fintrack.data.Settings
import app.fintrack.notify.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Real notification posting: content, one-per-period, and an amount-free lock-screen version. All data is fake. */
@RunWith(AndroidJUnit4::class)
class NotificationDeviceTest {
    @Test
    fun dailySummaryIsPostedOnceAndHidesAmountsOnTheLockScreen() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()

        context.deleteDatabase("fintrack.db")
        val settings = Settings(context)
        val repo = Repository(Database(context, KeyVault(context).databasePassphrase()), settings)
        repo.load()
        repo.updateProfile(
            OwnerProfile(accounts = listOf(OwnAccount(1, "Kotak ••2126", "KOTAK", AccountKind.BANK, listOf("2126"), listOf("kotak")))),
        )
        val today = LocalDate.now()
        val d = "%02d-%02d-%02d".format(today.dayOfMonth, today.monthValue, today.year % 100)
        repo.ingestSms("AX-KOTAKB-S", "Sent Rs.1250.00 from Kotak Bank A/c X2126 to CAFE MOCHA on $d. UPI Ref 910000000001. Not done by you?", System.currentTimeMillis())

        val notifier = Notifier(context, repo, settings)
        notifier.runScheduled(today)
        notifier.runScheduled(today) // same period again: must not post a second time

        val posted = manager.activeNotifications.filter { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Today's spending" }
        assertEquals("exactly one daily notification", 1, posted.size)
        val text = posted.single().notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(text, "₹1,250 across 1 transaction" in text && "Transfers excluded" in text)

        val public = posted.single().notification.publicVersion
        assertNotNull("lock-screen version must exist while amounts are off", public)
        val publicText = public.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse("no amounts on the lock screen: $publicText", publicText.any { it.isDigit() } || '₹' in publicText)
        manager.cancelAll()
    }
}
