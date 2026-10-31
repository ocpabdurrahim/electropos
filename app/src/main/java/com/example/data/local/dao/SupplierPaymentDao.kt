package com.example.data.local.dao

import androidx.room.*
import com.example.sync.SyncIds
import com.example.data.local.entities.SupplierPaymentEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SupplierPaymentDao(private val db: RoomDatabase) {
    @Query("SELECT * FROM supplier_payments ORDER BY paymentDate DESC")
    abstract fun getAllPayments(): Flow<List<SupplierPaymentEntity>>

    @Query("SELECT * FROM supplier_payments WHERE id = :id LIMIT 1")
    abstract suspend fun getPaymentById(id: Long): SupplierPaymentEntity?

    @Query("SELECT * FROM supplier_payments WHERE paymentNumber = :paymentNumber LIMIT 1")
    abstract suspend fun getPaymentByNumber(paymentNumber: String): SupplierPaymentEntity?

    @Query("SELECT * FROM supplier_payments WHERE supplierId = :supplierId ORDER BY paymentDate DESC")
    abstract fun getPaymentsBySupplier(supplierId: Long): Flow<List<SupplierPaymentEntity>>

    @Query("SELECT * FROM supplier_payments WHERE supplierId = :supplierId ORDER BY paymentDate DESC")
    abstract suspend fun getPaymentsBySupplierSync(supplierId: Long): List<SupplierPaymentEntity>

    @Query("SELECT * FROM supplier_payments WHERE purchaseId = :purchaseId ORDER BY paymentDate DESC")
    abstract fun getPaymentsForPurchase(purchaseId: Long): Flow<List<SupplierPaymentEntity>>

    @Query("SELECT COUNT(*) FROM supplier_payments")
    abstract suspend fun getPaymentCount(): Int

    @Query("SELECT MAX(id) FROM supplier_payments")
    abstract suspend fun getMaxPaymentId(): Long?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPaymentRaw(payment: SupplierPaymentEntity): Long

    suspend fun insertPayment(payment: SupplierPaymentEntity): Long =
        insertPaymentRaw(if (payment.id == 0L) payment.copy(id = SyncIds.next(db, "supplier_payments")) else payment)

    @Update
    abstract suspend fun updatePayment(payment: SupplierPaymentEntity)
}
