package com.example.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.ProductEntity
import com.example.data.local.entities.ProductSerialImeiEntity
import com.example.data.local.entities.SaleEntity
import com.example.data.local.entities.ShopProfileEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rows <-> documents: booleans stay booleans, numbers keep their kind, missing fields take the entity default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMapperTest {
    private lateinit var db: AppDatabase
    private val sql get() = db.openHelper.writableDatabase

    @Before
    fun setUp() {
        db = syncDatabase(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun rowBecomesTypedDocumentAndComesBackTheSame() = runBlocking {
        val products = SyncTables.get("products")
        val id = db.productDao().insertProduct(
            ProductEntity(
                name = "Galaxy A15", purchasePrice = 15999.5, retailPrice = 18500.0, currentStock = 4, hasWarranty = true,
                isImeiRequired = false, imageUri = "content://phone/only.jpg", warrantyPeriodMonths = 12
            )
        )
        val doc = SyncMapper.readRow(sql, products, id)!!
        assertEquals(true, doc["hasWarranty"])
        assertEquals(false, doc["isImeiRequired"])
        assertEquals(15999.5, doc["purchasePrice"])
        assertEquals(4L, doc["currentStock"])
        assertEquals(12L, doc["warrantyPeriodMonths"])
        assertEquals("Galaxy A15", doc["name"])
        assertFalse("local-only columns stay on the phone", doc.containsKey("imageUri"))

        // Back into a second row: same values; the local-only column takes its default
        val copy = doc + ("id" to id + 1)
        sql.insert("products", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, SyncMapper.docToValues(products, copy, withLocalDefaults = true))
        val back = db.productDao().getProductById(id + 1)!!
        val original = db.productDao().getProductById(id)!!
        assertEquals(original.copy(id = id + 1, imageUri = ""), back)
    }

    @Test
    fun readerAcceptsIntegersForNumbersAndNumbersForIntegers() {
        val sales = SyncTables.get("sales")
        // The web writes 500, not 500.0, and may hand an integer field as 3.0
        val values = SyncMapper.docToValues(
            sales,
            mapOf("id" to 9L, "invoiceNumber" to "INV-W00001", "totalAmount" to 500L, "paidAmount" to 200, "createdAt" to 1.7e12, "customerId" to null),
            withLocalDefaults = true
        )
        assertEquals(500.0, values.getAsDouble("totalAmount"), 0.0)
        assertEquals(200.0, values.getAsDouble("paidAmount"), 0.0)
        assertEquals(1_700_000_000_000L, values.getAsLong("createdAt"))
        assertNull("nullable stays null", values.get("customerId"))
        // Missing fields take the entity's defaults
        assertEquals(SaleEntity(invoiceNumber = "").customerName, values.getAsString("customerName"))
        assertEquals("PAID", values.getAsString("paymentStatus"))

        val col = SyncColumn("isActive", ColType.BOOLEAN, false)
        assertEquals(1L, SyncMapper.sqlValue(col, true, 0L))
        assertEquals(0L, SyncMapper.sqlValue(col, 0, 1L))
        assertEquals(1L, SyncMapper.sqlValue(col, 1.0, 0L))
    }

    @Test
    fun nullableColumnsAndNonNullDefaultsRoundTrip() = runBlocking {
        val table = SyncTables.get("product_serial_imei")
        val productId = db.productDao().insertProduct(ProductEntity(name = "Phone"))
        val id = db.productDao().insertSerialImei(ProductSerialImeiEntity(productId = productId, imei1 = "356789012345678"))
        val doc = SyncMapper.readRow(sql, table, id)!!
        assertNull(doc["serialNumber"])
        assertEquals("356789012345678", doc["imei1"])
        // A JSON round trip (records waiting for their parent are stored as JSON) keeps the values
        val again = SyncMapper.fromJson(SyncMapper.toJson(doc))
        val values = SyncMapper.docToValues(table, again, withLocalDefaults = true)
        assertNull(values.get("serialNumber"))
        assertEquals("356789012345678", values.getAsString("imei1"))
        assertEquals(productId, values.getAsLong("productId"))
        // An explicit null in a non-null column takes the default instead of breaking the insert
        assertEquals("IN_STOCK", SyncMapper.docToValues(table, again + ("status" to null), true).getAsString("status"))
    }

    @Test
    fun shopProfileKeepsPhoneSettingsOnUpdate() = runBlocking {
        val table = SyncTables.get("shop_profile")
        db.settingsAndAuditDao().saveShopProfile(ShopProfileEntity(shopName = "Old", themeMode = "DARK", invoiceNextNumber = 1500))
        val incoming = mapOf("id" to 1L, "shopName" to "From the web", "themeMode" to "LIGHT", "invoiceNextNumber" to 1L)
        sql.update("shop_profile", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, SyncMapper.docToValues(table, incoming, withLocalDefaults = false), "id = 1", null)
        val p = db.settingsAndAuditDao().getShopProfileSync()!!
        assertEquals("From the web", p.shopName)
        assertEquals("DARK", p.themeMode)
        assertEquals(1500L, p.invoiceNextNumber)
    }
}
