package eg.dictator.pharmacy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import eg.dictator.pharmacy.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Ink = Color(0xFF101116)
private val Panel = Color(0xFF1A1C24)
private val Crimson = Color(0xFF650D24)
private val Blue = Color(0xFF183A66)
private val Muted = Color(0xFF9BA1AE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dao = PharmacyDatabase.get(this).medicineDao()
        setContent {
            val vm: PharmacyViewModel = viewModel(factory = PharmacyViewModel.factory(dao))
            MaterialTheme(colorScheme = darkColorScheme(primary = Crimson, secondary = Blue, background = Ink, surface = Panel)) {
                PharmacyScreen(vm)
            }
        }
    }
}

class PharmacyViewModel(private val dao: MedicineDao) : ViewModel() {
    val searchText = MutableStateFlow("")
    val medicines = searchText.flatMapLatest { dao.search(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addMedicine(name: String, category: String, price: Double, quantity: Int) {
        viewModelScope.launch {
            dao.insertMedicine(Medicine(name = name.trim(), category = category.trim(), sellingPrice = price, quantity = quantity))
        }
    }

    fun adjustStock(id: Long, delta: Int, reason: String) {
        viewModelScope.launch { dao.adjustStock(id, delta, reason) }
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
    var selected by remember { mutableStateOf<Medicine?>(null) }
    val medicines by vm.medicines.collectAsState()

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
            if (medicines.isEmpty()) {
                Text("لا توجد أدوية مسجلة بعد. اضغط + لإضافة أول دواء.", color = Muted)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(medicines, key = { it.id }) { medicine ->
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
