package com.goushik.upiwallet.data

/** Spend vs income. A confirm-sheet capture is always a DEBIT; credits arrive only via SMS. */
enum class Direction { DEBIT, CREDIT }

/** Null on pre-2.0/captured rows: their direction and self-transfer evidence still decide the type.
 * Explicit types are used for user-entered entries. A transfer is ONE row with two account IDs, never
 * two expenses; refunds and repayments change balances but not gross spending/income. */
enum class TxnKind { EXPENSE, INCOME, TRANSFER, REFUND, REPAYMENT }

/** Physical money containers. A linked UPI ID is a payment instrument, NOT another account/balance. */
enum class AccountType { BANK, CASH, CARD, WALLET }

/**
 * Lifecycle of a captured transaction.
 * - PENDING      captured (confirm sheet), episode still open / no proof yet
 * - CONFIRMED    positive proof seen (success token) or matched by an authoritative SMS
 * - UNCONFIRMED  episode ended without proof (e.g. <=Rs.100, no SMS, unreadable success screen)
 * - DISCARDED    explicit failure / cancel observed
 */
enum class TxnStatus { PENDING, CONFIRMED, UNCONFIRMED, DISCARDED }

/** Provenance tag stored on the canonical row. */
object Source {
    const val A11Y = "a11y"
    const val SMS = "sms"
    const val A11Y_SMS = "a11y+sms"
    const val MANUAL = "manual"   // user-entered via the "+" button (no RRN; CONFIRMED on save)
}
