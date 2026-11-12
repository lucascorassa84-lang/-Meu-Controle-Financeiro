package br.com.meucontrolefinanceiro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
 @Insert suspend fun addDebt(item: Debt)
 @Insert suspend fun addPayment(item: DebtPayment)
}
@Database(entities = [Transaction::class, Debt::class, DebtPayment::class, Account::class, FinanceCard::class, CardPurchase::class, FinanceGoal::class], version = 2, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() { abstract fun dao(): FinanceDao; abstract fun extras(): ExtraFinanceDao }

class MainActivity : ComponentActivity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val migration = object : Migration(1, 2) {
 override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, institution TEXT NOT NULL, openingBalanceCents INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, limitCents INTEGER NOT NULL, closingDay INTEGER NOT NULL, dueDay INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE IF NOT EXISTS card_purchases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, purchasedAt INTEGER NOT NULL)")
  db.execSQL("CREATE INDEX IF NOT EXISTS index_card_purchases_cardId ON card_purchases (cardId)")
  db.execSQL("CREATE TABLE IF NOT EXISTS goals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, targetCents INTEGER NOT NULL, savedCents INTEGER NOT NULL)")
 }
}
val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finance-local.db").addMigrations(migration).build()
  setContent { MaterialTheme { FinanceApp(db.dao(), db.extras()) } }
 }
}
private fun cents(s: String): Long? = runCatching {
 BigDecimal(s.trim().replace(".", "").replace(",", ".")).movePointRight(2).longValueExact()
}.getOrNull()?.takeIf { it > 0 }
private fun money(v: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(v, 2))

@Composable fun FinanceApp(dao: FinanceDao, extraDao: ExtraFinanceDao) {
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
 val income = transactions.filter { it.kind == "INCOME" }.sumOf { it.amountCents }
 val expense = transactions.filter { it.kind == "EXPENSE" }.sumOf { it.amountCents }
 val outstanding = debts.sumOf { d -> (d.principalCents - payments.filter { it.debtId == d.id }.sumOf { it.amountCents }).coerceAtLeast(0) }
 Scaffold(topBar = { Surface(tonalElevation = 3.dp) { Text("Meu Controle Financeiro", Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.titleLarge) } }) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).padding(14.dp)) {
   Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip(tab == 0, { tab = 0 }, label = { Text("Painel") })
    FilterChip(tab == 1, { tab = 1 }, label = { Text("Lançamentos") })
    FilterChip(tab == 2, { tab = 2 }, label = { Text("Dívidas") })
    FilterChip(tab == 3, { tab = 3 }, label = { Text("Bancos") })
   }
   Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    FilterChip(tab == 4, { tab = 4 }, label = { Text("Contas") })
    FilterChip(tab == 5, { tab = 5 }, label = { Text("Cartões") })
    FilterChip(tab == 6, { tab = 6 }, label = { Text("Metas") })
    FilterChip(tab == 7, { tab = 7 }, label = { Text("Relatórios") })
   }
   if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
   when(tab) {
    0 -> Column {
     Text("Saldo registrado: " + money(income - expense), style = MaterialTheme.typography.headlineSmall)
     Text("Receitas: " + money(income))
     Text("Despesas: " + money(expense))
     Text("Principal de dívidas restante: " + money(outstanding))
     Text("Os valores representam registros locais; não são saldos bancários sincronizados.")
    }
    1 -> Column {
     OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
     OutlinedTextField(amount, { amount = it }, label = { Text("Valor em R$") }, modifier = Modifier.fillMaxWidth())
     Row {
      FilterChip(kind == "EXPENSE", { kind = "EXPENSE" }, label = { Text("Despesa") })
      Spacer(Modifier.width(8.dp))
      FilterChip(kind == "INCOME", { kind = "INCOME" }, label = { Text("Receita") })
     }
     Button(onClick = {
      val value = cents(amount)
      if (description.isBlank() || value == null) error = "Preencha descrição e valor positivo."
      else {
       scope.launch { dao.addTransaction(Transaction(description = description.trim(), amountCents = value, kind = kind)) }
       description = ""; amount = ""; error = ""
      }
     }) { Text("Salvar lançamento") }
     LazyColumn { items(transactions, key = { it.id }) { t ->
      ListItem(headlineContent = { Text(t.description) }, supportingContent = { Text(if(t.kind == "INCOME") "Receita" else "Despesa") }, trailingContent = { Text(money(t.amountCents)) })
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
