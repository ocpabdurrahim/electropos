package com.example.data.repository

import androidx.room.withTransaction
import com.example.data.local.database.AppDatabase
import com.example.data.local.entities.*
import org.json.JSONArray
import org.json.JSONObject
import com.example.sync.SyncLocal

class SampleDataAndBackupRepository(private val database: AppDatabase) {

    suspend fun initializeDefaultDataIfEmpty() {
        // A linked phone gets its profile and lists from the cloud shop
        if (SyncLocal.isLinked(database)) return
        val profile = database.settingsAndAuditDao().getShopProfileSync()
        if (profile == null) {
            database.settingsAndAuditDao().saveShopProfile(ShopProfileEntity())
        }

        // Initialize default expense categories
        val defaultExpenseCats = listOf(
            "Shop Rent", "Electricity Bill", "Internet & Wifi", "Staff Salary",
            "Transport & Courier", "Packaging & Bags", "Repairs & Maintenance",
            "Marketing & Ads", "Tea & Refreshments", "Other Expenses"
        )
        for (cat in defaultExpenseCats) {
            database.financeDao().insertExpenseCategory(ExpenseCategoryEntity(name = cat, isDefault = true))
        }

        // No opening cash is invented here: a real shop starts its cash book from its own count
    }

    suspend fun seedSampleElectronicsData() {
        check(!SyncLocal.isLinked(database)) { "Sample data can't be loaded on a phone linked to a cloud shop." }
        database.withTransaction {
            // 0. Demo cash drawer (sample data only)
            database.financeDao().insertCashTransaction(
                CashRegisterTransactionEntity(
                    type = "OPENING_CASH",
                    amount = 25000.0,
                    flowDirection = "IN",
                    paymentMethod = "CASH",
                    notes = "Sample opening cash drawer"
                )
            )

            // 1. Categories
            val categories = listOf(
                CategoryEntity(name = "Mobile Phones", description = "Smartphones and iPhones", iconName = "phone_android"),
                CategoryEntity(name = "Feature Phones", description = "Keypad mobile phones", iconName = "phone"),
                CategoryEntity(name = "Chargers & Adapters", description = "Wall chargers and fast adapters", iconName = "power"),
                CategoryEntity(name = "Cables & Converters", description = "USB-C, Lightning, Micro, HDMI", iconName = "cable"),
                CategoryEntity(name = "Audio & Earphones", description = "TWS, Neckbands, Headphones", iconName = "headphones"),
                CategoryEntity(name = "Power Banks", description = "Portable battery packs", iconName = "battery_charging_full"),
                CategoryEntity(name = "Cases & Protectors", description = "Covers and tempered glass", iconName = "shield"),
                CategoryEntity(name = "Routers & Networking", description = "Wi-Fi routers, modems, cables", iconName = "router"),
                CategoryEntity(name = "Computer Accessories", description = "Keyboards, mice, webcams", iconName = "mouse"),
                CategoryEntity(name = "Storage & Memory", description = "SSD, Pendrives, SD cards, RAM", iconName = "storage")
            )
            val catIds = categories.map { database.masterDao().insertCategory(it) }

            // 2. Brands
            val brands = listOf(
                BrandEntity(name = "Apple", originCountry = "USA"),
                BrandEntity(name = "Samsung", originCountry = "South Korea"),
                BrandEntity(name = "Xiaomi", originCountry = "China"),
                BrandEntity(name = "Oppo", originCountry = "China"),
                BrandEntity(name = "Vivo", originCountry = "China"),
                BrandEntity(name = "TP-Link", originCountry = "China"),
                BrandEntity(name = "Logitech", originCountry = "Switzerland"),
                BrandEntity(name = "Anker", originCountry = "USA"),
                BrandEntity(name = "SanDisk", originCountry = "USA"),
                BrandEntity(name = "A4Tech", originCountry = "Taiwan")
            )
            val brandIds = brands.map { database.masterDao().insertBrand(it) }

            // 3. Suppliers
            val suppliers = listOf(
                SupplierEntity(
                    supplierCode = "SUP-0001",
                    name = "Prime Telecom Distributions",
                    company = "Prime Distribution Ltd.",
                    phone = "01711234567",
                    whatsApp = "01711234567",
                    email = "orders@primetelecom.com",
                    address = "Motijheel C/A, Dhaka",
                    openingDue = 15000.0,
                    currentDue = 15000.0,
                    notes = "Official distributor for Apple & Samsung"
                ),
                SupplierEntity(
                    supplierCode = "SUP-0002",
                    name = "Star Tech Wholesale",
                    company = "Star Accessories Hub",
                    phone = "01819876543",
                    whatsApp = "01819876543",
                    email = "sales@startechbd.com",
                    address = "BCS Computer City, IDB Bhaban, Dhaka",
                    openingDue = 8500.0,
                    currentDue = 8500.0,
                    notes = "Supplies chargers, cables, routers & peripherals"
                )
            )
            val supplierIds = suppliers.map { s ->
                val id = database.masterDao().insertSupplier(s)
                if (s.openingDue > 0) {
                    database.supplierLedgerDao().insertLedgerEntry(
                        SupplierLedgerEntryEntity(
                            supplierId = id,
                            transactionType = "OPENING_BALANCE",
                            referenceId = id,
                            referenceNumber = "OPENING",
                            amount = s.openingDue,
                            signedAmount = s.openingDue,
                            runningBalance = s.openingDue,
                            description = "Opening balance carried forward",
                            createdBy = "System"
                        )
                    )
                }
                id
            }

            // 4. Customers
            val customers = listOf(
                CustomerEntity(
                    customerCode = "CUS-S001",
                    name = "Walk-in Cash Customer",
                    phone = "01700000000",
                    customerType = "CASH",
                    currentDue = 0.0
                ),
                CustomerEntity(
                    customerCode = "CUS-S002",
                    name = "Tanvir Ahmed",
                    phone = "01912345678",
                    address = "Dhanmondi 27, Dhaka",
                    email = "tanvir.ahmed@gmail.com",
                    customerType = "REGULAR",
                    openingDue = 3500.0,
                    currentDue = 3500.0,
                    notes = "Loyal regular customer"
                ),
                CustomerEntity(
                    customerCode = "CUS-S003",
                    name = "Rafiqul Islam (Corporate)",
                    phone = "01687654321",
                    address = "Gulshan-1, Dhaka",
                    email = "rafiq@techsol.bd",
                    customerType = "CREDIT",
                    openingDue = 12000.0,
                    currentDue = 12000.0,
                    notes = "Corporate IT accessories purchaser"
                )
            )
            customers.forEach { c ->
                val id = database.masterDao().insertCustomer(c)
                // The ledger is the source of truth for dues, so opening dues need their entry
                if (c.openingDue != 0.0) {
                    database.customerLedgerDao().insertLedgerEntry(
                        CustomerLedgerEntryEntity(
                            customerId = id,
                            transactionType = "OPENING_BALANCE",
                            referenceId = id,
                            referenceNumber = "OPENING",
                            amount = kotlin.math.abs(c.openingDue),
                            signedAmount = c.openingDue,
                            runningBalance = c.openingDue,
                            description = "Opening balance carried forward",
                            createdBy = "System"
                        )
                    )
                }
            }

            // 5. Products
            val p1 = database.productDao().insertProduct(
                ProductEntity(
                    name = "iPhone 15 (128GB Black)",
                    sku = "IP15-128-BLK",
                    barcode = "195949038234",
                    categoryId = catIds[0],
                    categoryName = "Mobile Phones",
                    brandId = brandIds[0],
                    brandName = "Apple",
                    model = "iPhone 15",
                    variant = "128GB",
                    color = "Black",
                    purchasePrice = 88000.0,
                    wholesalePrice = 93000.0,
                    retailPrice = 96500.0,
                    minSellingPrice = 94000.0,
                    currentStock = 4,
                    minStock = 2,
                    supplierId = supplierIds[0],
                    supplierName = "Prime Telecom Distributions",
                    warrantyPeriodMonths = 12,
                    warrantyType = "REPLACEMENT",
                    trackingType = TrackingType.SERIAL_AND_IMEI,
                    isSerialRequired = true,
                    isImeiRequired = true
                )
            )

            // Seed IMEIs for iPhone 15
            val imeis = listOf("864501061234567", "864501061234568", "864501061234569", "864501061234570")
            imeis.forEachIndexed { idx, imei ->
                database.productDao().insertSerialImei(
                    ProductSerialImeiEntity(
                        productId = p1,
                        imei1 = imei,
                        imei2 = (imei.toLong() + 500000).toString(),
                        serialNumber = "F2LX${idx}A099",
                        ram = "6GB",
                        storage = "128GB",
                        color = "Black",
                        purchasePrice = 88000.0,
                        sellingPrice = 96500.0,
                        supplierId = supplierIds[0],
                        status = "IN_STOCK",
                        warrantyPeriodMonths = 12
                    )
                )
            }

            val p2 = database.productDao().insertProduct(
                ProductEntity(
                    name = "Samsung Galaxy S24 (8/256GB Onyx Black)",
                    sku = "SAM-S24-256",
                    barcode = "8806095310214",
                    categoryId = catIds[0],
                    categoryName = "Mobile Phones",
                    brandId = brandIds[1],
                    brandName = "Samsung",
                    model = "Galaxy S24",
                    variant = "8GB/256GB",
                    color = "Onyx Black",
                    purchasePrice = 76000.0,
                    wholesalePrice = 81000.0,
                    retailPrice = 84999.0,
                    minSellingPrice = 82000.0,
                    currentStock = 3,
                    minStock = 2,
                    supplierId = supplierIds[0],
                    supplierName = "Prime Telecom Distributions",
                    warrantyPeriodMonths = 12,
                    trackingType = TrackingType.SERIAL_AND_IMEI,
                    isSerialRequired = true,
                    isImeiRequired = true
                )
            )
            listOf("358901099876541", "358901099876542", "358901099876543").forEachIndexed { idx, imei ->
                database.productDao().insertSerialImei(
                    ProductSerialImeiEntity(
                        productId = p2,
                        imei1 = imei,
                        imei2 = (imei.toLong() + 300000).toString(),
                        serialNumber = "R58X${idx}B101",
                        ram = "8GB",
                        storage = "256GB",
                        color = "Onyx Black",
                        purchasePrice = 76000.0,
                        sellingPrice = 84999.0,
                        supplierId = supplierIds[0],
                        status = "IN_STOCK",
                        warrantyPeriodMonths = 12
                    )
                )
            }

            database.productDao().insertProduct(
                ProductEntity(
                    name = "Anker 312 25W USB-C Fast Charger",
                    sku = "ANK-312-25W",
                    barcode = "194644140021",
                    categoryId = catIds[2],
                    categoryName = "Chargers & Adapters",
                    brandId = brandIds[7],
                    brandName = "Anker",
                    model = "A2640",
                    unit = "Pcs",
                    purchasePrice = 1100.0,
                    wholesalePrice = 1350.0,
                    retailPrice = 1599.0,
                    minSellingPrice = 1450.0,
                    currentStock = 25,
                    minStock = 5,
                    supplierId = supplierIds[1],
                    supplierName = "Star Tech Wholesale",
                    warrantyPeriodMonths = 18,
                    warrantyType = "REPLACEMENT"
                )
            )

            database.productDao().insertProduct(
                ProductEntity(
                    name = "Anker PowerLine+ II USB-C to Lightning Cable 3ft",
                    sku = "ANK-C-LTG-3FT",
                    barcode = "194644020996",
                    categoryId = catIds[3],
                    categoryName = "Cables & Converters",
                    brandId = brandIds[7],
                    brandName = "Anker",
                    unit = "Pcs",
                    purchasePrice = 750.0,
                    wholesalePrice = 950.0,
                    retailPrice = 1199.0,
                    currentStock = 30,
                    minStock = 5,
                    supplierId = supplierIds[1],
                    supplierName = "Star Tech Wholesale",
                    warrantyPeriodMonths = 12
                )
            )

            database.productDao().insertProduct(
                ProductEntity(
                    name = "TP-Link Archer C6 AC1200 Dual-Band Gigabit Router",
                    sku = "TPL-ARCHER-C6",
                    barcode = "6935364084202",
                    categoryId = catIds[7],
                    categoryName = "Routers & Networking",
                    brandId = brandIds[5],
                    brandName = "TP-Link",
                    unit = "Pcs",
                    purchasePrice = 2850.0,
                    wholesalePrice = 3150.0,
                    retailPrice = 3499.0,
                    currentStock = 12,
                    minStock = 3,
                    supplierId = supplierIds[1],
                    warrantyPeriodMonths = 24
                )
            )

            database.productDao().insertProduct(
                ProductEntity(
                    name = "Logitech B100 Optical USB Mouse",
                    sku = "LOG-B100-USB",
                    barcode = "097855063465",
                    categoryId = catIds[8],
                    categoryName = "Computer Accessories",
                    brandId = brandIds[6],
                    brandName = "Logitech",
                    unit = "Pcs",
                    purchasePrice = 280.0,
                    wholesalePrice = 350.0,
                    retailPrice = 450.0,
                    currentStock = 40,
                    minStock = 10,
                    supplierId = supplierIds[1],
                    warrantyPeriodMonths = 36
                )
            )

            database.productDao().insertProduct(
                ProductEntity(
                    name = "SanDisk Ultra 64GB USB 3.0 Flash Drive",
                    sku = "SD-CZ48-64G",
                    barcode = "619659102173",
                    categoryId = catIds[9],
                    categoryName = "Storage & Memory",
                    brandId = brandIds[8],
                    brandName = "SanDisk",
                    unit = "Pcs",
                    purchasePrice = 480.0,
                    wholesalePrice = 600.0,
                    retailPrice = 750.0,
                    currentStock = 35,
                    minStock = 8,
                    supplierId = supplierIds[1],
                    warrantyPeriodMonths = 60
                )
            )

            // Opening stock records, so every unit on the shelf has a stock movement behind it
            for (product in database.productDao().getAllProductsSync().filter { it.currentStock > 0 }) {
                database.stockDao().insertStockTransaction(
                    StockTransactionEntity(
                        productId = product.id,
                        productName = product.name,
                        transactionType = "OPENING_STOCK",
                        quantityChange = product.currentStock,
                        previousStock = 0,
                        newStock = product.currentStock,
                        unitCost = product.purchasePrice,
                        referenceId = product.id,
                        referenceType = "PRODUCT",
                        balanceAfter = product.currentStock,
                        createdBy = "System",
                        notes = "Sample opening stock"
                    )
                )
            }

            // Mark sample data loaded
            val profile = database.settingsAndAuditDao().getShopProfileSync() ?: ShopProfileEntity()
            database.settingsAndAuditDao().saveShopProfile(profile.copy(isSampleDataLoaded = true))
        }
    }

