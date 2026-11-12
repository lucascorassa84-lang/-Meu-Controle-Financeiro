package br.com.meucontrolefinanceiro

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import android.Manifest
import android.os.Build
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Entity(tableName = "transactions")
data class Transaction(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val description: String,
 val amountCents: Long,
 val kind: String,
 val createdAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "debts")
data class Debt(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val creditor: String,
 val principalCents: Long,
 val annualInterestBps: Int,
 val installments: Int,
 val dueDate: String,
 val source: String = "MANUAL",
 val externalId: String? = null
)
@Entity(tableName = "debt_payments", indices = [Index("debtId")])
data class DebtPayment(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val debtId: Long,
 val amountCents: Long,
 val paidAt: Long = System.currentTimeMillis()
)
@Dao interface FinanceDao {
 @Query("SELECT * FROM transactions ORDER BY createdAt DESC, id DESC") fun transactions(): Flow<List<Transaction>>
 @Query("SELECT * FROM debts ORDER BY id DESC") fun debts(): Flow<List<Debt>>
 @Query("SELECT * FROM debt_payments ORDER BY paidAt DESC") fun payments(): Flow<List<DebtPayment>>
 @Insert suspend fun addTransaction(item: Transaction)
 @Delete suspend fun deleteTransaction(item: Transaction)
 @Insert suspend fun addDebt(item: Debt)
 @Insert suspend fun addPayment(item: DebtPayment)
}
@Database(entities = [Transaction::class, Debt::class, DebtPayment::class, Account::class, FinanceCard::class, CardPurchase::class, FinanceGoal::class, InstallmentPurchase::class, BillPayment::class, BankMovement::class], version = 3, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() { abstract fun dao(): FinanceDao; abstract fun extras(): ExtraFinanceDao; abstract fun billing(): BillingDao }

class MainActivity : FragmentActivity() {
 private var authenticated by mutableStateOf(false)
 private var authError by mutableStateOf("")
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val db = FinanceDbProvider.get(applicationContext)
  LocalReminders.schedule(applicationContext)
  if (Build.VERSION.SDK_INT >= 33 &&
   ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
   requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 70)
  }
  setContent {
   MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF2357C6), secondary = Color(0xFF008E7A), background = Color(0xFFF5F7FC), surface = Color.White)) {
    if (authenticated) FinanceApp(db.dao(), db.extras(), db.billing())
    else Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
     Text("Meu Controle Financeiro", style = MaterialTheme.typography.headlineMedium)
     Text("Seus dados financeiros estão protegidos pelo bloqueio de tela.")
     if (authError.isNotBlank()) Text(authError, color = MaterialTheme.colorScheme.error)
     Button(onClick = { FinanceAuth.unlock(this@MainActivity,
      onSuccess = { authenticated = true; authError = "" },
      onError = { authError = it }) }) { Text("Desbloquear com biometria ou senha do celular") }
    }
   }
  }
 }
 override fun onStop() {
  super.onStop()
  authenticated = false
 }
}
private fun cents(s: String): Long? = runCatching {
 BigDecimal(s.trim().replace(".", "").replace(",", ".")).movePointRight(2).longValueExact()
}.getOrNull()?.takeIf { it > 0 }
private fun money(v: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(v, 2))

