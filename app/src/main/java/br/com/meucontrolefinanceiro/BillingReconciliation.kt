package br.com.meucontrolefinanceiro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth

@Entity(tableName = "installment_purchases", indices = [Index("cardId")])
data class InstallmentPurchase(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val cardId: Long, val description: String, val totalCents: Long,
 val installments: Int, val firstBillMonth: String, val createdAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "bill_payments", indices = [Index(value = ["cardId", "billMonth"])])
data class BillPayment(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val cardId: Long, val billMonth: String, val amountCents: Long,
 val paidAt: Long = System.currentTimeMillis()
)
@Entity(tableName = "bank_movements", indices = [Index(value = ["accountName", "externalKey"], unique = true)])
data class BankMovement(
 @PrimaryKey(autoGenerate = true) val id: Long = 0,
 val accountName: String, val externalKey: String,
 val description: String, val amountCents: Long, val kind: String,
 val occurredAt: Long = System.currentTimeMillis(), val matchedTransactionId: Long? = null
)
@Dao interface BillingDao {
 @Query("SELECT * FROM installment_purchases ORDER BY id DESC") fun installmentPurchases(): Flow<List<InstallmentPurchase>>
 @Query("SELECT * FROM bill_payments ORDER BY paidAt DESC") fun billPayments(): Flow<List<BillPayment>>
 @Query("SELECT * FROM bank_movements ORDER BY occurredAt DESC") fun bankMovements(): Flow<List<BankMovement>>
 @Insert suspend fun addInstallment(p: InstallmentPurchase)
 @Insert suspend fun addBillPayment(p: BillPayment)
 @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addMovement(m: BankMovement): Long
 @Query("UPDATE bank_movements SET matchedTransactionId = :transactionId WHERE id = :movementId AND matchedTransactionId IS NULL AND NOT EXISTS (SELECT 1 FROM bank_movements WHERE matchedTransactionId = :transactionId)") suspend fun reconcile(movementId: Long, transactionId: Long): Int
 @Query("UPDATE bank_movements SET matchedTransactionId = NULL WHERE id = :movementId") suspend fun undoMatch(movementId: Long)
 @Query("SELECT * FROM cards") suspend fun cardsSnapshot(): List<FinanceCard>
 @Query("SELECT * FROM debts") suspend fun debtsSnapshot(): List<Debt>
 @Query("SELECT * FROM debt_payments") suspend fun debtPaymentsSnapshot(): List<DebtPayment>
 @Query("SELECT * FROM bill_payments") suspend fun billPaymentsSnapshot(): List<BillPayment>
 @Query("SELECT * FROM installment_purchases") suspend fun installmentSnapshot(): List<InstallmentPurchase>
 @Query("SELECT * FROM card_purchases") suspend fun purchasesSnapshot(): List<CardPurchase>
}
fun installmentAmount(total: Long, n: Int, index: Int): Long = total / n + if (index < total % n) 1 else 0
fun cardBillAmount(card: FinanceCard, month: YearMonth, old: List<CardPurchase>, plans: List<InstallmentPurchase>): Long {
 val purchases = old.filter { it.cardId == card.id }.sumOf { p ->
  val date = Instant.ofEpochMilli(p.purchasedAt).atZone(ZoneId.systemDefault()).toLocalDate()
  val bill = if (date.dayOfMonth > card.closingDay) YearMonth.from(date).plusMonths(1) else YearMonth.from(date)
  if (bill == month) p.amountCents else 0L
 }
 val installments = plans.filter { it.cardId == card.id }.sumOf { p ->
  val offset = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.parse(p.firstBillMonth), month).toInt()
  if (offset in 0 until p.installments) installmentAmount(p.totalCents, p.installments, offset) else 0L
 }
 return purchases + installments
}
@Composable fun BillingScreen(dao: BillingDao, financeDao: FinanceDao, extraDao: ExtraFinanceDao, section: Int) {
 val cards by extraDao.cards().collectAsState(initial = emptyList())
 val plans by dao.installmentPurchases().collectAsState(initial = emptyList())
 val purchases by extraDao.purchases().collectAsState(initial = emptyList())
 val bills by dao.billPayments().collectAsState(initial = emptyList())
 val movements by dao.bankMovements().collectAsState(initial = emptyList())
 val transactions by financeDao.transactions().collectAsState(initial = emptyList())
 val scope = rememberCoroutineScope()
 var selectedCard by remember { mutableStateOf<Long?>(null) }
 var name by remember { mutableStateOf("") }
 var amount by remember { mutableStateOf("") }
 var count by remember { mutableStateOf("1") }
 var month by remember { mutableStateOf(YearMonth.now().toString()) }
 var account by remember { mutableStateOf("") }
 var movementKey by remember { mutableStateOf("") }
 var kind by remember { mutableStateOf("EXPENSE") }
 var error by remember { mutableStateOf("") }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
  if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
  if (section == 8) {
   Text("Faturas e parcelamentos", style = MaterialTheme.typography.titleLarge)
   OutlinedTextField(month, { month = it }, label = { Text("Mês da fatura AAAA-MM") })
   Row {
    cards.forEach { card -> FilterChip(selectedCard == card.id, { selectedCard = card.id }, label = { Text(card.name) }) }
   }
   val card = cards.find { it.id == selectedCard }
   if (card == null) Text("Cadastre e selecione um cartão na aba Cartões.")
   else {
    val ym = runCatching { YearMonth.parse(month) }.getOrNull()
    val due = ym?.atDay(card.dueDay)
    val total = ym?.let { cardBillAmount(card, it, purchases, plans) } ?: 0L
    val paid = bills.filter { it.cardId == card.id && it.billMonth == month }.sumOf { it.amountCents }
    Text("Fatura: " + moneyBRL(total) + " · Pago: " + moneyBRL(paid))
    Text("Pendente: " + moneyBRL((total - paid).coerceAtLeast(0)) + " · Vencimento: " + (due?.toString() ?: "mês inválido"))
    OutlinedTextField(name, { name = it }, label = { Text("Descrição da compra parcelada") })
    OutlinedTextField(amount, { amount = it }, label = { Text("Valor total R$") })
    OutlinedTextField(count, { count = it }, label = { Text("Número de parcelas (1-120)") })
    Button(onClick = {
     val value = parseBRL(amount); val n = count.toIntOrNull()
     if (name.isBlank() || value == null || n == null || n !in 1..120 || ym == null) error = "Dados da compra inválidos."
     else { scope.launch { dao.addInstallment(InstallmentPurchase(cardId = card.id, description = name.trim(), totalCents = value, installments = n, firstBillMonth = month)) }; name = ""; amount = ""; count = "1"; error = "" }
    }) { Text("Salvar parcelamento") }
    OutlinedTextField(amount, { amount = it }, label = { Text("Valor do pagamento da fatura R$") })
    Button(onClick = {
     val value = parseBRL(amount)
     if (value == null || ym == null || value > (total - paid).coerceAtLeast(0)) error = "Pagamento inválido."
     else { scope.launch { dao.addBillPayment(BillPayment(cardId = card.id, billMonth = month, amountCents = value)) }; amount = ""; error = "" }
    }) { Text("Registrar pagamento") }
    Column { plans.filter { it.cardId == card.id }.forEach { p ->
     ListItem(headlineContent = { Text(p.description) }, supportingContent = { Text("${p.installments}x · primeira fatura ${p.firstBillMonth}") }, trailingContent = { Text(moneyBRL(p.totalCents)) })
    } }
   }
  } else {
   Text("Conciliação de movimentações", style = MaterialTheme.typography.titleLarge)
   Text("Registre movimentações de extratos para conferir com lançamentos manuais. Sem conexão bancária automática.")
   OutlinedTextField(account, { account = it }, label = { Text("Banco / conta") })
   OutlinedTextField(movementKey, { movementKey = it }, label = { Text("Identificador único no extrato") })
   OutlinedTextField(name, { name = it }, label = { Text("Descrição no extrato") })
   OutlinedTextField(amount, { amount = it }, label = { Text("Valor R$") })
   Row {
    FilterChip(kind == "EXPENSE", { kind = "EXPENSE" }, label = { Text("Saída") })
    Spacer(Modifier.width(8.dp))
    FilterChip(kind == "INCOME", { kind = "INCOME" }, label = { Text("Entrada") })
   }
   Button(onClick = {
    val v = parseBRL(amount)
    if (account.isBlank() || movementKey.isBlank() || name.isBlank() || v == null) error = "Preencha todos os campos."
    else { scope.launch {
     val id = dao.addMovement(BankMovement(accountName = account.trim(), externalKey = movementKey.trim(), description = name.trim(), amountCents = v, kind = kind))
     error = if (id == -1L) "Movimentação já cadastrada." else ""
    }; name = ""; amount = ""; movementKey = "" }
   }) { Text("Adicionar movimentação") }
   Column { movements.forEach { m ->
    Column(Modifier.padding(vertical = 8.dp)) {
     Text(m.accountName + " · " + m.description)
     Text(moneyBRL(m.amountCents) + " · " + if(m.matchedTransactionId == null) "Pendente" else "Conciliada")
     if (m.matchedTransactionId != null) {
      TextButton(onClick = { scope.launch { dao.undoMatch(m.id) } }) { Text("Desfazer conciliação") }
     } else {
      val candidates = transactions.filter { t ->
       t.amountCents == m.amountCents && t.kind == m.kind &&
       movements.none { it.matchedTransactionId == t.id }
      }
      if (candidates.isEmpty()) Text("Nenhum lançamento de mesmo valor e tipo disponível.")
      candidates.take(8).forEach { t ->
       TextButton(onClick = { scope.launch {
        if (dao.reconcile(m.id, t.id) == 0) error = "Lançamento já conciliado."
       } }) { Text("Conciliar com: " + t.description + " (#" + t.id + ")") }
      }
     }
     HorizontalDivider()
    }
   } }
  }
 }
}
