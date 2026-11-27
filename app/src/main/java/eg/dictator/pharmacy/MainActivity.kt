package eg.dictator.pharmacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import eg.dictator.pharmacy.network.PharmacyApi
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

private fun startOfDay(): Long {
    val calendar = java.util.Calendar.getInstance()
    calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
    calendar.set(java.util.Calendar.MINUTE, 0)
    calendar.set(java.util.Calendar.SECOND, 0)
    calendar.set(java.util.Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private val Ink = Color(0xFF101116)
private val Panel = Color(0xFF1A1C24)
private val Crimson = Color(0xFF650D24)
private val Blue = Color(0xFF183A66)
private val Muted = Color(0xFF9BA1AE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var session by remember { mutableStateOf(SecureSessionStore.read(this)) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Crimson, secondary = Blue, background = Ink, surface = Panel)) {
                if (session == null) {
                    PharmacyOnboardingScreen(onRegistered = { created -> session = created })
                } else {
                    val current = session!!
                    val dao = remember(current.pharmacyId) {
                        PharmacyDatabase.get(this, current.pharmacyId).medicineDao()
                    }
                    val vm: PharmacyViewModel = viewModel(
                        key = "pharmacy_${current.pharmacyId}",
                        factory = PharmacyViewModel.factory(dao)
                    )
                    PharmacyScreen(vm)
                }
            }
        }
    }
}


