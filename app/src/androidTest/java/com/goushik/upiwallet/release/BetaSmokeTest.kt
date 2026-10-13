package com.goushik.upiwallet.release

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.goushik.upiwallet.MainActivity
import com.goushik.upiwallet.data.AppDatabase
import com.goushik.upiwallet.di.MIGRATION_7_8
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on an API-29 emulator in CI. Checks installation/launch, the merged manifest and a real Room
 * migration against a populated v7 file; pure ledger behaviour is covered by JVM tests. */
@RunWith(AndroidJUnit4::class)
class BetaSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun launchesOnAndroid10WithoutInternetPermission() {
        assertTrue(Build.VERSION.SDK_INT >= 29)
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse((info.requestedPermissions ?: emptyArray()).contains(Manifest.permission.INTERNET))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        assertNotNull(activity)
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test fun migratesPopulatedVersion7WithoutDiscardingPaymentOrAccount() = runBlocking {
        val name = "beta-upgrade-smoke.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        val old = SQLiteDatabase.openOrCreateDatabase(file, null)
        old.execSQL("CREATE TABLE transactions (id TEXT NOT NULL PRIMARY KEY, amountPaise INTEGER NOT NULL, " +
            "direction TEXT NOT NULL, status TEXT NOT NULL, payeeName TEXT, payeeVpa TEXT, " +
            "payerAccountLast4 TEXT, bankLabel TEXT, rrn TEXT, timestampEvent INTEGER NOT NULL, " +
            "timestampCaptured INTEGER NOT NULL, source TEXT NOT NULL, episodeId TEXT, needsReview INTEGER NOT NULL, " +
            "category TEXT, categoryConfidence REAL, categorySource TEXT, latRounded REAL, lngRounded REAL)")
        old.execSQL("CREATE UNIQUE INDEX index_transactions_rrn_direction ON transactions (rrn, direction)")
        old.execSQL("CREATE INDEX index_transactions_timestampEvent ON transactions (timestampEvent)")
        old.execSQL("CREATE INDEX index_transactions_direction_timestampEvent ON transactions (direction, timestampEvent)")
        old.execSQL("CREATE TABLE raw_events (id TEXT NOT NULL PRIMARY KEY, txnId TEXT, source TEXT NOT NULL, " +
            "packageName TEXT, eventType TEXT, payload TEXT NOT NULL, capturedAt INTEGER NOT NULL)")
        old.execSQL("CREATE INDEX index_raw_events_txnId ON raw_events (txnId)")
        old.execSQL("CREATE TABLE balance_anchors (id TEXT NOT NULL PRIMARY KEY, accountLabel TEXT NOT NULL, " +
            "baselinePaise INTEGER NOT NULL, anchoredAt INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE user_profile (id TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, " +
            "ownVpasCsv TEXT NOT NULL, onboardedAt INTEGER, showBalance INTEGER NOT NULL DEFAULT 1)")
        old.execSQL("CREATE TABLE merchant_rules (matchKey TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, " +
            "source TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
        old.execSQL("CREATE TABLE budgets (period TEXT NOT NULL PRIMARY KEY, limitPaise INTEGER NOT NULL, " +
            "updatedAt INTEGER NOT NULL, lastAlertedThreshold INTEGER NOT NULL, lastAlertedPeriodStart INTEGER NOT NULL)")
        old.execSQL("INSERT INTO balance_anchors VALUES ('a1', 'HDFC', 100000, 1000)")
        old.execSQL("INSERT INTO transactions (id, amountPaise, direction, status, timestampEvent, " +
            "timestampCaptured, source, needsReview, bankLabel) VALUES ('old-payment', 1000, 'DEBIT', " +
            "'CONFIRMED', 2000, 2000, 'sms', 0, 'HDFC')")
        old.execSQL("PRAGMA user_version = 7")
        old.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_7_8).build()
        try {
            assertEquals(1, db.balanceAnchorDao().all().size)
            assertEquals("old-payment", db.transactionDao().allOnce().single().id)
            assertEquals(null, db.transactionDao().allOnce().single().kind)
            assertEquals(0, db.categoryBudgetDao().all().size)
            assertEquals(0, db.paymentInstrumentDao().all().size)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
