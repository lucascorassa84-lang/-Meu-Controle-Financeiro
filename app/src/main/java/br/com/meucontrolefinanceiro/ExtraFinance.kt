package br.com.meucontrolefinanceiro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

@Entity(tableName = "accounts")
data class Account(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val institution: String, val openingBalanceCents: Long = 0)
@Entity(tableName = "cards")
data class FinanceCard(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val limitCents: Long, val closingDay: Int, val dueDay: Int)
@Entity(tableName = "card_purchases", indices = [Index("cardId")])
data class CardPurchase(@PrimaryKey(autoGenerate = true) val id: Long = 0, val cardId: Long, val description: String, val amountCents: Long, val purchasedAt: Long = System.currentTimeMillis())
@Entity(tableName = "goals")
data class FinanceGoal(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val targetCents: Long, val savedCents: Long = 0)
@Dao interface ExtraFinanceDao {
 @Query("SELECT * FROM accounts ORDER BY id DESC") fun accounts(): Flow<List<Account>>
 @Query("SELECT * FROM cards ORDER BY id DESC") fun cards(): Flow<List<FinanceCard>>
 @Query("SELECT * FROM card_purchases ORDER BY purchasedAt DESC") fun purchases(): Flow<List<CardPurchase>>
 @Query("SELECT * FROM goals ORDER BY id DESC") fun goals(): Flow<List<FinanceGoal>>
 @Insert suspend fun addAccount(x: Account)
 @Insert suspend fun addCard(x: FinanceCard)
 @Insert suspend fun addPurchase(x: CardPurchase)
 @Insert suspend fun addGoal(x: FinanceGoal)
 @Query("UPDATE goals SET savedCents = savedCents + :amount WHERE id = :id AND savedCents + :amount <= targetCents") suspend fun contribute(id: Long, amount: Long)
}
internal fun moneyBRL(value: Long) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(BigDecimal.valueOf(value, 2))
internal fun parseBRL(value: String): Long? = runCatching { BigDecimal(value.trim().replace(".", "").replace(",", ".")).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0L }

@Composable fun ExtraFinanceScreen(dao: ExtraFinanceDao, section: Int) {
 val accounts by dao.accounts().collectAsState(initial = emptyList())
 val cards by dao.cards().collectAsState(initial = emptyList())
 val purchases by dao.purchases().collectAsState(initial = emptyList())
 val goals by dao.goals().collectAsState(initial = emptyList())
 val scope = rememberCoroutineScope()
 var name by remember(section) { mutableStateOf("") }
 var amount by remember(section) { mutableStateOf("") }
 var secondary by remember(section) { mutableStateOf("") }
 var extra by remember(section) { mutableStateOf("") }
 var selected by remember(section) { mutableStateOf<Long?>(null) }
 var error by remember(section) { mutableStateOf("") }
 Column(Modifier.fillMaxSize()) {
  if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
  when (section) {
   4 -> {
    Text("Contas", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(name, { name = it }, label = { Text("Nome da conta") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(secondary, { secondary = it }, label = { Text("Instituição") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(amount, { amount = it }, label = { Text("Saldo inicial em R$ (opcional)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
     val v = if(amount.isBlank()) 0L else parseBRL(amount)
     if(name.isBlank() || secondary.isBlank() || v == null) error = "Informe nome, instituição e saldo válido."
     else { scope.launch { dao.addAccount(Account(name = name.trim(), institution = secondary.trim(), openingBalanceCents = v)) }; name = ""; secondary = ""; amount = ""; error = "" }
    }) { Text("Adicionar conta") }
    LazyColumn { items(accounts, key = { it.id }) { a -> ListItem(headlineContent = { Text(a.name) }, supportingContent = { Text(a.institution + " · Saldo inicial: " + moneyBRL(a.openingBalanceCents)) }); HorizontalDivider() } }
   }
   5 -> {
    Text("Cartões", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(name, { name = it }, label = { Text("Nome do cartão") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(amount, { amount = it }, label = { Text("Limite em R$") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(secondary, { secondary = it }, label = { Text("Dia de fechamento (1-28)") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(extra, { extra = it }, label = { Text("Dia de vencimento (1-28)") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
     val v = parseBRL(amount); val close = secondary.toIntOrNull(); val due = extra.toIntOrNull()
     if(name.isBlank() || v == null || close == null || due == null || close !in 1..28 || due !in 1..28) error = "Confira limite e dias entre 1 e 28."
     else { scope.launch { dao.addCard(FinanceCard(name = name.trim(), limitCents = v, closingDay = close, dueDay = due)) }; name = ""; amount = ""; secondary = ""; extra = ""; error = "" }
    }) { Text("Adicionar cartão") }
    LazyColumn { items(cards, key = { it.id }) { c ->
     val spent = purchases.filter { it.cardId == c.id }.sumOf { it.amountCents }
     Column {
      Text(c.name, style = MaterialTheme.typography.titleMedium)
      Text("Limite: " + moneyBRL(c.limitCents) + " · Compras registradas: " + moneyBRL(spent))
      Text("Fechamento dia ${c.closingDay} · Vencimento dia ${c.dueDay}")
      if(selected == c.id) {
       OutlinedTextField(secondary, { secondary = it }, label = { Text("Descrição da compra") })
       OutlinedTextField(amount, { amount = it }, label = { Text("Valor da compra R$") })
       Button(onClick = {
        val v = parseBRL(amount)
        if(v == null || secondary.isBlank()) error = "Compra inválida."
        else { scope.launch { dao.addPurchase(CardPurchase(cardId = c.id, description = secondary.trim(), amountCents = v)) }; selected = null; secondary = ""; amount = ""; error = "" }
       }) { Text("Registrar compra") }
      } else TextButton(onClick = { selected = c.id; secondary = ""; amount = "" }) { Text("Nova compra") }
      HorizontalDivider()
     }
    } }
   }
   6 -> {
    Text("Metas financeiras", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(name, { name = it }, label = { Text("Nome da meta") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(amount, { amount = it }, label = { Text("Objetivo em R$") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = {
     val v = parseBRL(amount)
     if(v == null || name.isBlank()) error = "Meta inválida."
     else { scope.launch { dao.addGoal(FinanceGoal(name = name.trim(), targetCents = v)) }; name = ""; amount = ""; error = "" }
    }) { Text("Criar meta") }
    LazyColumn { items(goals, key = { it.id }) { g ->
     Column {
      Text(g.name, style = MaterialTheme.typography.titleMedium)
      Text(moneyBRL(g.savedCents) + " / " + moneyBRL(g.targetCents))
      LinearProgressIndicator(progress = { (g.savedCents.toFloat() / g.targetCents).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
      if(selected == g.id) {
       OutlinedTextField(secondary, { secondary = it }, label = { Text("Adicionar valor R$") })
       Button(onClick = {
        val v = parseBRL(secondary)
        if(v == null || v > g.targetCents - g.savedCents) error = "Valor inválido ou superior ao restante da meta."
        else { scope.launch { dao.contribute(g.id, v) }; selected = null; secondary = ""; error = "" }
       }) { Text("Confirmar") }
      } else TextButton(onClick = { selected = g.id; secondary = "" }) { Text("Adicionar à meta") }
      HorizontalDivider()
     }
    } }
   }
  }
 }
}
