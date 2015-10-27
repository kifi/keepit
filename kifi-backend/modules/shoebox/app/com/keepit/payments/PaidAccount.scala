package com.keepit.payments

import com.keepit.common.db._
import com.keepit.common.db.slick.DataBaseComponent
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser

import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
import play.api.libs.json._

@json
case class DollarAmount(cents: Int) extends Ordered[DollarAmount] {
  def compare(that: DollarAmount) = cents compare that.cents
  def +(other: DollarAmount): DollarAmount = DollarAmount(cents + other.cents)
  def -(other: DollarAmount): DollarAmount = DollarAmount(cents - other.cents)
  def *(x: Int) = DollarAmount(cents * x)
  def max(other: DollarAmount): DollarAmount = DollarAmount(cents max other.cents)
  def min(other: DollarAmount): DollarAmount = DollarAmount(cents min other.cents)
  override def toString = toDollarString
  def toDollarString: String = if (cents < 0) "-" + (-this).toDollarString else "$%d.%02d".format(cents / 100, cents % 100)

  def unary_- = DollarAmount(-1 * cents)

  def toCents: Int = cents
}

object DollarAmount {
  def cents(cents: Int): DollarAmount = DollarAmount(cents)
  def dollars(dollars: Int): DollarAmount = DollarAmount(dollars * 100)

  val ZERO = DollarAmount(0)

  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[DollarAmount, Int](_.cents, DollarAmount.cents)
  }
  val formatAsCents: Format[DollarAmount] = (__ \ 'cents).format[Int].inmap(DollarAmount.cents, _.toCents)
}

@json
case class SimpleAccountContactInfo(who: BasicUser, enabled: Boolean)

sealed abstract class PaymentStatus(val value: String)
object PaymentStatus {
  case object Ok extends PaymentStatus("ok")
  case object Pending extends PaymentStatus("pending")
  case object Failed extends PaymentStatus("failed")

  private val all = Set(Ok, Pending, Failed)
  def apply(value: String): PaymentStatus = all.find(_.value == value) getOrElse {
    throw new IllegalArgumentException(s"Unknown PaymentStatus: $value")
  }

  implicit val writes = Writes[PaymentStatus](status => JsString(status.value))
}

case class FrozenAccountException(orgId: Id[Organization]) extends Exception(s"Organization $orgId's account is frozen!")
case class InvalidPaymentStatusException(orgId: Id[Organization], status: PaymentStatus) extends Exception(s"Invalid payment status for organization $orgId: ${status.value}")

case class PaidAccount(
    id: Option[Id[PaidAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidAccount] = PaidAccountStates.ACTIVE,
    orgId: Id[Organization],
    planId: Id[PaidPlan],
    credit: DollarAmount,
    planRenewal: DateTime,
    paymentDueAt: Option[DateTime] = None,
    paymentStatus: PaymentStatus = PaymentStatus.Ok,
    userContacts: Seq[Id[User]],
    emailContacts: Seq[EmailAddress],
    lockedForProcessing: Boolean = false,
    frozen: Boolean = false,
    activeUsers: Int) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
  def withState(state: State[PaidAccount]): PaidAccount = this.copy(state = state)
  def withPlanRenewal(renewal: DateTime): PaidAccount = this.copy(planRenewal = renewal)
  def withPaymentStatus(status: PaymentStatus): PaidAccount = this.copy(paymentStatus = status)
  def withPaymentDueAt(dueAt: Option[DateTime]): PaidAccount = this.copy(paymentDueAt = dueAt)
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
}

object PaidAccountStates extends States[PaidAccount]
