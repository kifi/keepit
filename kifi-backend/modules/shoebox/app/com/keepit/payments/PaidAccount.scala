package com.keepit.payments

import com.keepit.common.db._
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.social.BasicUser
import com.kifi.macros.json
import org.joda.time.DateTime

@json
case class SimpleAccountContactInfo(who: BasicUser, enabled: Boolean)

sealed abstract class PaymentStatus(val value: String)
object PaymentStatus {
  case object Ok extends PaymentStatus("ok")
  case object Required extends PaymentStatus("required")
  case object Pending extends PaymentStatus("pending")
  case object Failed extends PaymentStatus("failed")

  private val all = Set(Ok, Required, Pending, Failed)
  def apply(value: String): PaymentStatus = all.find(_.value == value) getOrElse {
    throw new IllegalArgumentException(s"Unknown PaymentStatus: $value")
  }
}

case class PaidAccount(
    id: Option[Id[PaidAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidAccount] = PaidAccountStates.ACTIVE,
    orgId: Id[Organization],
    planId: Id[PaidPlan],
    credit: DollarAmount,
    paymentStatus: PaymentStatus = PaymentStatus.Ok,
    userContacts: Seq[Id[User]],
    emailContacts: Seq[EmailAddress],
    lockedForProcessing: Boolean = false,
    frozen: Boolean = false,
    activeUsers: Int,
    billingCycleStart: DateTime) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
  def withState(state: State[PaidAccount]): PaidAccount = this.copy(state = state)
  def withPaymentStatus(status: PaymentStatus): PaidAccount = this.copy(paymentStatus = status)
  def freeze: PaidAccount = this.copy(frozen = true) //a frozen account will not be charged anything by the payment processor until unfrozen by an admin. Intended for automatically detected data integrity issues.

  def owed: DollarAmount = -(DollarAmount.ZERO min credit)

  def withReducedCredit(reduction: DollarAmount): PaidAccount = {
    val newCredit = DollarAmount(credit.cents - reduction.cents)
    this.copy(credit = newCredit)
  }
  def withIncreasedCredit(increase: DollarAmount): PaidAccount = {
    val newCredit = DollarAmount(credit.cents + increase.cents)
    this.copy(credit = newCredit)
  }
  def withMoreActiveUsers(howMany: Int): PaidAccount = this.copy(activeUsers = activeUsers + howMany)
  def withFewerActiveUsers(howMany: Int): PaidAccount = {
    //assert(activeUsers - howMany >= 0)
    val newActiveUsers = activeUsers - howMany
    this.copy(activeUsers = if (newActiveUsers < 0) 0 else newActiveUsers)
  }

  def withUserContacts(newContacts: Seq[Id[User]]): PaidAccount = this.copy(userContacts = newContacts)
  def withEmailContacts(newContacts: Seq[EmailAddress]): PaidAccount = this.copy(emailContacts = newContacts)

  def withNewPlan(newPlanId: Id[PaidPlan]): PaidAccount = this.copy(planId = newPlanId)

  def withCycleStart(newCycleStart: DateTime): PaidAccount = this.copy(billingCycleStart = newCycleStart)
}

object PaidAccountStates extends States[PaidAccount]
