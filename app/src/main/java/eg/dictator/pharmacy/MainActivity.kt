package eg.dictator.pharmacy

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import eg.dictator.pharmacy.network.PharmacyApi
import eg.dictator.pharmacy.network.PharmacyDataApi
import eg.dictator.pharmacy.network.ManagerApi
import eg.dictator.pharmacy.network.PharmacyDevice
import eg.dictator.pharmacy.security.SecureSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eg.dictator.pharmacy.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private fun normalizeMedicineQuery(value: String): String = value.lowercase()
    .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا').replace('ٱ', 'ا')
    .replace('ى', 'ي').trim()

private fun startOfDay(): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private val Ink = Color(0xFFF4F2ED)
private val Panel = Color(0xFFFFFFFF)
private val Crimson = Color(0xFF183B4E)
private val Blue = Color(0xFF2F6D68)
private val Muted = Color(0xFF687780)
private val Champagne = Color(0xFFC5A46D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var session by remember { mutableStateOf(SecureSessionStore.read(this)) }
            MaterialTheme(colorScheme = lightColorScheme(primary = Crimson, secondary = Blue, background = Ink, surface = Panel)) {
                if (session == null) {
                    PharmacyOnboardingScreen(onRegistered = { created -> session = created })
                } else {
                    val current = session!!
                    val dao = remember(current.pharmacyId) {
                        PharmacyDatabase.get(this, current.pharmacyId).medicineDao()
                    }
                    val vm: PharmacyViewModel = viewModel(
                        key = "pharmacy_${current.pharmacyId}",
                        factory = PharmacyViewModel.factory(dao, current)
                    )
                    LaunchedEffect(current.pharmacyId) { MedicineReferenceImporter.ensureImported(this@MainActivity, dao) }
                    PharmacyScreen(vm, current)
                }
            }
        }
    }
}


