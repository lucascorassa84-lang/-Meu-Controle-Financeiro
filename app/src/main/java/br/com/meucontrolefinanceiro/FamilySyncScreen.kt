package br.com.meucontrolefinanceiro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Shared Supabase view. Requires user JWT; local Room records are not silently uploaded. */
@Composable
fun FamilySyncScreen() {
 var endpoint by remember { mutableStateOf("https://fbvfequlagmeiyeusodp.supabase.co") }
 var publicKey by remember { mutableStateOf("sb_publishable_HNQbiXjYHod0lhVtngsBzg_56j3ULcv") }
 var token by remember { mutableStateOf("") }
 var email by remember { mutableStateOf("") }
 var password by remember { mutableStateOf("") }
 var familyId by remember { mutableStateOf("f18fe643-163a-4934-8220-7339bd7ac825") }
 var description by remember { mutableStateOf("") }
 var amount by remember { mutableStateOf("") }
 var kind by remember { mutableStateOf("EXPENSE") }
 var status by remember { mutableStateOf("Configure o acesso autenticado ao Supabase.") }
 var rows by remember { mutableStateOf(listOf<JSONObject>()) }
 var busy by remember { mutableStateOf(false) }
 val scope = rememberCoroutineScope()
 fun runAction(action: suspend (FamilySyncClient) -> Unit) {
  if (busy) return
  val id = runCatching { UUID.fromString(familyId.trim()) }.getOrNull()
  if (id == null || !endpoint.startsWith("https://") || publicKey.isBlank() || token.isBlank()) {
   status = "Informe URL HTTPS, chave publicável, JWT e UUID da família."; return
  }
  busy = true
  scope.launch {
   try {
    val client = FamilySyncClient(endpoint.trim(), publicKey.trim(), token.trim())
    withContext(Dispatchers.IO) { action(client) }
    status = "Operação concluída. Atualize para consultar os dados."
   } catch (e: Exception) {
    status = "Falha: " + (e.message ?: "Verifique conexão e permissões")
   } finally { busy = false }
  }
 }
 Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
  Text("Movimentações compartilhadas", style = MaterialTheme.typography.titleLarge)
  Text("Entre com sua conta Supabase. Use apenas chave publicável/anon; nunca service_role.")
  OutlinedTextField(endpoint, { endpoint = it }, label = { Text("URL do projeto HTTPS") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(publicKey, { publicKey = it }, label = { Text("Chave publicável / anon") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(email, { email = it }, label = { Text("E-mail da conta Supabase") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(password, { password = it }, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
  Button(enabled = !busy, onClick = {
   if (!endpoint.startsWith("https://") || publicKey.isBlank() || email.isBlank() || password.isBlank()) {
    status = "Informe URL HTTPS, chave publicável, e-mail e senha."
   } else {
    busy = true
    scope.launch {
     try {
      val jwt = withContext(Dispatchers.IO) { SupabaseAuthClient(endpoint.trim(), publicKey.trim()).signIn(email.trim(), password) }
      token = jwt
      password = ""
      status = "Conta autenticada. Atualize as movimentações."
     } catch (e: Exception) { status = e.message ?: "Falha no login" }
     finally { busy = false }
    }
   }
  }) { Text("Entrar na conta") }
  Text(if (token.isNotBlank()) "Sessão autenticada" else "Faça login para sincronizar.")
  OutlinedTextField(familyId, { familyId = it }, label = { Text("UUID da família") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  Button(enabled = !busy, onClick = {
   runAction { client ->
    val result = client.listMovements(familyId.trim())
    val newRows = (0 until result.length()).map { result.getJSONObject(it) }
    withContext(Dispatchers.Main) { rows = newRows }
   }
  }) { Text("Atualizar movimentações") }
  OutlinedTextField(description, { description = it }, label = { Text("Descrição") }, modifier = Modifier.fillMaxWidth())
  OutlinedTextField(amount, { amount = it }, label = { Text("Valor em centavos") }, modifier = Modifier.fillMaxWidth())
  Row {
   FilterChip(kind == "EXPENSE", { kind = "EXPENSE" }, label = { Text("Despesa") })
   Spacer(Modifier.width(8.dp))
   FilterChip(kind == "INCOME", { kind = "INCOME" }, label = { Text("Receita") })
  }
  Button(enabled = !busy, onClick = {
   val cents = amount.toLongOrNull()
   if (description.isBlank() || cents == null || cents <= 0) status = "Informe descrição e centavos positivos."
   else runAction { it.upsertMovement(familyId.trim(), UUID.randomUUID().toString(), description.trim(), cents, kind) }
  }) { Text("Salvar no banco compartilhado") }
  Text(status, style = MaterialTheme.typography.bodySmall)
  LazyColumn {
   items(rows, key = { it.optString("id") }) { item ->
    ListItem(
     headlineContent = { Text(item.optString("description")) },
     supportingContent = { Text(item.optString("kind") + " · " + item.optLong("amount_cents") + " centavos") },
     trailingContent = {
      TextButton(enabled = !busy, onClick = {
       val movementId = item.optString("id")
       runAction { client ->
        client.deleteMovement(movementId)
        val updated = client.listMovements(familyId.trim())
        withContext(Dispatchers.Main) { rows = (0 until updated.length()).map { updated.getJSONObject(it) } }
       }
      }) { Text("Excluir") }
     }
    )
   }
  }
 }
}
