package br.com.meucontrolefinanceiro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

class LocalReminders(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
 override suspend fun doWork(): Result = try {
  val db = FinanceDbProvider.get(applicationContext)
  val dao = db.billing()
  val today = LocalDate.now()
  val debts = dao.debtsSnapshot()
  val payments = dao.debtPaymentsSnapshot()
  val cards = dao.cardsSnapshot()
  val plans = dao.installmentSnapshot()
  val purchases = dao.purchasesSnapshot()
  val bills = dao.billPaymentsSnapshot()
  val notices = mutableListOf<String>()
  debts.forEach { d ->
   val date = runCatching { LocalDate.parse(d.dueDate) }.getOrNull()
   val balance = d.principalCents - payments.filter { it.debtId == d.id }.sumOf { it.amountCents }
   if (date != null && balance > 0 && !date.isAfter(today.plusDays(3)) && !date.isBefore(today))
    notices.add("Dívida ${d.creditor}: vencimento ${date}")
  }
  cards.forEach { c ->
   val billMonth = YearMonth.now()
   val due = billMonth.atDay(c.dueDay)
   val total = cardBillAmount(c, billMonth, purchases, plans)
   val paid = bills.filter { it.cardId == c.id && it.billMonth == billMonth.toString() }.sumOf { it.amountCents }
   if (total > paid && !due.isBefore(today) && !due.isAfter(today.plusDays(3)))
    notices.add("Fatura ${c.name}: ${moneyBRL(total - paid)} vence ${due}")
  }
  if (notices.isNotEmpty()) {
   val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
   manager.createNotificationChannel(NotificationChannel("finance_reminders", "Vencimentos financeiros", NotificationManager.IMPORTANCE_DEFAULT))
   if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
    manager.notify(today.toEpochDay().toInt(), NotificationCompat.Builder(applicationContext, "finance_reminders")
     .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Meu Controle Financeiro")
     .setContentText(notices.first()).setStyle(NotificationCompat.BigTextStyle().bigText(notices.joinToString("\n")))
     .setAutoCancel(true).build())
   }
  }
  Result.success()
 } catch (e: Exception) { Result.retry() }
 companion object {
  fun schedule(context: Context) {
   WorkManager.getInstance(context).enqueueUniquePeriodicWork("finance_reminders", ExistingPeriodicWorkPolicy.KEEP,
    PeriodicWorkRequestBuilder<LocalReminders>(1, TimeUnit.DAYS).build())
  }
 }
}
