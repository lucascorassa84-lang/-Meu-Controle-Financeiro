package br.com.meucontrolefinanceiro

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** Indicative fixed-installment simulation; actual contract terms may differ. */
data class InstallmentEstimate(val number: Int, val dueDate: LocalDate, val paymentCents: Long, val interestCents: Long, val principalCents: Long, val balanceCents: Long)

object DebtCalculator {
 fun fixedInstallments(principalCents: Long, annualInterestBps: Int, count: Int, firstDue: LocalDate): List<InstallmentEstimate> {
  require(principalCents > 0 && annualInterestBps >= 0 && count in 1..600)
  val scale = 16
  val monthlyRate = BigDecimal(annualInterestBps).divide(BigDecimal(120000), scale, RoundingMode.HALF_UP)
  val principal = BigDecimal(principalCents)
  val payment = if(monthlyRate.signum() == 0) principal.divide(BigDecimal(count), 0, RoundingMode.HALF_UP)
  else {
   val factor = BigDecimal.ONE.add(monthlyRate).pow(count)
   principal.multiply(monthlyRate).multiply(factor).divide(factor.subtract(BigDecimal.ONE), 0, RoundingMode.HALF_UP)
  }
  var balance = principalCents
  return (1..count).map { n ->
   val interest = BigDecimal(balance).multiply(monthlyRate).setScale(0, RoundingMode.HALF_UP).longValueExact()
   val due = if(n == count) balance + interest else minOf(balance + interest, payment.longValueExact())
   val principalPaid = (due - interest).coerceAtLeast(0)
   balance = (balance - principalPaid).coerceAtLeast(0)
   InstallmentEstimate(n, firstDue.plusMonths((n - 1).toLong()), due, interest, principalPaid, balance)
  }
 }
}
