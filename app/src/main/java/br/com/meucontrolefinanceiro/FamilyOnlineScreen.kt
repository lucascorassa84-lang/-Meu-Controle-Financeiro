package br.com.meucontrolefinanceiro

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Supabase Auth uses a publishable key, never a service_role key. */
private fun signInSupabase(projectUrl: String, key: String, email: String, password: String): String {
 val base = projectUrl.trim().trimEnd('/').removeSuffix("/rest/v1")
 require(base.startsWith("https://") && URL(base).host.endsWith(".supabase.co")) { "Informe a URL HTTPS do projeto Supabase." }
 require(key.isNotBlank()) { "Informe a chave publicável." }
 require(email.isNotBlank() && password.isNotBlank()) { "Informe e-mail e senha." }
 val connection = URL("$base/auth/v1/token?grant_type=password").openConnection() as HttpURLConnection
 try {
  connection.requestMethod = "POST"
  connection.connectTimeout = 15000
  connection.readTimeout = 15000
  connection.doOutput = true
  connection.setRequestProperty("apikey", key.trim())
  connection.setRequestProperty("Content-Type", "application/json")
  connection.outputStream.use { it.write(JSONObject().put("email", email.trim()).put("password", password).toString().toByteArray()) }
  val status = connection.responseCode
  val response = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
  if (status !in 200..299) {
   val reason = runCatching { JSONObject(response).optString("msg").ifBlank { JSONObject(response).optString("error_description") } }.getOrDefault("")
   throw IllegalStateException(if (status == 400 || status == 401) "E-mail ou senha incorretos, ou conta ainda não confirmada." else "Falha no login (HTTP $status): $reason")
  }
  return JSONObject(response).getString("access_token")
 } finally { connection.disconnect() }
}

@Composable
fun FamilyOnlineScreen() {
 val scope = rememberCoroutineScope()
 var projectUrl by remember { mutableStateOf("https://fbvfequlagmeiyeusodp.supabase.co") }
 var publishableKey by remember { mutableStateOf("") }
 var email by remember { mutableStateOf("") }
 var password by remember { mutableStateOf("") }
 var familyId by remember { mutableStateOf("") }
 var accessToken by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var message by remember { mutableStateOf("Faça login para sincronizar.") }
 var movements by remember { mutableStateOf<List<String>>(emptyList()) }
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
  Text("Família online", style = MaterialTheme.typography.headlineSmall)
  OutlinedTextField(projectUrl, { projectUrl = it }, label = { Text("URL do projeto Supabase") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(publishableKey, { publishableKey = it }, label = { Text("Chave publicável") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(email, { email = it }, label = { Text("E-mail da conta") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  OutlinedTextField(password, { password = it }, label = { Text("Senha") }, modifier = Modifier.fillMaxWidth(), visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
  OutlinedTextField(familyId, { familyId = it }, label = { Text("UUID da família") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
  Button(enabled = !busy, onClick = {
   busy = true
   message = "Conectando..."
   scope.launch {
    try {
     val token = withContext(Dispatchers.IO) { signInSupabase(projectUrl, publishableKey, email, password) }
     accessToken = token
     password = ""
     message = "Login realizado. Atualize as movimentações."
    } catch (e: Exception) {
     accessToken = null
     message = e.message ?: "Não foi possível entrar na conta."
    } finally { busy = false }
   }
  }) { Text(if (busy) "Aguarde..." else "Entrar na conta") }
  Button(enabled = !busy && accessToken != null, onClick = {
   busy = true
   scope.launch {
    try {
     val id = java.util.UUID.fromString(familyId.trim()).toString()
     val data = withContext(Dispatchers.IO) { FamilySyncClient(projectUrl.trim().removeSuffix("/rest/v1"), publishableKey.trim(), accessToken!!).listMovements(id) }
     movements = (0 until data.length()).map { data.getJSONObject(it).optString("description") }
     message = "Sincronização consultada: ${movements.size} movimentações."
    } catch (e: Exception) { message = "Falha ao atualizar: ${e.message}" }
    finally { busy = false }
   }
  }) { Text("Atualizar movimentações") }
  Text(message, color = if (message.startsWith("Falha")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
  movements.forEach { Text(it) }
  Text("Esta tela consulta movimentações remotas. O vínculo familiar e a gravação bidirecional dependem da configuração de membros e permissões no Supabase.", style = MaterialTheme.typography.bodySmall)
 }
}
