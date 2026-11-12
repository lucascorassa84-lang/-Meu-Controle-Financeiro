package br.com.meucontrolefinanceiro

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FinanceDbProvider {
 @Volatile private var instance: FinanceDatabase? = null
 val migration1to2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
   db.execSQL("CREATE TABLE IF NOT EXISTS accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, institution TEXT NOT NULL, openingBalanceCents INTEGER NOT NULL)")
   db.execSQL("CREATE TABLE IF NOT EXISTS cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, limitCents INTEGER NOT NULL, closingDay INTEGER NOT NULL, dueDay INTEGER NOT NULL)")
   db.execSQL("CREATE TABLE IF NOT EXISTS card_purchases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, purchasedAt INTEGER NOT NULL)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_card_purchases_cardId ON card_purchases (cardId)")
   db.execSQL("CREATE TABLE IF NOT EXISTS goals (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, targetCents INTEGER NOT NULL, savedCents INTEGER NOT NULL)")
  }
 }
 val migration2to3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
   db.execSQL("CREATE TABLE IF NOT EXISTS installment_purchases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, description TEXT NOT NULL, totalCents INTEGER NOT NULL, installments INTEGER NOT NULL, firstBillMonth TEXT NOT NULL, createdAt INTEGER NOT NULL)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_installment_purchases_cardId ON installment_purchases (cardId)")
   db.execSQL("CREATE TABLE IF NOT EXISTS bill_payments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, cardId INTEGER NOT NULL, billMonth TEXT NOT NULL, amountCents INTEGER NOT NULL, paidAt INTEGER NOT NULL)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_bill_payments_cardId_billMonth ON bill_payments (cardId, billMonth)")
   db.execSQL("CREATE TABLE IF NOT EXISTS bank_movements (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, accountName TEXT NOT NULL, externalKey TEXT NOT NULL, description TEXT NOT NULL, amountCents INTEGER NOT NULL, kind TEXT NOT NULL, occurredAt INTEGER NOT NULL, matchedTransactionId INTEGER)")
   db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bank_movements_accountName_externalKey ON bank_movements (accountName, externalKey)")
  }
 }
 fun get(context: Context): FinanceDatabase = instance ?: synchronized(this) {
  instance ?: Room.databaseBuilder(context.applicationContext, FinanceDatabase::class.java, "finance-local.db")
   .addMigrations(migration1to2, migration2to3).build().also { instance = it }
 }
}
