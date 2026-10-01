package com.smsextractor.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import com.smsextractor.core.Transaction
import com.smsextractor.core.TxType
import java.math.BigDecimal
import java.time.LocalDate

/** Amounts are stored as text so no precision is ever lost (converted to BigDecimal when read). */
@Entity(tableName = "transactions", indices = [Index(value = ["dedupKey"], unique = true)])
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val sender: String,
    val smsTimestamp: Long?,
    val originalText: String,
    val date: String?,
    val dateText: String?,
    val partialMonth: Int?,
    val partialDay: Int?,
    val time: String?,
    val action: String?,
    val type: String,
    val amount: String?,
    val currency: String?,
    val fromParty: String?,
    val toParty: String?,
    val merchant: String?,
    val accountSuffix: String?,
    val reference: String?,
    val balance: String?,
    val balanceCurrency: String?,
    val needsReview: Boolean,
    val reviewNotes: String,
    val userEdited: Boolean,
)

data class SenderCount(val sender: String, val count: Int)

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT dedupKey FROM transactions")
    suspend fun keys(): List<String>

    @Query("SELECT sender, COUNT(*) AS count FROM transactions GROUP BY sender")
    suspend fun senderCounts(): List<SenderCount>

    /** IGNORE + the unique dedupKey index means re-importing never creates duplicates. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(item: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [TransactionEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TransactionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "transactions.db")
                .build().also { instance = it }
        }
    }
}

fun TransactionEntity.toModel() = Transaction(
    id = id, dedupKey = dedupKey, sender = sender, smsTimestamp = smsTimestamp, originalText = originalText,
    date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    dateText = dateText, partialMonth = partialMonth, partialDay = partialDay, time = time, action = action,
    type = runCatching { TxType.valueOf(type) }.getOrDefault(TxType.UNKNOWN),
    amount = amount?.let { runCatching { BigDecimal(it) }.getOrNull() },
    currency = currency, fromParty = fromParty, toParty = toParty, merchant = merchant,
    accountSuffix = accountSuffix, reference = reference,
    balance = balance?.let { runCatching { BigDecimal(it) }.getOrNull() },
    balanceCurrency = balanceCurrency, needsReview = needsReview, reviewNotes = reviewNotes, userEdited = userEdited,
)

fun Transaction.toEntity() = TransactionEntity(
    id = id, dedupKey = dedupKey, sender = sender, smsTimestamp = smsTimestamp, originalText = originalText,
    date = date?.toString(), dateText = dateText, partialMonth = partialMonth, partialDay = partialDay, time = time,
    action = action, type = type.name, amount = amount?.toPlainString(), currency = currency,
    fromParty = fromParty, toParty = toParty, merchant = merchant, accountSuffix = accountSuffix,
    reference = reference, balance = balance?.toPlainString(), balanceCurrency = balanceCurrency,
    needsReview = needsReview, reviewNotes = reviewNotes, userEdited = userEdited,
)