@Composable
private fun PharmacyOnboardingScreen(onRegistered: (SecureSessionStore.Session) -> Unit) {
    var pharmacyName by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("جهاز المدير") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Ink).padding(22.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("DICTATOR", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("PHARMACY", color = Color(0xFFB9C5D8), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Divider(color = Crimson)
            Text("ابدأ بإعداد صيدليتك", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("أنشئ مساحة مستقلة لصيدليتك. هذا الجهاز سيصبح جهاز المدير الأول، ويمكن إضافة الموظفين لاحقًا بدعوات.", color = Muted)
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
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("إنشاء الصيدلية")
            }
            Text("بإنشاء الصيدلية يصبح هذا الجهاز مديرًا. لا تشارك رمز الجهاز مع أي شخص.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

class PharmacyViewModel(private val dao: MedicineDao) : ViewModel() {
    val searchText = MutableStateFlow("")
    val medicines = searchText.flatMapLatest { dao.search(it.trim()) }
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
        viewModelScope.launch { try { done(Result.success(dao.createDebt(name, lines, paid))) } catch (e: Exception) { done(Result.failure(e)) } }
    }
    fun payDebt(id: Long, amount: Double, done: (Result<Unit>) -> Unit) {
        viewModelScope.launch { try { dao.recordDebtPayment(id, amount); done(Result.success(Unit)) } catch (e: Exception) { done(Result.failure(e)) } }
    }
    fun settleDebt(id: Long, done: (Result<Unit>) -> Unit) {
        viewModelScope.launch { try { dao.markDebtPaid(id); done(Result.success(Unit)) } catch (e: Exception) { done(Result.failure(e)) } }
    }
    private val reportFrom = MutableStateFlow(startOfDay())
    private val reportTo = MutableStateFlow(startOfDay() + 24L * 60 * 60 * 1000)
    val reportRevenue = combine(reportFrom, reportTo) { from, to -> from to to }
        .flatMapLatest { (from, to) -> dao.revenueBetween(from, to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val reportCount = combine(reportFrom, reportTo) { from, to -> from to to }
        .flatMapLatest { (from, to) -> dao.salesCountBetween(from, to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val reportTopSelling = combine(reportFrom, reportTo) { from, to -> from to to }
        .flatMapLatest { (from, to) -> dao.topSelling(from, to) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setReportRange(from: Long, to: Long) {
        reportFrom.value = from
        reportTo.value = to
    }

    fun addMedicine(name: String, category: String, price: Double, quantity: Int) {
        viewModelScope.launch {
            dao.insertMedicine(Medicine(name = name.trim(), category = category.trim(), sellingPrice = price, quantity = quantity))
        }
    }

    fun adjustStock(id: Long, delta: Int, reason: String) {
        viewModelScope.launch { dao.adjustStock(id, delta, reason) }
    }

    fun completeSale(lines: List<SaleLineRequest>, onResult: (Result<Long>) -> Unit) {
        viewModelScope.launch {
            try { onResult(Result.success(dao.completeSale(lines))) }
            catch (error: Exception) { onResult(Result.failure(error)) }
        }
    }

    companion object {
        fun factory(dao: MedicineDao) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PharmacyViewModel(dao) as T
        }
    }
}

@Composable
private fun PharmacyScreen(vm: PharmacyViewModel) {
    var search by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var showSale by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var showDebts by remember { mutableStateOf(false) }
    var showManager by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Medicine?>(null) }
    val medicines by vm.medicines.collectAsState()
    val allMedicines by vm.allMedicines.collectAsState()
    val lowStock by vm.lowStock.collectAsState()
    val outOfStock by vm.outOfStock.collectAsState()
    val recentSales by vm.recentSales.collectAsState()
    val activeDebts by vm.activeDebts.collectAsState()
    val paidDebts by vm.paidDebts.collectAsState()
    val reportRevenue by vm.reportRevenue.collectAsState()
    val reportCount by vm.reportCount.collectAsState()
    val reportTopSelling by vm.reportTopSelling.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val visibleMedicines = when (selectedTab) {
        1 -> lowStock
        2 -> outOfStock
        else -> medicines
    }.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) || it.category.contains(search, ignoreCase = true) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            Column(Modifier.fillMaxWidth().background(Panel).padding(18.dp)) {
                Text("DICTATOR", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("PHARMACY MANAGEMENT SYSTEM", color = Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(8.dp))
                Text("إدارة الصيدلية", color = Color.White)
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }, containerColor = Crimson, contentColor = Color.White) {
                Icon(Icons.Default.Add, contentDescription = "إضافة دواء")
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it; vm.searchText.value = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("ابحث باسم الدواء أو جزء منه") },
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { showSale = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Crimson)) { Text("بدء عملية بيع") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showReports = true }, modifier = Modifier.fillMaxWidth()) { Text("سجل الفواتير وتقارير المبيعات") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showDebts = true }, modifier = Modifier.fillMaxWidth()) { Text("مديونيات العملاء") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showManager = true }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("لوحة المدير") }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryTile("كل الأدوية", medicines.size.toString(), Modifier.weight(1f))
                SummaryTile("مخزون منخفض", lowStock.size.toString(), Modifier.weight(1f))
                SummaryTile("نفد", outOfStock.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = selectedTab, containerColor = Panel, contentColor = Color.White) {
                listOf("الكل", "نواقص", "نفد").forEachIndexed { index, label ->
                    Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(label) })
                }
            }
            Spacer(Modifier.height(12.dp))
            if (visibleMedicines.isEmpty()) {
                Text(when (selectedTab) {
                    1 -> "لا توجد أصناف منخفضة المخزون."
                    2 -> "لا توجد أصناف نفد مخزونها."
                    else -> "لا توجد أدوية مطابقة. اضغط + لإضافة دواء."
                }, color = Muted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visibleMedicines, key = { it.id }) { medicine ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(medicine.name, color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(medicine.category, color = Muted)
                                        Text("السعر: " + medicine.sellingPrice + " ج.م", color = Color.White)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(medicine.quantity.toString(), color = Color.White, style = MaterialTheme.typography.headlineSmall)
                                        Text("عبوة", color = Muted)
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { vm.adjustStock(medicine.id, -1, "خصم يدوي") }, enabled = medicine.quantity > 0) {
                                        Icon(Icons.Default.Remove, contentDescription = null)
                                        Text("1")
                                    }
                                    Button(onClick = { selected = medicine }, colors = ButtonDefaults.buttonColors(containerColor = Crimson)) {
                                        Text("تعديل الكمية")
                                    }
                                    Button(onClick = { vm.adjustStock(medicine.id, 1, "إضافة يدوية") }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Text("1")
                                    }
                                }
                                if (medicine.quantity == 0) Text("نفد المخزون", color = Color(0xFFFF7777))
                                else if (medicine.quantity <= medicine.minimumQuantity) Text("مخزون منخفض", color = Color(0xFFFFC36B))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManager) ManagerDashboardDialog(
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

    if (showReports) SalesReportDialog(
        sales = recentSales,
        revenue = reportRevenue,
        salesCount = reportCount,
        topSelling = reportTopSelling,
        onRangeChanged = vm::setReportRange,
        onDismiss = { showReports = false }
    )

    if (showSale) SaleDialog(
        medicines = allMedicines,
        onDismiss = { showSale = false },
        onComplete = { lines, callback -> vm.completeSale(lines, callback) }
    )

    if (showAdd) AddMedicineDialog(
        onDismiss = { showAdd = false },
        onSave = { name, category, price, quantity ->
            vm.addMedicine(name, category, price, quantity)
            showAdd = false
        }
    )

    selected?.let { medicine ->
        AdjustStockDialog(
            medicine = medicine,
            onDismiss = { selected = null },
            onApply = { delta ->
                vm.adjustStock(medicine.id, delta, "تعديل كمية يدوي")
                selected = null
            }
        )
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun AddMedicineDialog(onDismiss: () -> Unit, onSave: (String, String, Double, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة دواء") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("اسم الدواء") })
                OutlinedTextField(category, { category = it }, label = { Text("القسم") })
                OutlinedTextField(price, { price = it }, label = { Text("سعر البيع") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(quantity, { quantity = it.filter(Char::isDigit) }, label = { Text("الكمية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull()
                val q = quantity.toIntOrNull()
                if (name.isNotBlank() && category.isNotBlank() && p != null && p >= 0 && q != null && q >= 0) onSave(name, category, p, q)
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun AdjustStockDialog(medicine: Medicine, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var amount by remember { mutableStateOf("1") }
    var adding by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تعديل مخزون " + medicine.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { adding = true }) { Text("إضافة") }
                    OutlinedButton(onClick = { adding = false }) { Text("خصم") }
                }
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("الكمية") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text("المخزون الحالي: " + medicine.quantity)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = amount.toIntOrNull()
                if (value != null && value > 0 && (adding || value <= medicine.quantity)) onApply(if (adding) value else -value)
            }) { Text("تأكيد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}


@Composable
private fun SaleDialog(
    medicines: List<Medicine>,
    onDismiss: () -> Unit,
    onComplete: (List<SaleLineRequest>, (Result<Long>) -> Unit) -> Unit
) {
    val cart = remember { mutableStateMapOf<Long, Int>() }
    var query by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val filtered = medicines.filter {
        it.quantity > 0 && (query.isBlank() || it.name.contains(query, ignoreCase = true) || it.category.contains(query, ignoreCase = true))
    }
    val cartMedicines = medicines.filter { (cart[it.id] ?: 0) > 0 }
    val total = cartMedicines.sumOf { it.sellingPrice * (cart[it.id] ?: 0) }

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
                            Text(medicine.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text("${medicine.sellingPrice} ج.م • المتاح ${medicine.quantity}", color = Muted)
                        }
                        TextButton(onClick = {
                            val current = cart[medicine.id] ?: 0
                            if (current < medicine.quantity) cart[medicine.id] = current + 1
                        }) { Text("+") }
                        Text((cart[medicine.id] ?: 0).toString(), color = Color.White)
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
                Text("الإجمالي: %.2f ج.م".format(total), color = Color.White, fontWeight = FontWeight.Bold)
                message?.let { Text(it, color = Color(0xFFFF8888)) }
            }
        },
        confirmButton = {
            TextButton(enabled = cart.isNotEmpty() && !saving, onClick = {
                saving = true
                val lines = cart.map { SaleLineRequest(it.key, it.value) }
                onComplete(lines) { result ->
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
                        Text("%.2f ج.م".format(revenue), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
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
                Text("آخر الفواتير", fontWeight = FontWeight.Bold)
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
                        Text(d.customerName, color = Color.White, fontWeight = FontWeight.Bold)
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
            medicines.filter { it.quantity > 0 && (query.isBlank() || it.name.contains(query, true)) }.take(60).forEach { m ->
                Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(m.name, color = Color.White); Text("%.2f ج.م".format(m.sellingPrice), color = Muted) }
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
                        Text("${activeDebts.size} مديونية", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("إجمالي المتبقي: %.2f ج.م".format(debtBalance), color = Color(0xFFFFC36B))
                    }
                }
                Divider()
                Text("إدارة الأجهزة", fontWeight = FontWeight.Bold)
                Text("ربط الأجهزة ومزامنة المخزون والمبيعات يحتاجان إلى إعداد خدمة الخادم الآمنة أولًا.", color = Muted)
                Text("دعوات الانضمام بالكود وQR ستظهر هنا بعد تفعيل الخادم. لم يتم إنشاء رمز وهمي أو ربط غير فعلي.", color = Color(0xFFFFC36B))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}