@Composable fun FinanceApp(dao: FinanceDao, extraDao: ExtraFinanceDao, billingDao: BillingDao) {
 val transactions by dao.transactions().collectAsState(initial = emptyList())
 val debts by dao.debts().collectAsState(initial = emptyList())
 val payments by dao.payments().collectAsState(initial = emptyList())
 val scope = rememberCoroutineScope()
 var tab by remember { mutableIntStateOf(0) }
 var description by remember { mutableStateOf("") }
 var amount by remember { mutableStateOf("") }
 var kind by remember { mutableStateOf("EXPENSE") }
 var creditor by remember { mutableStateOf("") }
 var interest by remember { mutableStateOf("0") }
 var installments by remember { mutableStateOf("1") }
 var dueDate by remember { mutableStateOf("") }
 var selectedDebt by remember { mutableStateOf<Long?>(null) }
 var payment by remember { mutableStateOf("") }
 var error by remember { mutableStateOf("") }
 var pendingDelete by remember { mutableStateOf<Transaction?>(null) }
 val income = transactions.filter { it.kind == "INCOME" }.sumOf { it.amountCents }
 val expense = transactions.filter { it.kind == "EXPENSE" }.sumOf { it.amountCents }
 val outstanding = debts.sumOf { d -> (d.principalCents - payments.filter { it.debtId == d.id }.sumOf { it.amountCents }).coerceAtLeast(0) }
 val sections = listOf("Painel", "Lançamentos", "Dívidas", "Bancos", "Contas", "Cartões", "Metas", "Relatórios", "Faturas", "Conciliação", "Família online")
 Scaffold(containerColor = Color(0xFFF5F7FC), topBar = {
  Surface(color = Color(0xFF142B56)) {
   Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
    Text("MEU CONTROLE", color = Color(0xFFB5C9F0), style = MaterialTheme.typography.labelMedium)
    Text("Financeiro", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text("Seu dinheiro, com mais clareza", color = Color(0xFFD5E1F9), style = MaterialTheme.typography.bodySmall)
   }
  }
 }) { padding ->
  Column(Modifier.fillMaxSize().padding(padding)) {
   Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    sections.forEachIndexed { index, title ->
     FilterChip(selected = tab == index, onClick = { tab = index; error = "" }, label = { Text(title) }, shape = RoundedCornerShape(14.dp))
    }
   }
   Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
   if (pendingDelete != null) {
    val item = pendingDelete!!
    AlertDialog(
     onDismissRequest = { pendingDelete = null },
     title = { Text("Excluir lançamento?") },
     text = { Text("Confirma a exclusão de " + item.description + " (" + money(item.amountCents) + ")?") },
     confirmButton = { TextButton(onClick = { scope.launch { dao.deleteTransaction(item) }; pendingDelete = null }) { Text("Excluir", color = MaterialTheme.colorScheme.error) } },
     dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") } }
    )
   }
   if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
   when(tab) {
    0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
     Surface(color = Color(0xFF1C417D), shape = RoundedCornerShape(22.dp), shadowElevation = 3.dp) {
      Column(Modifier.fillMaxWidth().padding(22.dp)) {
       Text("SALDO REGISTRADO", color = Color(0xFFBFD3F8), style = MaterialTheme.typography.labelMedium)
       Spacer(Modifier.height(8.dp))
       Text(money(income - expense), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
       Spacer(Modifier.height(12.dp))
       Text("Atualizado com seus lançamentos", color = Color(0xFFD4E2FC), style = MaterialTheme.typography.bodySmall)
      }
     }
     Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      DashboardMetric("Receitas", money(income), Color(0xFF087B65), Modifier.weight(1f))
      DashboardMetric("Despesas", money(expense), Color(0xFFC24A4A), Modifier.weight(1f))
     }
     DashboardMetric("Dívidas restantes", money(outstanding), Color(0xFF294D91), Modifier.fillMaxWidth())
     Text("Acesso rápido", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
     Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Button(onClick = { tab = 1 }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("+ Lançamento") }
      OutlinedButton(onClick = { tab = 8 }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Ver faturas") }
     }
     Text("Movimentações recentes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
     Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
      Column(Modifier.fillMaxWidth().padding(12.dp)) {
       if (transactions.isEmpty()) Text("Nenhum lançamento registrado ainda.", color = Color(0xFF64748B), modifier = Modifier.padding(10.dp))
       else transactions.take(5).forEach { t ->
        ListItem(headlineContent = { Text(t.description, fontWeight = FontWeight.Medium) },
         supportingContent = { Text(if (t.kind == "INCOME") "Receita" else "Despesa") },
         trailingContent = { Text((if (t.kind == "INCOME") "+ " else "- ") + money(t.amountCents),
          color = if (t.kind == "INCOME") Color(0xFF087B65) else Color(0xFFC24A4A)) })
        HorizontalDivider(color = Color(0xFFF0F2F7))
       }
      }
     }
     Text("Valores de registros locais; não representam saldos bancários sincronizados.", color = Color(0xFF64748B), style = MaterialTheme.typography.bodySmall)
    }
    1 -> Column {
     OutlinedTextField(description, { description = it }, label = { Text("O que foi? Ex.: combustível, mercado, salário") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
     OutlinedTextField(amount, { amount = it }, label = { Text("Valor em R$") }, modifier = Modifier.fillMaxWidth())
     Row {
      FilterChip(kind == "EXPENSE", { kind = "EXPENSE" }, label = { Text("Despesa") })
      Spacer(Modifier.width(8.dp))
      FilterChip(kind == "INCOME", { kind = "INCOME" }, label = { Text("Receita") })
     }
     Button(onClick = {
      val value = cents(amount)
      if (description.isBlank() || value == null) error = "Informe o motivo do lançamento e um valor positivo."
      else {
       scope.launch { dao.addTransaction(Transaction(description = description.trim(), amountCents = value, kind = kind)) }
       description = ""; amount = ""; error = ""
      }
     }) { Text("Salvar lançamento") }
     LazyColumn { items(transactions, key = { it.id }) { t ->
      ListItem(headlineContent = { Text(t.description) }, supportingContent = { Text(if(t.kind == "INCOME") "Receita" else "Despesa") }, trailingContent = { Row(verticalAlignment = Alignment.CenterVertically) { Text(money(t.amountCents)); TextButton(onClick = { pendingDelete = t }) { Text("Excluir", color = MaterialTheme.colorScheme.error) } } })
      HorizontalDivider()
     } }
    }
    2 -> Column {
     OutlinedTextField(creditor, { creditor = it }, label = { Text("Credor") }, modifier = Modifier.fillMaxWidth())
     OutlinedTextField(amount, { amount = it }, label = { Text("Principal em R$") }, modifier = Modifier.fillMaxWidth())
     OutlinedTextField(interest, { interest = it }, label = { Text("Taxa anual % (informativa)") }, modifier = Modifier.fillMaxWidth())
     OutlinedTextField(installments, { installments = it }, label = { Text("Quantidade de parcelas") }, modifier = Modifier.fillMaxWidth())
     OutlinedTextField(dueDate, { dueDate = it }, label = { Text("Vencimento AAAA-MM-DD") }, modifier = Modifier.fillMaxWidth())
     Button(onClick = {
      val v = cents(amount)
      val bps = runCatching { BigDecimal(interest.replace(",", ".")).multiply(BigDecimal(100)).intValueExact() }.getOrNull()
      val n = installments.toIntOrNull()
      if (creditor.isBlank() || v == null || bps == null || bps < 0 || n == null || n < 1 || (dueDate.isNotBlank() && runCatching { java.time.LocalDate.parse(dueDate) }.isFailure)) error = "Dados da dívida inválidos."
      else {
       scope.launch { dao.addDebt(Debt(creditor = creditor.trim(), principalCents = v, annualInterestBps = bps, installments = n, dueDate = dueDate)) }
       creditor = ""; amount = ""; interest = "0"; installments = "1"; dueDate = ""; error = ""
      }
     }) { Text("Cadastrar dívida") }
     LazyColumn { items(debts, key = { it.id }) { d ->
      val paid = payments.filter { it.debtId == d.id }.sumOf { it.amountCents }
      val remain = (d.principalCents - paid).coerceAtLeast(0)
      Column(Modifier.padding(vertical = 8.dp)) {
       Text(d.creditor, style = MaterialTheme.typography.titleMedium)
       Text("Principal restante: " + money(remain))
       Text("Parcelas: ${d.installments} · Taxa: ${d.annualInterestBps / 100.0}% a.a.")
       val estimate = remember(d.id, remain) {
        runCatching {
         val start = if (d.dueDate.isBlank()) java.time.LocalDate.now().plusMonths(1) else java.time.LocalDate.parse(d.dueDate)
         DebtCalculator.fixedInstallments(remain, d.annualInterestBps, d.installments, start)
        }.getOrNull()
       }
       if (estimate != null) {
        Text("Simulação de parcela: " + money(estimate.first().paymentCents))
        Text("Previsão indicativa de quitação: " + estimate.last().dueDate.toString())
        Text("Juros simulados: " + money(estimate.sumOf { it.interestCents }))
        Text("Estimativa Price, sem taxas extras ou regras contratuais.")
       }
       if (remain > 0) {
        if (selectedDebt == d.id) {
         OutlinedTextField(payment, { payment = it }, label = { Text("Amortização do principal") })
         Button(onClick = {
          val v = cents(payment)
          if (v == null || v > remain) error = "Pagamento inválido."
          else {
           scope.launch { dao.addPayment(DebtPayment(debtId = d.id, amountCents = v)) }
           selectedDebt = null; payment = ""; error = ""
          }
         }) { Text("Confirmar") }
        } else TextButton(onClick = { selectedDebt = d.id }) { Text("Registrar amortização") }
       }
       HorizontalDivider()
      }
     } }
    }
    4, 5, 6 -> ExtraFinanceScreen(extraDao, tab)
    8, 9 -> BillingScreen(billingDao, dao, extraDao, tab)
    10 -> FamilyOnlineScreen()
    7 -> Column {
     Text("Relatório financeiro", style = MaterialTheme.typography.titleLarge)
     Text("Receitas: " + money(income))
     Text("Despesas: " + money(expense))
     Text("Resultado: " + money(income - expense))
     Text("Principal de dívidas: " + money(outstanding))
    }
    else -> Column {
     Text("Integrações bancárias", style = MaterialTheme.typography.titleMedium)
     Text("Nubank · Caixa · Mercado Pago · Itaú · C6 Bank")
     Text("Aguardando provedor Open Finance autorizado. Nenhuma conta está conectada.")
     Text("Seus lançamentos e dívidas permanecem armazenados somente no celular.")
    }
   }
  }
 }
 }
}

@Composable
private fun DashboardMetric(title: String, value: String, accent: Color, modifier: Modifier = Modifier) {
 Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(18.dp), shadowElevation = 1.dp) {
  Column(Modifier.padding(17.dp)) {
   Text(title, color = Color(0xFF64748B), style = MaterialTheme.typography.labelLarge)
   Spacer(Modifier.height(7.dp))
   Text(value, color = accent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
  }
 }
}
