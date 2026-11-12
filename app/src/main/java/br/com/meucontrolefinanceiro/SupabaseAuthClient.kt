package br.com.meucontrolefinanceiro

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Email/password authentication using Supabase GoTrue. Never bundle service_role. */
class SupabaseAuthClient(private val projectUrl: String, private val publishableKey: String) {
 fun signIn(email: String, password: String): String {
  require(projectUrl.startsWith("https://")) { "A URL deve usar HTTPS" }
  val conn = URL(projectUrl.trimEnd('/') + "/auth/v1/token?grant_type=password").openConnection() as HttpURLConnection
  try {
   conn.requestMethod = "POST"
   conn.connectTimeout = 15000
   conn.readTimeout = 15000
   conn.doOutput = true
   conn.setRequestProperty("apikey", publishableKey)
   conn.setRequestProperty("Content-Type", "application/json")
   val payload = JSONObject().put("email", email).put("password", password).toString()
   conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
   val status = conn.responseCode
   val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
    ?.bufferedReader()?.use { it.readText() } ?: ""
   if (status !in 200..299) throw IllegalStateException("Login recusado (HTTP $status). Verifique email e senha.")
   return JSONObject(response).getString("access_token")
  } finally { conn.disconnect() }
 }
}
