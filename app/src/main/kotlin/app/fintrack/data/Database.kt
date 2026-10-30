package app.fintrack.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * Encrypted SQLite (SQLCipher). The engine keeps the ledger in memory and this stores it durably.
 *
 * Schema versions:
 *  1  entry / notice / transfer_group (JSON payloads with a few indexed columns), message, audit, kv.
 * Payloads are kotlinx.serialization JSON with defaults and ignoreUnknownKeys, so adding fields needs no migration;
 * a structural change bumps [VERSION] and adds a step to [onUpgrade].
 */
class Database(context: Context, passphrase: ByteArray) : SQLiteOpenHelper(
    context.applicationContext, "fintrack.db", passphrase, null, VERSION, 0, null, null, false,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE entry (id INTEGER PRIMARY KEY, date TEXT NOT NULL, json TEXT NOT NULL)")
        db.execSQL("CREATE INDEX entry_date ON entry(date)")
        db.execSQL("CREATE TABLE notice (key TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE transfer_group (id INTEGER PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE message (id INTEGER PRIMARY KEY AUTOINCREMENT, hash TEXT NOT NULL UNIQUE, sender TEXT, " +
                "body TEXT NOT NULL, received_at INTEGER NOT NULL, status TEXT NOT NULL, reason TEXT)",
        )
        db.execSQL("CREATE TABLE audit (id INTEGER PRIMARY KEY AUTOINCREMENT, entry_id INTEGER, action TEXT NOT NULL, detail TEXT NOT NULL, at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first release; future steps go here, one `if (oldVersion < N)` block each.
    }

    companion object {
        const val VERSION = 1

        init {
            System.loadLibrary("sqlcipher")
        }
    }
}
