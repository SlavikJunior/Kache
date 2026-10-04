package io.github.slavikjunior.kache.store.room

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/**
 * Version 1 → 2: adds `last_accessed_at`, which LRU and MRU eviction rank by.
 *
 * Existing rows are backfilled from `created_at` rather than left at zero. Zero would place
 * every pre-existing row ahead of everything written since, so the first eviction after an
 * upgrade would drop the oldest part of the user's cache wholesale; `created_at` makes those
 * rows rank by write time instead, which is the order they effectively had before access
 * tracking existed.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SQLiteConnection) {
        db.exec(
            "ALTER TABLE cache_entries ADD COLUMN last_accessed_at INTEGER NOT NULL DEFAULT 0"
        )
        db.exec("UPDATE cache_entries SET last_accessed_at = created_at")
    }
}

/**
 * Schema migrations for [KacheDatabase], in the order they must be applied.
 *
 * Exposed so that a consumer holding a database file created by an earlier version can hand
 * them to Room instead of losing their cache to a destructive fallback.
 *
 * Declared after the migrations it collects: top-level properties initialise in declaration
 * order, so referencing one from above would capture it uninitialised.
 */
public val KACHE_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

/**
 * Runs one statement.
 *
 * Room 2.8 drives migrations through [SQLiteConnection], the KMP-native connection type that
 * replaced `SupportSQLiteDatabase`, and that interface exposes no `execSQL`: every statement
 * is prepared and stepped. A single [SQLiteStatement.step] is enough for DDL and for the
 * `UPDATE` below, both of which produce no result rows.
 */
private fun SQLiteConnection.exec(sql: String) {
    prepare(sql).use { it.step() }
}