@Composable
private fun PharmacyOnboardingScreen(onRegistered: (SecureSessionStore.Session) -> Unit) {
    var pharmacyName by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("جهاز المدير") }
    var signInMode by remember { mutableStateOf(false) }
    var joinMode by remember { mutableStateOf(false) }
    var inviteCode by remember { mutableStateOf("") }
    var deviceToken by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Ink).padding(22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("DICTATOR", color = Color(0xFF172B36), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("PHARMACY", color = Color(0xFFB9C5D8), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Divider(color = Crimson)
            Text("اختر طريقة الدخول", color = Color(0xFF172B36), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("مسار المدير لإنشاء الصيدلية، ومسار الموظف للانضمام بدعوة أو الدخول بجهاز تمت الموافقة عليه.", color = Muted)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { signInMode = false; joinMode = false; deviceName = "جهاز المدير"; error = null }, modifier = Modifier.fillMaxWidth()) { Text("مدير الصيدلية • إنشاء صيدلية") }
                OutlinedButton(onClick = { signInMode = false; joinMode = true; deviceName = "جهاز موظف"; error = null }, modifier = Modifier.fillMaxWidth()) { Text("موظف • الانضمام بكود دعوة") }
                OutlinedButton(onClick = { signInMode = true; joinMode = false; error = null }, modifier = Modifier.fillMaxWidth()) { Text("مدير أو موظف • دخول جهاز معتمد") }
            }
            if (signInMode) {
                Text("دخول جهاز تمت الموافقة عليه من مدير الصيدلية", color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = deviceToken, onValueChange = { deviceToken = it.trim(); error = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("رمز دخول الجهاز") },
                    singleLine = true, enabled = !loading
                )
                error?.let { Text(it, color = Color(0xFFFF7777)) }
                Button(
                    onClick = {
                        if (deviceToken.length < 32) {
                            error = "أدخل رمز الدخول الذي أرسله مدير الصيدلية"
                        } else {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    val signedIn = withContext(Dispatchers.IO) {
                                        PharmacyApi.signInWithDeviceToken(deviceToken)
                                    }
                                    val saved = SecureSessionStore.Session(
                                        token = signedIn.token, pharmacyId = signedIn.pharmacyId,
                                        pharmacyName = signedIn.pharmacyName, deviceId = signedIn.deviceId,
                                        role = signedIn.role
                                    )
                                    SecureSessionStore.save(context, saved)
                                    onRegistered(saved)
                                } catch (e: Exception) {
                                    error = e.message ?: "تعذر تسجيل الدخول"
                                } finally { loading = false }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Crimson)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF172B36), strokeWidth = 2.dp)
                    else Text("تسجيل الدخول")
                }
            } else if (joinMode) {
                Text("انضمام الموظف إلى صيدلية موجودة", color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = inviteCode, onValueChange = { inviteCode = it.filter { ch -> ch.isLetterOrDigit() }.take(16).uppercase(); error = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("رمز دعوة المدير") },
                    singleLine = true, enabled = !loading
                )
                OutlinedTextField(
                    value = deviceName, onValueChange = { deviceName = it.take(80); error = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("اسم هذا الجهاز") },
                    singleLine = true, enabled = !loading
                )
                error?.let { Text(it, color = Color(0xFFFF7777)) }
                Button(
                    onClick = {
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    PharmacyApi.requestEnrollment(inviteCode, deviceName)
                                }
                                error = "تم إرسال طلب الانضمام. اطلب من المدير الموافقة على هذا الجهاز، ثم أدخل رمز الدخول الذي سيظهر لديه."
                                inviteCode = ""
                            } catch (e: Exception) {
                                error = e.message ?: "تعذر إرسال طلب الانضمام"
                            } finally { loading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !loading && inviteCode.length == 16 && deviceName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF172B36), strokeWidth = 2.dp)
                    else Text("إرسال طلب الانضمام")
                }
                Text("بعد موافقة المدير، سيعطيك رمز دخول خاص بهذا الجهاز.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(
                    value = pharmacyName, onValueChange = { pharmacyName = it.take(100); error = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("اسم الصيدلية") },
                    singleLine = true, enabled = !loading
                )
                OutlinedTextField(
                    value = deviceName, onValueChange = { deviceName = it.take(80); error = null },
                    modifier = Modifier.fillMaxWidth(), label = { Text("اسم هذا الجهاز") },
                    singleLine = true, enabled = !loading
                )
                error?.let { Text(it, color = Color(0xFFFF7777)) }
                Button(
                    onClick = {
                        if (pharmacyName.isBlank() || deviceName.isBlank()) {
                            error = "اكتب اسم الصيدلية واسم الجهاز أولًا"
                        } else {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    val created = withContext(Dispatchers.IO) {
                                        PharmacyApi.registerPharmacy(pharmacyName, deviceName)
                                    }
                                    val saved = SecureSessionStore.Session(
                                        token = created.token, pharmacyId = created.pharmacyId,
                                        pharmacyName = created.pharmacyName, deviceId = created.deviceId,
                                        role = created.role
                                    )
                                    SecureSessionStore.save(context, saved)
                                    onRegistered(saved)
                                } catch (e: Exception) {
                                    error = e.message ?: "تعذر الاتصال بالخادم"
                                } finally {
                                    loading = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !loading,
                    colors = ButtonDefaults.buttonColors(containerColor = Crimson)
                ) {
                    if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF172B36), strokeWidth = 2.dp)
                    else Text("إنشاء الصيدلية كمدير")
                }
                Text("بإنشاء الصيدلية يصبح هذا الجهاز مديرًا. لا تشارك رمز الجهاز مع أي شخص.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

class PharmacyViewModel(
    private val dao: MedicineDao,
    private val session: SecureSessionStore.Session
) : ViewModel() {
    init {
        refreshCloudCatalog()
        setReportRange(startOfDay(), startOfDay() + 24L * 60 * 60 * 1000)
    }

    private fun refreshCloudCatalog() {
        viewModelScope.launch {
            try {
                val remote = withContext(Dispatchers.IO) {
                    PharmacyDataApi.listAllMedicines(session.token)
                }
                val ids = remote.map { it.id }
                remote.forEach { item ->
                    val old = dao.medicineByRemoteId(item.id)
                    dao.upsertRemoteMedicine(
                        Medicine(
                            id = old?.id ?: 0,
                            name = item.name,
                            category = item.category.orEmpty(),
                            sellingPrice = item.salePriceMinor / 100.0,
                            quantity = item.quantity,
                            minimumQuantity = item.lowStockThreshold,
                            barcode = item.barcode,
                            imageUri = old?.imageUri,
                            updatedAt = System.currentTimeMillis(),
                            remoteId = item.id
                        )
                    )
                }
                if (ids.isNotEmpty()) dao.removeMissingRemoteMedicines(ids) else dao.clearRemoteMedicines()
                refreshCloudDebts()
            } catch (_: Exception) {
                // Keep the last locally cached catalog when temporarily offline.
            }
        }
    } 
    private suspend fun refreshCloudDebts() {
        val sales = withContext(Dispatchers.IO) { PharmacyDataApi.listAllSales(session.token) }
        sales.forEachIndexed { index, sale ->
            val oldDebt = dao.debtByRemoteSaleId(sale.id)
            if (index >= 20 && sale.dueMinor <= 0 && oldDebt == null) return@forEachIndexed
            val details = withContext(Dispatchers.IO) { PharmacyDataApi.getSaleDetails(session.token, sale.id) }

            val oldSale = dao.saleByRemoteSaleId(sale.id)
            val localSaleId = dao.upsertRemoteSale(
                Sale(
                    id = oldSale?.id ?: 0,
                    total = sale.totalMinor / 100.0,
                    createdAt = sale.createdAt * 1000L,
                    remoteSaleId = sale.id
                )
            )
            dao.clearSaleItems(localSaleId)
            if (details.items.isNotEmpty()) {
                dao.insertSaleItems(details.items.map { item ->
                    SaleItem(
                        saleId = localSaleId,
                        medicineId = dao.medicineByRemoteId(item.medicineId)?.id ?: 0L,
                        medicineName = item.medicineName,
                        unitPrice = item.unitPriceMinor / 100.0,
                        quantity = item.quantity,
                        lineTotal = item.lineTotalMinor / 100.0
                    )
                })
            }

            if (sale.dueMinor > 0 || oldDebt != null) {
                val debtId = dao.upsertRemoteDebt(
                    CustomerDebt(
                        id = oldDebt?.id ?: 0,
                        customerName = sale.customerName ?: "عميل بدون اسم",
                        totalAmount = sale.totalMinor / 100.0,
                        paidAmount = sale.paidMinor / 100.0,
                        createdAt = sale.createdAt * 1000L,
                        updatedAt = System.currentTimeMillis(),
                        remoteSaleId = sale.id
                    )
                )
                dao.clearDebtItems(debtId)
                dao.clearDebtPayments(debtId)
                if (details.items.isNotEmpty()) {
                    dao.insertDebtItems(details.items.map { item ->
                        DebtItem(
                            debtId = debtId,
                            medicineId = dao.medicineByRemoteId(item.medicineId)?.id,
                            medicineName = item.medicineName,
                            unitPrice = item.unitPriceMinor / 100.0,
                            quantity = item.quantity,
                            lineTotal = item.lineTotalMinor / 100.0
                        )
                    })
                }
                details.payments.forEach { payment ->
                    dao.insertDebtPayment(
                        DebtPayment(
                            debtId = debtId,
                            amount = payment.amountMinor / 100.0,
                            note = "دفعة سحابية",
                            createdAt = payment.createdAt * 1000L
                        )
                    )
                }
            }
        }
    }
    val searchText = MutableStateFlow("")
    private val referencePage = MutableStateFlow(0)
    fun setSearchText(value: String) {
        referencePage.value = 0
        searchText.value = value
    }
    fun loadMoreReferenceMedicines() { referencePage.value += 1 }
    val referenceMedicines = combine(searchText, referencePage) { query, page -> query to page }
        .flatMapLatest { (query, page) ->
            val normalized = normalizeMedicineQuery(query)
            if (normalized.isBlank()) dao.browseMedicineReference(limit = 20, offset = page * 20)
            else dao.searchMedicineReference(normalized, limit = 20, offset = page * 20)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val referenceMedicineCount = dao.observeReferenceCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val referencePageIndex = referencePage.asStateFlow()
    val medicines = searchText.flatMapLatest { dao.search(normalizeMedicineQuery(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allMedicines = dao.allMedicines().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val lowStock = dao.lowStock().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val outOfStock = dao.outOfStock().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentSales = dao.recentSales().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val debtSort = MutableStateFlow(2)
    val activeDebts = debtSort.flatMapLatest { dao.activeDebts(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val paidDebts = dao.paidDebts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setDebtSort(sort: Int) { debtSort.value = sort }
    fun createDebt(name: String, lines: List<DebtLineRequest>, paid: Double, done: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            try {
                val remoteLines = lines.map { line ->
                    val medicine = dao.medicineById(line.medicineId) ?: error("أحد الأصناف لم يعد موجودًا")
                    val remoteId = medicine.remoteId ?: error("هذا الصنف غير مرتبط بمخزون الصيدلية السحابي")
                    remoteId to line.quantity
                }
                val remoteSaleId = withContext(Dispatchers.IO) {
                    val customer = PharmacyDataApi.listCustomers(session.token, name, 1)
                        .firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
                    val customerId = customer?.id ?: PharmacyDataApi.createCustomer(session.token, name.trim(), null, null)
                    val response = PharmacyDataApi.createSale(
                        session.token, java.util.UUID.randomUUID().toString(), remoteLines,
                        customerId, (paid * 100.0).toLong()
                    )
                    response.optString("saleId").takeIf { it.isNotBlank() }
                        ?: error("لم يصل رقم الفاتورة من الخادم")
                }
                val localId = dao.createDebt(name, lines, paid)
                dao.setDebtRemoteSaleId(localId, remoteSaleId)
                refreshCloudCatalog()
                done(Result.success(localId))
            } catch (e: Exception) { done(Result.failure(e)) }
        }
    }
    fun payDebt(id: Long, amount: Double, done: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val debt = dao.debtById(id) ?: error("المديونية غير موجودة")
                debt.remoteSaleId?.let { remoteId ->
                    withContext(Dispatchers.IO) {
                        PharmacyDataApi.addSalePayment(session.token, remoteId, (amount * 100.0).toLong())
                    }
                }
                dao.recordDebtPayment(id, amount)
                done(Result.success(Unit))
            } catch (e: Exception) { done(Result.failure(e)) }
        }
    }
    fun settleDebt(id: Long, done: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val debt = dao.debtById(id) ?: error("المديونية غير موجودة")
                val remaining = (debt.totalAmount - debt.paidAmount).coerceAtLeast(0.0)
                if (remaining > 0.000001) {
                    debt.remoteSaleId?.let { remoteId ->
                        withContext(Dispatchers.IO) {
                            PharmacyDataApi.addSalePayment(session.token, remoteId, (remaining * 100.0).toLong())
                        }
                    }
                }
                dao.markDebtPaid(id)
                done(Result.success(Unit))
            } catch (e: Exception) { done(Result.failure(e)) }
        }
    }
    private val remoteReport = MutableStateFlow(
        eg.dictator.pharmacy.network.RemoteReport(0, 0, 0, 0)
    )
    val reportRevenue = remoteReport.map { it.revenueMinor / 100.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val reportCount = remoteReport.map { it.saleCount }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val reportTopSelling = remoteReport.map { report ->
        report.topSelling.mapIndexed { index, item ->
            TopSellingMedicine(index.toLong(), item.medicineName, item.soldQuantity, item.revenueMinor / 100.0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setReportRange(from: Long, to: Long) {
        if (session.role != "manager") return
        viewModelScope.launch {
            try {
                remoteReport.value = withContext(Dispatchers.IO) {
                    PharmacyDataApi.getReport(session.token, from / 1000L, to / 1000L)
                }
            } catch (_: Exception) { }
        }
    }

    fun addMedicine(
        name: String, category: String, price: Double, quantity: Int, imageUri: String?,
        done: (Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                require(session.role == "manager") { "إضافة الأدوية للمدير فقط" }
                val remoteId = withContext(Dispatchers.IO) {
                    val id = PharmacyDataApi.createMedicine(
                        session.token, name.trim(), category.trim(), null,
                        "piece", (price * 100.0).toLong(), 3
                    )
                    if (quantity > 0) PharmacyDataApi.receiveStock(session.token, id, quantity)
                    id
                }
                dao.upsertRemoteMedicine(
                    Medicine(
                        name = name.trim(), category = category.trim(),
                        sellingPrice = price, quantity = quantity,
                        imageUri = imageUri,
                        remoteId = remoteId
                    )
                )
                done(Result.success(Unit))
            } catch (error: Exception) {
                done(Result.failure(error))
            }
        }
    }

    fun adjustStock(id: Long, delta: Int, reason: String, done: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val medicine = dao.medicineById(id) ?: error("الدواء غير موجود")
                val remoteId = medicine.remoteId ?: error("هذا الدواء غير مرتبط بالخادم")
                withContext(Dispatchers.IO) {
                    PharmacyDataApi.adjustStock(session.token, remoteId, delta, reason)
                }
                dao.setRemoteQuantity(remoteId, medicine.quantity + delta, System.currentTimeMillis())
                done(Result.success(Unit))
            } catch (error: Exception) {
                done(Result.failure(error))
            }
        }
    }

    private val pendingSaleOperations = mutableMapOf<String, String>()

    fun completeSale(lines: List<SaleLineRequest>, discountPercent: Double, onResult: (Result<String>) -> Unit) {
        viewModelScope.launch {
            try {
                val operationKey = lines.sortedBy { it.medicineId }
                    .joinToString("|") { "${it.medicineId}:${it.quantity}" } + "|discount:" + discountPercent
                val operationId = pendingSaleOperations.getOrPut(operationKey) {
                    java.util.UUID.randomUUID().toString()
                }
                require(discountPercent.isFinite() && discountPercent >= 0.0 && discountPercent <= 100.0) { "نسبة الخصم يجب أن تكون بين 0 و100" }
                val selected = lines.map { line ->
                    val medicine = dao.medicineById(line.medicineId) ?: error("أحد الأصناف لم يعد موجودًا")
                    val remoteId = medicine.remoteId ?: error("هذا الصنف محلي وغير مرتبط بمخزون الصيدلية السحابي")
                    Triple(remoteId, line.quantity, medicine.sellingPrice)
                }
                val remoteLines = selected.map { it.first to it.second }
                val subtotalMinor = selected.sumOf { (_, quantity, price) -> kotlin.math.round(price * 100.0 * quantity).toLong() }
                val discountMinor = kotlin.math.round(subtotalMinor * discountPercent / 100.0).toLong()
                val response = withContext(Dispatchers.IO) {
                    PharmacyDataApi.createSale(session.token, operationId, remoteLines, discountMinor = discountMinor)
                }
                pendingSaleOperations.remove(operationKey)
                refreshCloudCatalog()
                onResult(Result.success(response.optString("invoiceNumber", response.optString("saleId"))))
            } catch (error: Exception) { onResult(Result.failure(error)) }
        }
    }

    fun returnSaleItem(saleItemId: Long, quantity: Int, reason: String, done: (Result<Double>) -> Unit) {
        viewModelScope.launch {
            try {
                require(quantity > 0) { "أدخل كمية صحيحة" }
                val item = dao.saleItemById(saleItemId) ?: error("الصنف غير موجود في الفاتورة")
                val alreadyReturned = dao.returnedQuantity(saleItemId)
                require(quantity <= item.quantity - alreadyReturned) { "الكمية المتبقية للإرجاع: " + (item.quantity - alreadyReturned).coerceAtLeast(0) }
                val medicine = dao.medicineById(item.medicineId) ?: error("الدواء غير موجود في المخزون المحلي")
                val remoteId = medicine.remoteId ?: error("الدواء غير مرتبط بمخزون الصيدلية")
                withContext(Dispatchers.IO) { PharmacyDataApi.adjustStock(session.token, remoteId, quantity, "مرتجع فاتورة #" + item.saleId + ": " + reason.trim()) }
                val refund = dao.recordSaleReturn(saleItemId, quantity, reason)
                refreshCloudCatalog()
                done(Result.success(refund))
            } catch (error: Exception) { done(Result.failure(error)) }
        }
    }
    companion object {
        fun factory(dao: MedicineDao, session: SecureSessionStore.Session) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PharmacyViewModel(dao, session) as T
        }
    }
}

@Composable
private fun PharmacyScreen(vm: PharmacyViewModel, session: SecureSessionStore.Session) {
    var search by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showSale by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showReturns by remember { mutableStateOf(false) }
    var showDebts by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Medicine?>(null) }
    var selectedReference by remember { mutableStateOf<MedicineReference?>(null) }
    val medicines by vm.medicines.collectAsState()
    val allMedicines by vm.allMedicines.collectAsState()
    val referenceMedicines by vm.referenceMedicines.collectAsState()
    val referenceMedicineCount by vm.referenceMedicineCount.collectAsState()
    val referencePageIndex by vm.referencePageIndex.collectAsState()
    val lowStock by vm.lowStock.collectAsState()
    val outOfStock by vm.outOfStock.collectAsState()
    val recentSales by vm.recentSales.collectAsState()
    val activeDebts by vm.activeDebts.collectAsState()
    val paidDebts by vm.paidDebts.collectAsState()
    val reportRevenue by vm.reportRevenue.collectAsState()
    val reportCount by vm.reportCount.collectAsState()
    val reportTopSelling by vm.reportTopSelling.collectAsState()
    var stockFilterTab by remember { mutableIntStateOf(0) }
    var activeNav by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val updateScope = rememberCoroutineScope()
    var availableRelease by remember { mutableStateOf<AppRelease?>(null) }
    var downloadedUpdate by remember { mutableStateOf<java.io.File?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("الإصدار الحالي: " + BuildConfig.VERSION_NAME) }
    val visibleMedicines = when (stockFilterTab) {
        1 -> lowStock
        2 -> outOfStock
        else -> medicines
    }.filter { search.isBlank() || normalizeMedicineQuery(it.name).contains(normalizeMedicineQuery(search)) || normalizeMedicineQuery(it.category).contains(normalizeMedicineQuery(search)) }

    Scaffold(
        containerColor = Ink,
        bottomBar = {
            NavigationBar(containerColor = Panel, contentColor = Crimson) {
                NavigationBarItem(selected = activeNav == 0, onClick = { activeNav = 0; stockFilterTab = 0 }, icon = { Icon(Icons.Default.Home, null) }, label = { Text("الرئيسية") })
                NavigationBarItem(selected = activeNav == 1, onClick = { activeNav = 1; stockFilterTab = 0 }, icon = { Icon(Icons.Default.Medication, null) }, label = { Text("الأدوية") })
                NavigationBarItem(selected = activeNav == 2, onClick = { activeNav = 2; showSale = true }, icon = { Icon(Icons.Default.PointOfSale, null) }, label = { Text("المبيعات") })
                NavigationBarItem(selected = activeNav == 3, onClick = { activeNav = 3; stockFilterTab = 1 }, icon = { Icon(Icons.Default.Inventory2, null) }, label = { Text("المخزون") })
                NavigationBarItem(selected = activeNav == 4, onClick = { activeNav = 4; showDebts = true }, icon = { Icon(Icons.Default.MoreHoriz, null) }, label = { Text("المزيد") })
            }
        },
        topBar = {
            Column(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text("DICTATOR PHARMACY", color = Crimson, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("إدارة دقيقة… وصيدلية أكثر كفاءة", color = Blue, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Text("تطوير Dictator_EG@", color = Muted, style = MaterialTheme.typography.labelSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("إدارة الصيدلية  •  ${session.pharmacyName}", color = Muted, style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Dictator_channel2"))) }) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = Blue, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("قناة تيليجرام")
                    }
                }
                Text("تتم المزامنة تلقائيًا عند توفر الإنترنت مع الاحتفاظ بالبيانات محليًا.", color = Blue, style = MaterialTheme.typography.bodySmall)
            }
        },
        floatingActionButton = {
            if (session.role == "manager") {
                FloatingActionButton(onClick = { showAdd = true }, containerColor = Crimson, contentColor = Color.White) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة دواء")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            when (activeNav) {
                0 -> {
                    Text("أهلاً بك في " + session.pharmacyName, color = Color(0xFF172B36), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("ملخص سريع لإدارة الصيدلية", color = Muted)
                    Button(onClick = { showSale = true; activeNav = 2 }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Crimson)) { Text("بدء عملية بيع") }
                    OutlinedButton(onClick = { showDebts = true; activeNav = 4 }, modifier = Modifier.fillMaxWidth()) { Text("فتح مديونيات العملاء") }
                    if (session.role == "manager") { OutlinedButton(onClick = { showReports = true }, modifier = Modifier.fillMaxWidth()) { Text("سجل الفواتير وتقارير المبيعات") }; Button(onClick = { showManager = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("لوحة المدير") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { SummaryTile("أدوية المخزون", allMedicines.size.toString(), Modifier.weight(1f)); SummaryTile("مخزون منخفض", lowStock.size.toString(), Modifier.weight(1f)); SummaryTile("نفد", outOfStock.size.toString(), Modifier.weight(1f)) }
                    Text("قائمة الأدوية موجودة في قسم الأدوية أسفل الشاشة.", color = Muted)
                }
                1 -> {
                    Text("دليل الأدوية المصري", color = Color(0xFF172B36), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (referenceMedicineCount > 0) "$referenceMedicineCount دواء مسجل في الدليل • اعرض وأضف لمخزونك" else "جارٍ تجهيز قاعدة بيانات الأدوية...", color = Muted, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = search, onValueChange = { search = it; vm.setSearchText(it) }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم الدواء بالعربي أو English أو المادة الفعالة أو الباركود") }, singleLine = true)
                    if (referenceMedicineCount == 0) {
                        Text("يتم تحميل دليل الأدوية أول مرة. تأكد من اتصال الإنترنت إذا لم تظهر القائمة.", color = Muted)
                    } else if (referenceMedicines.isEmpty()) {
                        Text(if (search.isBlank()) "جارٍ تحميل الأدوية..." else "لا توجد نتائج مطابقة. جرّب الاسم التجاري أو المادة الفعالة.", color = Muted)
                    }
                    referenceMedicines.forEach { reference ->
                        val normalizedAr = normalizeMedicineQuery(reference.commercialNameAr)
                        val normalizedEn = normalizeMedicineQuery(reference.commercialNameEn)
                        val existingMedicine = allMedicines.firstOrNull {
                            val savedName = normalizeMedicineQuery(it.name)
                            (normalizedAr.isNotBlank() && savedName.contains(normalizedAr)) ||
                                (normalizedEn.isNotBlank() && savedName.contains(normalizedEn))
                        }
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (existingMedicine?.imageUri?.isNotBlank() == true) {
                                    AsyncImage(model = existingMedicine.imageUri, contentDescription = reference.commercialNameAr, modifier = Modifier.size(68.dp), contentScale = ContentScale.Crop)
                                } else {
                                    Surface(modifier = Modifier.size(68.dp), color = Color(0xFFE9EEF0), shape = RoundedCornerShape(10.dp)) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Medication, contentDescription = "صورة العبوة غير متاحة في مصدر البيانات", tint = Crimson, modifier = Modifier.size(32.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(reference.commercialNameAr.ifBlank { reference.commercialNameEn }, color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                                    Text(reference.commercialNameEn, color = Muted, style = MaterialTheme.typography.labelSmall)
                                    if (reference.scientificName.isNotBlank()) Text(reference.scientificName, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                    Text(reference.manufacturer, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    Text("السعر المرجعي: " + (reference.priceEgp?.let { "%.2f ج.م".format(it) } ?: "غير متاح"), color = Blue, style = MaterialTheme.typography.labelMedium)
                                    if (reference.drugClass.isNotBlank()) Text(reference.drugClass.replace('.', ' '), color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    if (reference.uses.isNotBlank()) Text(reference.uses, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                                    if (existingMedicine != null) Text("في مخزون الصيدلية: " + existingMedicine.quantity, color = Blue, style = MaterialTheme.typography.labelSmall)
                                }
                                if (existingMedicine == null && session.role == "manager") {
                                    TextButton(onClick = { selectedReference = reference; showAdd = true }) { Text("إضافة للمخزون") }
                                }
                            }
                        }
                    }
                    if (referenceMedicines.size == 20 * (referencePageIndex + 1)) {
                        OutlinedButton(onClick = { vm.loadMoreReferenceMedicines() }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (search.isBlank()) "عرض 20 دواء آخر" else "عرض 20 نتيجة أخرى")
                        }
                    }
                    Text("صور العبوات ليست ضمن مصدر البيانات؛ أضف صورة حقيقية عند تسجيل الصنف.", color = Muted, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mahmoudfalous/eg-drugs"))) }) {
                        Text("مصدر دليل الأدوية: Egyptian Drug Database • CC BY 4.0")
                    }
                }
                2 -> { Text("المبيعات", color = Crimson, fontWeight = FontWeight.Bold); Button(onClick = { showSale = true }, modifier = Modifier.fillMaxWidth()) { Text("عملية بيع جديدة") }; if (session.role == "manager") OutlinedButton(onClick = { showReports = true }, modifier = Modifier.fillMaxWidth()) { Text("الفواتير والتقارير") } }
                3 -> { Text("المخزون", color = Color(0xFF172B36), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("الكل", "نواقص", "نفد").forEachIndexed { index, label -> FilterChip(selected = stockFilterTab == index, onClick = { stockFilterTab = index }, label = { Text(label) }) } }; val stockRows = when (stockFilterTab) { 1 -> lowStock; 2 -> outOfStock; else -> allMedicines }; if (stockRows.isEmpty()) Text("لا توجد أصناف في هذا القسم.", color = Muted) else MedicineStockList(stockRows, session.role == "manager") { selected = it } }
                else -> {
                    Text("المزيد", color = Crimson, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { showDebts = true }, modifier = Modifier.fillMaxWidth()) { Text("مديونيات العملاء") }
                    if (session.role == "manager") {
                        OutlinedButton(onClick = { showReports = true }, modifier = Modifier.fillMaxWidth()) { Text("التقارير والفواتير") }
                        OutlinedButton(onClick = { showManager = true }, modifier = Modifier.fillMaxWidth()) { Text("إدارة الأجهزة ولوحة المدير") }
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text("تحديث التطبيق", color = Color(0xFF172B36), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("نزّل آخر إصدار وثبّته مباشرة دون حذف التطبيق أو بيانات الصيدلية.", color = Muted)
                            Text(updateMessage, color = Blue, style = MaterialTheme.typography.bodySmall)
                            Button(enabled = !updateBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                                updateBusy = true
                                updateMessage = "جارٍ التحقق من GitHub Releases..."
                                updateScope.launch {
                                    try {
                                        val found = withContext(Dispatchers.IO) { AppUpdater.checkForUpdate() }
                                        availableRelease = found
                                        downloadedUpdate = null
                                        updateMessage = if (found == null) "أنت تستخدم أحدث إصدار متاح." else "يتوفر الإصدار " + found.tag + " — الحجم التقريبي " + if (found.apkSize > 0) "%.1f MB".format(found.apkSize / 1048576.0) else "غير معروف"
                                    } catch (error: Exception) { updateMessage = error.message ?: "تعذر التحقق من التحديث." }
                                    finally { updateBusy = false }
                                }
                            }) { Text(if (updateBusy) "جارٍ التحقق..." else "التحقق من وجود تحديث") }
                            availableRelease?.let { release ->
                                Button(enabled = !updateBusy, modifier = Modifier.fillMaxWidth(), onClick = {
                                    updateBusy = true
                                    updateMessage = "جارٍ تنزيل الإصدار " + release.tag + "..."
                                    updateScope.launch {
                                        try {
                                            val file = withContext(Dispatchers.IO) { AppUpdater.downloadRelease(context, release) }
                                            downloadedUpdate = file
                                            val opened = AppUpdater.installOrRequestPermission(context, file)
                                            updateMessage = if (opened) "تم فتح مثبت Android. أكّد التثبيت لإكمال التحديث." else "اسمح بالتثبيت من هذا المصدر، ثم ارجع واضغط تثبيت التحديث."
                                        } catch (error: Exception) { updateMessage = error.message ?: "تعذر تنزيل التحديث." }
                                        finally { updateBusy = false }
                                    }
                                }) { Text(if (updateBusy) "جارٍ التنزيل..." else "تنزيل وتثبيت " + release.tag) }
                            }
                            downloadedUpdate?.let { file ->
                                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                    try {
                                        val opened = AppUpdater.installOrRequestPermission(context, file)
                                        updateMessage = if (opened) "أكّد التثبيت في شاشة Android." else "فعّل إذن التثبيت ثم ارجع واضغط هذا الزر مرة أخرى."
                                    } catch (error: Exception) { updateMessage = error.message ?: "تعذر فتح مثبت Android." }
                                }) { Text("تثبيت التحديث الذي تم تنزيله") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManager && session.role == "manager") ManagerDashboardDialog(
        session = session,
        revenue = reportRevenue, salesCount = reportCount, lowStockCount = lowStock.size,
        outOfStockCount = outOfStock.size, activeDebts = activeDebts,
        onRangeChanged = vm::setReportRange, onDismiss = { showManager = false }
    )

    if (showDebts) CustomerDebtsDialog(
        medicines = allMedicines, debts = activeDebts, paidDebts = paidDebts,
        onSortChanged = vm::setDebtSort,
        onCreate = { name, lines, paid, done -> vm.createDebt(name, lines, paid, done) },
        onPay = { id, amount, done -> vm.payDebt(id, amount, done) },
        onSettle = { id, done -> vm.settleDebt(id, done) },
        onDismiss = { showDebts = false }
    )

    if (showReports && session.role == "manager") SalesReportDialog(
        sales = recentSales,
        revenue = reportRevenue,
        salesCount = reportCount,
        topSelling = reportTopSelling,
        onRangeChanged = vm::setReportRange,
        onReturn = { showReturns = true },
        onDismiss = { showReports = false }
    )

    if (showReturns && session.role == "manager") SaleReturnsDialog(
        sales = recentSales,
        onReturn = { saleItemId, quantity, reason, done -> vm.returnSaleItem(saleItemId, quantity, reason, done) },
        onDismiss = { showReturns = false }
    )
    if (showSale) SaleDialog(
        medicines = allMedicines,
        onDismiss = { showSale = false },
        onComplete = { lines, discountPercent, callback -> vm.completeSale(lines, discountPercent, callback) }
    )

    if (showAdd && session.role == "manager") AddMedicineDialog(
        initialName = selectedReference?.let { if (it.commercialNameAr.isNotBlank() && it.commercialNameEn.isNotBlank()) it.commercialNameAr + " / " + it.commercialNameEn else it.commercialNameAr.ifBlank { it.commercialNameEn } } ?: "",
        initialCategory = selectedReference?.drugClass ?: "",
        initialPrice = selectedReference?.priceEgp?.toString() ?: "",
        onDismiss = { showAdd = false; selectedReference = null },
        onSave = { name, category, price, quantity, imageUri, done ->
            vm.addMedicine(name, category, price, quantity, imageUri, done)
        }
    )

    if (session.role == "manager") selected?.let { medicine ->
        AdjustStockDialog(
            medicine = medicine,
            onDismiss = { selected = null },
            onApply = { delta, done ->
                vm.adjustStock(medicine.id, delta, "تعديل كمية يدوي") { result ->
                    result.onSuccess { selected = null }
                    done(result)
                }
            }
        )
    }
}

@Composable
private fun MedicineStockList(medicines: List<Medicine>, canAdjust: Boolean, onAdjust: (Medicine) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
        items(medicines, key = { it.id }) { medicine ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!medicine.imageUri.isNullOrBlank()) AsyncImage(model = medicine.imageUri, contentDescription = medicine.name, modifier = Modifier.size(62.dp), contentScale = ContentScale.Crop)
                    else Surface(modifier = Modifier.size(62.dp), color = Color(0xFFE9EEF0), shape = RoundedCornerShape(10.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Medication, contentDescription = "لا توجد صورة مضافة", tint = Crimson, modifier = Modifier.size(30.dp)) } }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(medicine.name, color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                        Text("القسم: " + medicine.category, color = Muted, style = MaterialTheme.typography.bodySmall)
                        Text("السعر: %.2f ج.م • المتاح: %d".format(medicine.sellingPrice, medicine.quantity), color = Blue, style = MaterialTheme.typography.bodySmall)
                    }
                    if (canAdjust) IconButton(onClick = { onAdjust(medicine) }) { Icon(Icons.Default.Inventory2, contentDescription = "تعديل المخزون", tint = Crimson) }
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Color(0xFF172B36), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AddMedicineDialog(
    initialName: String = "",
    initialCategory: String = "",
    initialPrice: String = "",
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Int, String?, (Result<Unit>) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var category by remember { mutableStateOf(initialCategory) }
    var price by remember { mutableStateOf(initialPrice) }
    var quantity by remember { mutableStateOf("0") }
    var imageUri by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            imageUri = uri.toString()
        }
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("إضافة دواء") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text("اسم الدواء") }, enabled = !saving)
                OutlinedTextField(category, { category = it; error = null }, label = { Text("القسم") }, enabled = !saving)
                OutlinedTextField(price, { price = it; error = null }, label = { Text("سعر البيع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), enabled = !saving)
                OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit); error = null }, label = { Text("الكمية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (imageUri != null) AsyncImage(model = imageUri, contentDescription = "صورة الدواء", modifier = Modifier.size(64.dp), contentScale = ContentScale.Crop)
                    OutlinedButton(enabled = !saving, onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text(if (imageUri == null) "إضافة صورة الدواء" else "تغيير الصورة") }
                }
                Text("الصورة المختارة تُحفظ على هذا الجهاز فقط حاليًا.", color = Muted, style = MaterialTheme.typography.labelSmall)
                error?.let { Text(it, color = Color(0xFFFF8888)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val p = price.toDoubleOrNull()
                val q = quantity.toIntOrNull()
                if (name.isBlank() || category.isBlank() || p == null || p < 0 || q == null || q < 0) {
                    error = "راجع اسم الدواء والقسم والسعر والكمية"
                } else {
                    saving = true
                    onSave(name, category, p, q, imageUri) { result ->
                        saving = false
                        result.onSuccess { onDismiss() }
                            .onFailure { error = it.message ?: "تعذر حفظ الدواء على الخادم" }
                    }
                }
            }) { Text(if (saving) "جارٍ الحفظ..." else "حفظ") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun AdjustStockDialog(
    medicine: Medicine, onDismiss: () -> Unit,
    onApply: (Int, (Result<Unit>) -> Unit) -> Unit
) {
    var amount by remember { mutableStateOf("1") }
    var adding by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("تعديل مخزون " + medicine.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !saving, onClick = { adding = true }) { Text("إضافة") }
                    OutlinedButton(enabled = !saving, onClick = { adding = false }) { Text("خصم") }
                }
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit); error = null }, label = { Text("الكمية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = !saving)
                Text("المخزون الحالي: " + medicine.quantity)
                error?.let { Text(it, color = Color(0xFFFF8888)) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                val value = amount.toIntOrNull()
                if (value == null || value <= 0 || (!adding && value > medicine.quantity)) {
                    error = "راجع الكمية المطلوبة"
                } else {
                    saving = true
                    onApply(if (adding) value else -value) { result ->
                        saving = false
                        result.onFailure { error = it.message ?: "تعذر تعديل المخزون" }
                    }
                }
            }) { Text(if (saving) "جارٍ الحفظ..." else "تأكيد") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("إلغاء") } }
    )
}


@Composable
private fun SaleDialog(
    medicines: List<Medicine>,
    onDismiss: () -> Unit,
    onComplete: (List<SaleLineRequest>, Double, (Result<String>) -> Unit) -> Unit
) {
    val cart = remember { mutableStateMapOf<Long, Int>() }
    var query by remember { mutableStateOf("") }
    var discountText by remember { mutableStateOf("0") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val filtered = medicines.filter {
        it.quantity > 0 && (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
    }
    val cartMedicines = medicines.filter { (cart[it.id] ?: 0) > 0 }
    val subtotal = cartMedicines.sumOf { it.sellingPrice * (cart[it.id] ?: 0) }
    val discountPercent = discountText.toDoubleOrNull()?.takeIf { it >= 0.0 && it <= 100.0 } ?: 0.0
    val discountAmount = subtotal * discountPercent / 100.0
    val total = subtotal - discountAmount

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("عملية بيع جديدة") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن دواء") }, singleLine = true)
                Text("الأصناف المتاحة", fontWeight = FontWeight.Bold)
                filtered.take(80).forEach { medicine ->
                    Row(
                        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(medicine.name, color = Color(0xFF172B36), fontWeight = FontWeight.SemiBold)
                            Text("${medicine.sellingPrice} ج.م • المتاح ${medicine.quantity}", color = Muted)
                        }
                        TextButton(onClick = {
                            val current = cart[medicine.id] ?: 0
                            if (current < medicine.quantity) cart[medicine.id] = current + 1
                        }) { Text("+") }
                        Text((cart[medicine.id] ?: 0).toString(), color = Color(0xFF172B36))
                        TextButton(onClick = {
                            val current = cart[medicine.id] ?: 0
                            if (current <= 1) cart.remove(medicine.id) else cart[medicine.id] = current - 1
                        }) { Text("−") }
                    }
                }
                Divider()
                Text("السلة", fontWeight = FontWeight.Bold)
                if (cartMedicines.isEmpty()) Text("لم تضف أي صنف بعد", color = Muted)
                cartMedicines.forEach { medicine ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(medicine.name, modifier = Modifier.weight(1f))
                        Text("${cart[medicine.id]} × ${medicine.sellingPrice} ج.م")
                    }
                }
                OutlinedTextField(discountText, { discountText = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, label = { Text("خصم بالنسبة المئوية %") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                if (discountText.toDoubleOrNull() == null || discountText.toDouble() < 0.0 || discountText.toDouble() > 100.0) Text("أدخل نسبة من 0 إلى 100", color = Color(0xFFB3261E))
                Text("قبل الخصم: %.2f ج.م".format(subtotal), color = Muted)
                Text("قيمة الخصم: %.2f ج.م".format(discountAmount), color = Blue)
                Text("الإجمالي بعد الخصم: %.2f ج.م".format(total), color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                message?.let { Text(it, color = Color(0xFFFF8888)) }
            }
        },
        confirmButton = {
            TextButton(enabled = cart.isNotEmpty() && !saving && discountText.toDoubleOrNull()?.let { it >= 0.0 && it <= 100.0 } == true, onClick = {
                saving = true
                val lines = cart.map { SaleLineRequest(it.key, it.value) }
                onComplete(lines, discountPercent) { result ->
                    saving = false
                    result.onSuccess { saleId ->
                        message = "تم تسجيل الفاتورة #$saleId"
                        cart.clear()
                        onDismiss()
                    }.onFailure { error ->
                        message = error.message ?: "تعذر إتمام عملية البيع"
                    }
                }
            }) { Text(if (saving) "جارٍ الحفظ..." else "تأكيد البيع") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("إلغاء") } }
    )
}


@Composable
private fun SalesReportDialog(
    sales: List<SaleWithItems>,
    revenue: Double,
    salesCount: Int,
    topSelling: List<TopSellingMedicine>,
    onRangeChanged: (Long, Long) -> Unit,
    onReturn: () -> Unit,
    onDismiss: () -> Unit
) {
    var period by remember { mutableIntStateOf(0) }
    var expandedSale by remember { mutableStateOf<Long?>(null) }

    fun applyPeriod(selected: Int) {
        val now = java.util.Calendar.getInstance()
        val start = java.util.Calendar.getInstance()
        when (selected) {
            0 -> {
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
            1 -> {
                start.set(java.util.Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
            else -> {
                start.set(java.util.Calendar.DAY_OF_MONTH, 1)
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
        }
        period = selected
        onRangeChanged(start.timeInMillis, now.timeInMillis + 1)
    }

    LaunchedEffect(Unit) { applyPeriod(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تقارير المبيعات") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("اليوم", "الأسبوع", "الشهر").forEachIndexed { index, label ->
                        OutlinedButton(
                            onClick = { applyPeriod(index) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (period == index) Crimson else Color.Transparent,
                                contentColor = Color.White
                            )
                        ) { Text(label) }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("إجمالي المبيعات", color = Muted)
                        Text("%.2f ج.م".format(revenue), color = Color(0xFF172B36), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("عدد الفواتير: $salesCount", color = Muted)
                    }
                }
                Text("الأكثر مبيعًا", fontWeight = FontWeight.Bold)
                if (topSelling.isEmpty()) Text("لا توجد مبيعات في هذه الفترة.", color = Muted)
                topSelling.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${item.medicineName}")
                            Text("الكمية: ${item.soldQuantity}", color = Muted)
                        }
                        Text("%.2f ج.م".format(item.revenue))
                    }
                }
                Divider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("آخر الفواتير", fontWeight = FontWeight.Bold)
                    TextButton(onClick = onReturn) { Text("تسجيل مرتجع") }
                }
                if (sales.isEmpty()) Text("لا توجد فواتير مسجلة.", color = Muted)
                sales.forEach { entry ->
                    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp)) {
                            TextButton(onClick = {
                                expandedSale = if (expandedSale == entry.sale.id) null else entry.sale.id
                            }) {
                                Text("فاتورة #${entry.sale.id} • ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.sale.createdAt))} • %.2f ج.م".format(entry.sale.total))
                            }
                            if (expandedSale == entry.sale.id) {
                                entry.items.forEach { item ->
                                    Text("${item.medicineName} — ${item.quantity} × ${item.unitPrice} = ${item.lineTotal} ج.م", color = Muted)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

@Composable
private fun SaleReturnsDialog(
    sales: List<SaleWithItems>,
    onReturn: (Long, Int, String, (Result<Double>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val quantities = remember { mutableStateMapOf<Long, String>() }
    val reasons = remember { mutableStateMapOf<Long, String>() }
    var busyItem by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مرتجعات الفواتير") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("اختر الصنف من الفاتورة وحدد الكمية. سيُعاد المخزون بعد اعتماد العملية.", color = Muted)
                if (sales.isEmpty()) Text("لا توجد فواتير محلية متاحة.", color = Muted)
                sales.forEach { sale ->
                    Text("فاتورة #" + sale.sale.id, fontWeight = FontWeight.Bold)
                    sale.items.forEach { item ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.medicineName, fontWeight = FontWeight.SemiBold)
                                Text("المباع: " + item.quantity + " • سعر الوحدة: " + "%.2f".format(item.unitPrice) + " ج.م", color = Muted)
                                OutlinedTextField(value = quantities[item.id] ?: "1", onValueChange = { quantities[item.id] = it.filter(Char::isDigit).take(5) }, label = { Text("كمية المرتجع") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), enabled = busyItem == null)
                                OutlinedTextField(value = reasons[item.id] ?: "استرجاع عميل", onValueChange = { reasons[item.id] = it }, label = { Text("سبب المرتجع") }, singleLine = true, enabled = busyItem == null)
                                Button(onClick = {
                                    val qty = quantities[item.id]?.toIntOrNull() ?: 0
                                    busyItem = item.id
                                    onReturn(item.id, qty, reasons[item.id] ?: "استرجاع عميل") { result ->
                                        busyItem = null
                                        message = result.fold({ "تم تسجيل المرتجع بقيمة " + "%.2f".format(it) + " ج.م وإعادة الكمية للمخزون. راجع تسوية المبلغ مع العميل." }, { it.message ?: "تعذر تسجيل المرتجع" })
                                    }
                                }, enabled = busyItem == null, modifier = Modifier.fillMaxWidth()) { Text(if (busyItem == item.id) "جارٍ التسجيل..." else "اعتماد المرتجع") }
                            }
                        }
                    }
                }
                if (message.isNotBlank()) Text(message, color = Blue)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}
@Composable
private fun CustomerDebtsDialog(
    medicines: List<Medicine>, debts: List<CustomerDebtWithDetails>, paidDebts: List<CustomerDebtWithDetails>,
    onSortChanged: (Int) -> Unit,
    onCreate: (String, List<DebtLineRequest>, Double, (Result<Long>) -> Unit) -> Unit,
    onPay: (Long, Double, (Result<Unit>) -> Unit) -> Unit,
    onSettle: (Long, (Result<Unit>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CustomerDebtWithDetails?>(null) }
    var sort by remember { mutableIntStateOf(2) }
    var showPaid by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("مديونيات العملاء") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Crimson)) { Text("إضافة مديونية") }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("الأحدث", "الأقدم", "الاسم", "الأعلى").forEachIndexed { i, label ->
                    OutlinedButton(onClick = { sort = i; onSortChanged(i) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 2.dp)) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (showPaid) "المديونيات المدفوعة" else "المديونيات الحالية", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { showPaid = !showPaid }) { Text(if (showPaid) "الحالية" else "المدفوعة") }
            }
            val rows = if (showPaid) paidDebts else debts
            if (rows.isEmpty()) Text("لا توجد سجلات هنا.", color = Muted)
            rows.forEach { entry ->
                val d = entry.debt
                val remaining = (d.totalAmount - d.paidAmount).coerceAtLeast(0.0)
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(d.customerName, color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                        Text("الإجمالي: %.2f • المدفوع: %.2f ج.م".format(d.totalAmount, d.paidAmount), color = Muted)
                        Text("المتبقي: %.2f ج.م".format(remaining), color = if (remaining > 0) Color(0xFFFFC36B) else Color(0xFF75C995), fontWeight = FontWeight.Bold)
                        entry.items.forEach { item -> Text("• ${item.medicineName} — ${item.quantity} × ${item.unitPrice}", color = Muted) }
                        if (remaining > 0) Row {
                            TextButton(onClick = { selected = entry }) { Text("تسجيل دفعة") }
                            TextButton(onClick = { onSettle(d.id) { result -> message = result.exceptionOrNull()?.message ?: "تم تسجيل التسوية بالكامل" } }) { Text("تم الدفع بالكامل") }
                        }
                    }
                }
            }
            message?.let { Text(it, color = Color(0xFF75C995)) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } })
    if (adding) AddCustomerDebtDialog(medicines, onDismiss = { adding = false }, onCreate = { name, lines, paid ->
        onCreate(name, lines, paid) { result ->
            result.onSuccess { adding = false; message = "تم تسجيل المديونية #${it}" }
                .onFailure { message = it.message ?: "تعذر حفظ المديونية" }
        }
    })
    selected?.let { entry ->
        DebtPaymentDialog(entry.debt.customerName, entry.debt.totalAmount - entry.debt.paidAmount,
            onDismiss = { selected = null }, onPay = { amount ->
                onPay(entry.debt.id, amount) { result ->
                    result.onSuccess { selected = null; message = "تم تسجيل الدفعة" }
                        .onFailure { message = it.message ?: "تعذر تسجيل الدفعة" }
                }
            })
    }
}

@Composable
private fun AddCustomerDebtDialog(
    medicines: List<Medicine>, onDismiss: () -> Unit,
    onCreate: (String, List<DebtLineRequest>, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var firstPayment by remember { mutableStateOf("0") }
    val cart = remember { mutableStateMapOf<Long, Int>() }
    var error by remember { mutableStateOf<String?>(null) }
    val selected = medicines.filter { (cart[it.id] ?: 0) > 0 }
    val total = selected.sumOf { it.sellingPrice * (cart[it.id] ?: 0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("إضافة مديونية") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("اسم العميل") }, singleLine = true)
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("ابحث عن دواء") }, singleLine = true)
            val matchingMedicines = medicines.filter { it.quantity > 0 && (query.isBlank() || normalizeMedicineQuery(it.name).contains(normalizeMedicineQuery(query)) || normalizeMedicineQuery(it.category).contains(normalizeMedicineQuery(query))) }.take(100)
            if (matchingMedicines.isEmpty()) Text("لا توجد أدوية متاحة بهذا الاسم في مخزون الصيدلية. أضف الدواء من قسم الأدوية أولًا.", color = Muted)
            matchingMedicines.forEach { m ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!m.imageUri.isNullOrBlank()) AsyncImage(model = m.imageUri, contentDescription = m.name, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop) else Surface(modifier = Modifier.size(48.dp), color = Color(0xFFE9EEF0), shape = RoundedCornerShape(8.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Medication, contentDescription = null, tint = Crimson) } }; Spacer(Modifier.width(8.dp)); Column(Modifier.weight(1f)) { Text(m.name, color = Color(0xFF172B36)); Text("القسم: " + m.category, color = Muted); Text("%.2f ج.م • المتاح %d".format(m.sellingPrice, m.quantity), color = Muted) }
                    TextButton(onClick = { val n = cart[m.id] ?: 0; if (n < m.quantity) cart[m.id] = n + 1 }) { Text("+") }
                    Text((cart[m.id] ?: 0).toString())
                    TextButton(onClick = { val n = cart[m.id] ?: 0; if (n <= 1) cart.remove(m.id) else cart[m.id] = n - 1 }) { Text("−") }
                }
            }
            Text("الإجمالي: %.2f ج.م".format(total), fontWeight = FontWeight.Bold)
            OutlinedTextField(firstPayment, { firstPayment = it }, modifier = Modifier.fillMaxWidth(), label = { Text("دفعة أولى (اختياري)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            error?.let { Text(it, color = Color(0xFFFF8888)) }
        }
    }, confirmButton = {
        TextButton(enabled = name.isNotBlank() && cart.isNotEmpty(), onClick = {
            val paid = firstPayment.toDoubleOrNull()
            if (paid == null || paid < 0 || paid > total) error = "راجع قيمة الدفعة الأولى"
            else onCreate(name, cart.map { DebtLineRequest(it.key, it.value) }, paid)
        }) { Text("حفظ المديونية") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}

@Composable
private fun DebtPaymentDialog(customer: String, remaining: Double, onDismiss: () -> Unit, onPay: (Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("دفعة من ${customer}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("المتبقي: %.2f ج.م".format(remaining))
            OutlinedTextField(amount, { amount = it }, label = { Text("المبلغ المدفوع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }, confirmButton = {
        TextButton(onClick = { val v = amount.toDoubleOrNull(); if (v != null && v > 0 && v <= remaining) onPay(v) }) { Text("تسجيل الدفعة") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } })
}


@Composable
private fun ManagerDashboardDialog(
    session: SecureSessionStore.Session,
    revenue: Double,
    salesCount: Int,
    lowStockCount: Int,
    outOfStockCount: Int,
    activeDebts: List<CustomerDebtWithDetails>,
    onRangeChanged: (Long, Long) -> Unit,
    onDismiss: () -> Unit
) {
    var period by remember { mutableIntStateOf(0) }
    fun applyPeriod(selected: Int) {
        val now = java.util.Calendar.getInstance()
        val start = java.util.Calendar.getInstance()
        when (selected) {
            0 -> {
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
            1 -> {
                start.set(java.util.Calendar.DAY_OF_WEEK, start.firstDayOfWeek)
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
            else -> {
                start.set(java.util.Calendar.DAY_OF_MONTH, 1)
                start.set(java.util.Calendar.HOUR_OF_DAY, 0)
                start.set(java.util.Calendar.MINUTE, 0)
                start.set(java.util.Calendar.SECOND, 0)
                start.set(java.util.Calendar.MILLISECOND, 0)
            }
        }
        period = selected
        onRangeChanged(start.timeInMillis, now.timeInMillis + 1)
    }
    LaunchedEffect(Unit) { applyPeriod(0) }
    val debtBalance = activeDebts.sumOf { (it.debt.totalAmount - it.debt.paidAmount).coerceAtLeast(0.0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("لوحة المدير") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("نظرة عامة على أداء الصيدلية", color = Muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("اليوم", "الأسبوع", "الشهر").forEachIndexed { index, label ->
                        OutlinedButton(
                            onClick = { applyPeriod(index) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (period == index) Crimson else Color.Transparent,
                                contentColor = Color.White
                            )
                        ) { Text(label) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTile("المبيعات", "%.2f".format(revenue) + " ج.م", Modifier.weight(1f))
                    SummaryTile("الفواتير", salesCount.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryTile("النواقص", lowStockCount.toString(), Modifier.weight(1f))
                    SummaryTile("النافد", outOfStockCount.toString(), Modifier.weight(1f))
                }
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("مديونيات العملاء المفتوحة", color = Muted)
                        Text("${activeDebts.size} مديونية", color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                        Text("إجمالي المتبقي: %.2f ج.م".format(debtBalance), color = Color(0xFFFFC36B))
                    }
                }
                Divider()
                ManagerDevicesSection(session)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}


@Composable
private fun ManagerDevicesSection(session: SecureSessionStore.Session) {
    var devices by remember { mutableStateOf<List<PharmacyDevice>>(emptyList()) }
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var approvedDeviceToken by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    fun refreshDevices() {
        if (session.role != "manager") return
        loading = true
        scope.launch {
            try {
                devices = withContext(Dispatchers.IO) { ManagerApi.listDevices(session.token) }
                message = null
            } catch (error: Exception) {
                message = error.message ?: "تعذر تحميل الأجهزة"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(session.deviceId) { refreshDevices() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("إدارة الأجهزة", fontWeight = FontWeight.Bold)
        if (session.role != "manager") {
            Text("إدارة الأجهزة والدعوات متاحة لمدير الصيدلية فقط.", color = Muted)
        } else {
            Button(
                onClick = {
                    loading = true
                    scope.launch {
                        try {
                            val invite = withContext(Dispatchers.IO) { ManagerApi.createInvite(session.token) }
                            inviteCode = invite.code
                            message = "صلاحية الرمز 10 دقائق ويستخدم مرة واحدة"
                            refreshDevices()
                        } catch (error: Exception) {
                            message = error.message ?: "تعذر إنشاء الدعوة"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Crimson)
            ) { Text(if (loading) "جارٍ التنفيذ..." else "إنشاء دعوة لموظف") }

            approvedDeviceToken?.let { token ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("رمز دخول الجهاز بعد الموافقة — يظهر مرة واحدة", color = Color(0xFFFFC36B))
                        Text(token, color = Color(0xFF172B36), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(token)) }) {
                            Text("نسخ رمز الجهاز")
                        }
                    }
                }
            }

            inviteCode?.let { code ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("رمز الدعوة", color = Muted)
                        Text(code, color = Color(0xFF172B36), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                            Text("نسخ الرمز")
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("أجهزة الصيدلية", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { refreshDevices() }, enabled = !loading) { Text("تحديث") }
            }
            if (devices.isEmpty() && !loading) Text("لا توجد أجهزة إضافية مسجلة حتى الآن.", color = Muted)
            devices.forEach { device ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(device.name, color = Color(0xFF172B36), fontWeight = FontWeight.Bold)
                        Text(
                            when (device.status) {
                                "pending" -> "بانتظار موافقة المدير"
                                "active" -> if (device.role == "manager") "مدير نشط" else "موظف نشط"
                                "revoked" -> "تم إيقاف الجهاز"
                                else -> device.status
                            },
                            color = if (device.status == "pending") Color(0xFFFFC36B) else Muted
                        )
                        if (device.status == "pending") {
                            Button(
                                onClick = {
                                    loading = true
                                    scope.launch {
                                        try {
                                            approvedDeviceToken = withContext(Dispatchers.IO) {
                                                ManagerApi.approveDevice(session.token, device.id)
                                            }
                                            message = "تمت الموافقة. انسخ رمز الجهاز وأرسله للموظف مرة واحدة."
                                            refreshDevices()
                                        } catch (error: Exception) {
                                            message = error.message ?: "تعذرت الموافقة"
                                        } finally { loading = false }
                                    }
                                },
                                enabled = !loading
                            ) { Text("الموافقة على الجهاز") }
                        } else if (device.status == "active" && device.role == "staff") {
                            TextButton(
                                onClick = {
                                    loading = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { ManagerApi.revokeDevice(session.token, device.id) }
                                            message = "تم إيقاف الجهاز"
                                            refreshDevices()
                                        } catch (error: Exception) {
                                            message = error.message ?: "تعذر إيقاف الجهاز"
                                        } finally { loading = false }
                                    }
                                },
                                enabled = !loading
                            ) { Text("إيقاف الجهاز", color = Color(0xFFFF8888)) }
                        }
                    }
                }
            }
            message?.let { Text(it, color = Color(0xFFFFC36B)) }
        }
    }
}
