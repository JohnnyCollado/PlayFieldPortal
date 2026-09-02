package com.playfieldportal.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Builds a [MigrationTestHelper] on Room's driver-based API.
 *
 * Room 2.7 replaced the SupportSQLite flavour of this helper (`createDatabase(name, version)` /
 * `runMigrationsAndValidate(name, version, validateDroppedTables, vararg migrations)`) with one
 * that takes the database file and an explicit driver up front. The old overloads still exist but
 * are broken under Robolectric: the compatibility layer configures its driver with the bare
 * database name while Room asks it for the absolute path, so every call fails with
 * "This driver is configured to open a database named ...".
 */
fun migrationTestHelper(databaseName: String): MigrationTestHelper {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    return MigrationTestHelper(
        instrumentation = instrumentation,
        file = instrumentation.targetContext.getDatabasePath(databaseName),
        driver = AndroidSQLiteDriver(),
        databaseClass = PFPDatabase::class,
    )
}

/**
 * Maps every result row of [sql] through [map].
 *
 * The driver API replaces Cursor with [SQLiteStatement], whose accessors are `getText` / `getLong`
 * / `isNull` and which advances with `step()` — there is no `moveToFirst` and no `count`.
 */
fun <T> SQLiteConnection.rows(sql: String, map: (SQLiteStatement) -> T): List<T> =
    prepare(sql).use { stmt ->
        buildList { while (stmt.step()) add(map(stmt)) }
    }

/** The single row [sql] is expected to return, mapped through [map]. Fails if there is none. */
fun <T> SQLiteConnection.singleRow(sql: String, map: (SQLiteStatement) -> T): T =
    prepare(sql).use { stmt ->
        check(stmt.step()) { "query returned no rows: $sql" }
        map(stmt)
    }

/** First column of the first row as an Int — for `SELECT COUNT(*)` and other scalar reads. */
fun SQLiteConnection.count(sql: String): Int = singleRow(sql) { it.getLong(0).toInt() }
