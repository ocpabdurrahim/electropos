package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.CustomerEntity
import com.example.data.local.entities.CustomerLedgerEntryEntity
import com.example.data.local.entities.CustomerPaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CustomerDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM customers WHERE isActive = 1 ORDER BY name ASC")
    abstract fun getAllActiveCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    abstract fun getAllCustomers(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY name ASC")
    abstract suspend fun getAllCustomersSync(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    abstract suspend fun getCustomerById(id: Long): CustomerEntity?

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    abstract fun observeCustomerById(id: Long): Flow<CustomerEntity?>

    @Query("SELECT * FROM customers WHERE phone = :phone AND isActive = 1 LIMIT 1")
    abstract suspend fun getCustomerByPhone(phone: String): CustomerEntity?

    @Query("SELECT * FROM customers WHERE customerCode = :code LIMIT 1")
    abstract suspend fun getCustomerByCode(code: String): CustomerEntity?

    @Query("""
        SELECT * FROM customers 
        WHERE (name LIKE '%' || :query || '%' 
           OR phone LIKE '%' || :query || '%' 
           OR customerCode LIKE '%' || :query || '%'
           OR address LIKE '%' || :query || '%')
        ORDER BY name ASC
    """)
    abstract fun searchCustomers(query: String): Flow<List<CustomerEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCustomerRaw(customer: CustomerEntity): Long

    suspend fun insertCustomer(customer: CustomerEntity): Long =
        insertCustomerRaw(if (customer.id == 0L) customer.copy(id = SyncIds.next(db, "customers")) else customer)

    @Update
    abstract suspend fun updateCustomer(customer: CustomerEntity)

    @Query("UPDATE customers SET currentDue = currentDue + :delta, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun adjustCustomerDue(id: Long, delta: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE customers SET currentDue = :balance, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun setCustomerBalance(id: Long, balance: Double, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE customers SET isActive = 0, updatedAt = :timestamp WHERE id = :id")
    abstract suspend fun deactivateCustomer(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM customers WHERE customerCode = :code AND id != :excludeId")
    abstract suspend fun countCustomerCodeOccurrences(code: String, excludeId: Long = 0L): Int

    @Query("SELECT COUNT(*) FROM customers WHERE phone = :phone AND id != :excludeId AND isActive = 1")
    abstract suspend fun countCustomerPhoneOccurrences(phone: String, excludeId: Long = 0L): Int

    @Query("SELECT SUM(currentDue) FROM customers WHERE isActive = 1")
    abstract fun getTotalCustomerDue(): Flow<Double?>
}

@Dao
abstract class CustomerLedgerDao(private val db: RoomDatabase) {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLedgerEntryRaw(entry: CustomerLedgerEntryEntity): Long

    suspend fun insertLedgerEntry(entry: CustomerLedgerEntryEntity): Long =
        insertLedgerEntryRaw(if (entry.id == 0L) entry.copy(id = SyncIds.next(db, "customer_ledger_entries")) else entry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertLedgerEntriesRaw(entries: List<CustomerLedgerEntryEntity>): List<Long>

    suspend fun insertLedgerEntries(entries: List<CustomerLedgerEntryEntity>): List<Long> =
        insertLedgerEntriesRaw(SyncIds.assign(db, "customer_ledger_entries", entries, { it.id }) { row, id -> row.copy(id = id) })

    @Query("SELECT * FROM customer_ledger_entries WHERE customerId = :customerId ORDER BY createdAt ASC, id ASC")
    abstract fun getLedgerEntriesForCustomer(customerId: Long): Flow<List<CustomerLedgerEntryEntity>>

    @Query("SELECT * FROM customer_ledger_entries WHERE customerId = :customerId ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getLedgerEntriesForCustomerSync(customerId: Long): List<CustomerLedgerEntryEntity>

    @Query("SELECT * FROM customer_ledger_entries WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC LIMIT 1")
    abstract suspend fun getLatestLedgerEntryForCustomer(customerId: Long): CustomerLedgerEntryEntity?

    @Query("SELECT COALESCE(SUM(signedAmount), 0.0) FROM customer_ledger_entries WHERE customerId = :customerId")
    abstract suspend fun calculateCustomerBalance(customerId: Long): Double

    @Query("SELECT * FROM customer_ledger_entries ORDER BY createdAt DESC")
    abstract fun getAllLedgerEntries(): Flow<List<CustomerLedgerEntryEntity>>

    @Query("SELECT * FROM customer_ledger_entries ORDER BY createdAt ASC, id ASC")
    abstract suspend fun getAllLedgerEntriesSync(): List<CustomerLedgerEntryEntity>

    @Query("DELETE FROM customer_ledger_entries WHERE referenceId = :refId AND transactionType = :txType")
    abstract suspend fun deleteEntriesForReference(refId: Long, txType: String)
}

@Dao
abstract class CustomerPaymentDao(private val db: RoomDatabase) {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPaymentRaw(payment: CustomerPaymentEntity): Long

    suspend fun insertPayment(payment: CustomerPaymentEntity): Long =
        insertPaymentRaw(if (payment.id == 0L) payment.copy(id = SyncIds.next(db, "customer_payments")) else payment)

    @Query("SELECT * FROM customer_payments WHERE customerId = :customerId ORDER BY paymentDate DESC")
    abstract fun getPaymentsForCustomer(customerId: Long): Flow<List<CustomerPaymentEntity>>

    @Query("SELECT * FROM customer_payments WHERE customerId = :customerId ORDER BY paymentDate DESC")
    abstract suspend fun getPaymentsForCustomerSync(customerId: Long): List<CustomerPaymentEntity>

    @Query("SELECT * FROM customer_payments ORDER BY paymentDate DESC")
    abstract fun getAllCustomerPayments(): Flow<List<CustomerPaymentEntity>>

    @Query("SELECT * FROM customer_payments WHERE id = :id LIMIT 1")
    abstract suspend fun getPaymentById(id: Long): CustomerPaymentEntity?

    @Query("SELECT COUNT(*) FROM customer_payments WHERE paymentNumber = :paymentNumber")
    abstract suspend fun countPaymentNumberMatches(paymentNumber: String): Int
}
