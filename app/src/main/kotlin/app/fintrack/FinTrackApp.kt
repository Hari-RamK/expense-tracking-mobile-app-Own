package app.fintrack

import android.app.Application
import android.content.Context
import app.fintrack.data.Database
import app.fintrack.data.KeyVault
import app.fintrack.data.Repository
import app.fintrack.data.Settings
import app.fintrack.notify.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FinTrackApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-wired dependencies; small enough that a DI framework would add more than it removes. */
class AppContainer(private val context: Context) {
    val settings = Settings(context)
    private val keyVault = KeyVault(context)
    private val loadMutex = Mutex()
    private var database: Database? = null
    private var repo: Repository? = null
    private var notifierInstance: Notifier? = null

    private val _openError = MutableStateFlow<String?>(null)

    /** Set when the encrypted database cannot be opened (e.g. the Keystore key was lost). */
    val openError: StateFlow<String?> = _openError.asStateFlow()

    val repository: Repository
        @Synchronized get() = repo ?: Repository(Database(context, keyVault.databasePassphrase()).also { database = it }, settings).also { repo = it }

    val notifier: Notifier
        @Synchronized get() = notifierInstance ?: Notifier(context, repository, settings).also { notifierInstance = it }

    suspend fun ensureLoaded() = loadMutex.withLock {
        if (repository.state.value.loaded) return@withLock
        try {
            repository.load()
            _openError.value = null
        } catch (e: Throwable) {
            _openError.value = "The encrypted database could not be opened: ${e.javaClass.simpleName}. " +
                "Restore a backup, or erase and start fresh."
        }
    }

    /** Deletes the database and its key. Used when the database cannot be opened, or on "erase everything". */
    @Synchronized
    fun destroyDatabase() {
        runCatching { database?.close() }
        context.deleteDatabase("fintrack.db")
        keyVault.destroy()
        database = null
        repo = null
        notifierInstance = null
        _openError.value = null
    }
}
