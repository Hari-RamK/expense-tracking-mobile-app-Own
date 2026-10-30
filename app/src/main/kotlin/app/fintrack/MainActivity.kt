package app.fintrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.fintrack.notify.Scheduler
import app.fintrack.ui.AccountsScreen
import app.fintrack.ui.AppViewModel
import app.fintrack.ui.EntryEditorDialog
import app.fintrack.ui.FinTrackTheme
import app.fintrack.ui.HomeScreen
import app.fintrack.ui.RegularItemsScreen
import app.fintrack.ui.ReportScreen
import app.fintrack.ui.ReviewScreen
import app.fintrack.ui.SettingsScreen
import app.fintrack.ui.TransactionsScreen
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()
    private var locked by mutableStateOf(false)
    private var lastStopMillis = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            vm.settings.collect { s ->
                if (s.secureScreen) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        Scheduler.schedule(this, Scheduler.Mode.KEEP)
        setContent { FinTrackTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
        val away = System.currentTimeMillis() - lastStopMillis
        if (vm.settings.value.appLockEnabled && (lastStopMillis == 0L || away > LOCK_AFTER_MILLIS)) {
            locked = true
            promptUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        lastStopMillis = System.currentTimeMillis()
    }

    private fun promptUnlock() {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else BiometricManager.Authenticators.BIOMETRIC_WEAK
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Never lock the owner out of their own data because the phone has no screen lock set up.
            locked = false
            vm.say("App lock needs a fingerprint or screen lock set up on this phone, so it is off for now.")
            return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Buho FinTrack").setAllowedAuthenticators(authenticators)
                .apply { if (Build.VERSION.SDK_INT < 30) setNegativeButtonText("Cancel") }.build(),
        )
    }

    @Composable
    private fun Root() {
        val error by vm.openError.collectAsStateWithLifecycle()
        val snapshot by vm.repository.state.collectAsStateWithLifecycle()
        val message by vm.message.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.say(null) } }

        when {
            locked -> LockScreen { promptUnlock() }
            error != null -> ErrorScreen(error!!) { vm.resetBrokenDatabase() }
            !snapshot.loaded -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) { CircularProgressIndicator() }
            else -> MainScaffold(snackbar)
        }
    }

    @Composable
    private fun LockScreen(onUnlock: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
            Text("Buho FinTrack is locked", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onUnlock, modifier = Modifier.padding(top = 16.dp)) { Text("Unlock") }
        }
    }

    @Composable
    private fun ErrorScreen(text: String, onReset: () -> Unit) {
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center) {
            Text("Cannot open your data", style = MaterialTheme.typography.headlineSmall)
            Text(text, modifier = Modifier.padding(vertical = 12.dp))
            Button(onClick = onReset) { Text("Erase and start fresh") }
        }
    }

    private data class Tab(val route: String, val label: String, val glyph: String)

    @Composable
    private fun MainScaffold(snackbar: SnackbarHostState) {
        val nav = rememberNavController()
        val snapshot by vm.repository.state.collectAsStateWithLifecycle()
        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        var editing by remember { mutableStateOf<Long?>(null) }
        val context = LocalContext.current
        var notificationsAllowed by remember { mutableStateOf(notificationPermissionGranted()) }

        val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.all { it }) vm.importInbox(context) else vm.say("SMS access was not granted, so nothing can be read.")
        }
        val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = it }
        val requestSms = { smsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }
        val requestNotifications = {
            if (Build.VERSION.SDK_INT >= 33) notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else notificationsAllowed = true
        }

        val tabs = listOf(
            Tab("home", "Home", "⌂"), Tab("report", "Reports", "▤"), Tab("transactions", "Activity", "⇄"),
            Tab("accounts", "Accounts", "▦"), Tab("settings", "Settings", "⚙"),
        )
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route, onClick = { nav.navigate(t.route) { launchSingleTop = true; popUpTo("home") } },
                            icon = { Text(t.glyph) }, label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") {
                    HomeScreen(vm, snapshot, onOpenReview = { nav.navigate("review") }, onOpenEntry = { editing = it }, onOpenAccounts = { nav.navigate("accounts") }, requestSms = { requestSms() })
                }
                composable("report") { ReportScreen(vm, snapshot) { editing = it } }
                composable("transactions") { TransactionsScreen(snapshot, vm, initialReviewOnly = false) { editing = it } }
                composable("review") { ReviewScreen(snapshot, vm) { editing = it } }
                composable("accounts") { AccountsScreen(snapshot, vm) }
                composable("regular") { RegularItemsScreen(snapshot, vm) }
                composable("settings") {
                    SettingsScreen(snapshot, vm, onOpenRegular = { nav.navigate("regular") }, requestSms = { requestSms() }, requestNotifications = { requestNotifications() }, notificationsAllowed = notificationsAllowed)
                }
            }
        }
        editing?.let { EntryEditorDialog(it, snapshot, vm) { editing = null } }
    }

    private fun notificationPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val LOCK_AFTER_MILLIS = 30_000L
    }
}
