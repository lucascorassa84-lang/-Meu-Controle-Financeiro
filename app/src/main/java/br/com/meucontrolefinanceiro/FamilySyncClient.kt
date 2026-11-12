package br.com.meucontrolefinanceiro

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Optional family sync transport. No credentials are bundled into the APK.
 * Configure a Supabase project URL, publishable/anon key and user access token
 * only after creating the project and enabling the RLS policies in family_schema.sql.
 * Never place a service_role key in an Android application.
 */
class FamilySyncClient(
 private val projectUrl: String,
 private val publishableKey: String,
 private val userAccessToken: String
) {
 private fun request(method: String, path: String, body: String? = null): String {
  require(projectUrl.startsWith("https://")) { "HTTPS obrigatório" }
  val connection = URL(projectUrl.trimEnd('/') + "/rest/v1/" + path).openConnection() as HttpURLConnection
  connection.requestMethod = method
  connection.connectTimeout = 15000
  connection.readTimeout = 15000
  connection.setRequestProperty("apikey", publishableKey)
  connection.setRequestProperty("Authorization", "Bearer $userAccessToken")
  connection.setRequestProperty("Accept", "application/json")
  if (body != null) {
   connection.doOutput = true
   connection.setRequestProperty("Content-Type", "application/json")
   connection.setRequestProperty("Prefer", "resolution=merge-duplicates,return=representation")
   connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
  }
  return try {
   val status = connection.responseCode
   val stream = if (status in 200..299) connection.inputStream else connection.errorStream
   val result = stream?.bufferedReader()?.use { it.readText() } ?: ""
   if (status !in 200..299) throw IllegalStateException("Falha de sincronização HTTP $status")
   result
  } finally { connection.disconnect() }
 }
 fun listMovements(familyId: String): JSONArray =
  JSONArray(request("GET", "family_movements?family_id=eq.${java.net.URLEncoder.encode(familyId, "UTF-8")}&select=*&order=created_at.desc"))
 fun upsertMovement(
  familyId: String, movementId: String, description: String, amountCents: Long, kind: String
 ): String {
  val record = JSONObject().put("id", movementId).put("family_id", familyId)
   .put("description", description).put("amount_cents", amountCents).put("kind", kind)
  return request("POST", "family_movements?on_conflict=id", JSONArray().put(record).toString())
 }
 fun deleteMovement(movementId: String) {
  request("DELETE", "family_movements?id=eq.${java.net.URLEncoder.encode(movementId, "UTF-8")}")
 }
}
