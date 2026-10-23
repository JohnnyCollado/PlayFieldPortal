package com.playfieldportal.core.data.database

import androidx.room.withTransaction
import javax.inject.Inject

/**
 * Runs a block of DAO calls as one database transaction: all of it lands, or none of it does.
 * An interface so a caller's tests can run the block directly without a database.
 */
interface DatabaseTransactions {
    suspend fun <T> run(block: suspend () -> T): T
}

/** [DatabaseTransactions] on the app database. */
class RoomDatabaseTransactions @Inject constructor(private val db: PFPDatabase) : DatabaseTransactions {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction { block() }
}