    // Complete Database Backup to JSON
    suspend fun exportDatabaseToJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("appName", "ElectroPOS")

        val products = database.productDao().getAllProductsSync()
        val prodArray = JSONArray()
        for (p in products) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("sku", p.sku)
            obj.put("barcode", p.barcode)
            obj.put("name", p.name)
            obj.put("categoryName", p.categoryName)
            obj.put("brandName", p.brandName)
            obj.put("purchasePrice", p.purchasePrice)
            obj.put("retailPrice", p.retailPrice)
            obj.put("currentStock", p.currentStock)
            obj.put("warrantyPeriodMonths", p.warrantyPeriodMonths)
            obj.put("isImeiRequired", p.isImeiRequired)
            prodArray.put(obj)
        }
        root.put("products", prodArray)

        val sales = database.saleDao().getAllSalesSync()
        val saleArray = JSONArray()
        for (s in sales) {
            val obj = JSONObject()
            obj.put("invoiceNumber", s.invoiceNumber)
            obj.put("customerName", s.customerName)
            obj.put("totalAmount", s.totalAmount)
            obj.put("paidAmount", s.paidAmount)
            obj.put("dueAmount", s.dueAmount)
            obj.put("saleDate", s.saleDate)
            saleArray.put(obj)
        }
        root.put("sales", saleArray)

        return root.toString(2)
    }
}
