package com.keepit.payments

import com.keepit.common.db._
import com.keepit.common.time._
import com.keepit.model._
import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser

import com.kifi.macros.json
import org.joda.time.DateTime
import play.api.libs.json.{ JsResult, Format, JsValue, JsString }

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
}

object DollarAmount {
  def wholeDollars(dollars: Int): DollarAmount = DollarAmount(dollars * 100)

  val ZERO = DollarAmount(0)

  val dollarStringFormat = new Format[DollarAmount] {
    def writes(o: DollarAmount) = JsString(o.toDollarString)

    // this is a fragile reads, shouldn't be used anywhere but tests until improved or fully-spec'd
    def reads(json: JsValue): JsResult[DollarAmount] = json.validate[String].map { str =>
      val centsString = str.drop(str.indexOf('$') + 1).replace(".", "")
      DollarAmount(centsString.toInt)
    }
  }
}

@json
case class SimpleAccountContactInfo(who: BasicUser, enabled: Boolean)

case class PaidAccount(
    id: Option[Id[PaidAccount]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[PaidAccount] = PaidAccountStates.ACTIVE,
    orgId: Id[Organization],
    planId: Id[PaidPlan],
    credit: DollarAmount,
    userContacts: Seq[Id[User]],
    emailContacts: Seq[EmailAddress],
    lockedForProcessing: Boolean = false,
    frozen: Boolean = false,
    activeUsers: Int,
    billingCycleStart: DateTime) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
  def withState(state: State[PaidAccount]): PaidAccount = this.copy(state = state)
  def freeze: PaidAccount = this.copy(frozen = true) //a frozen account will not be charged anything by the payment processor until unfrozen by an admin. Intended for automatically detected data integrity issues.

  def owed: DollarAmount = DollarAmount.ZERO max -credit

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

  def withNewPlan(newPlanId: Id[PaidPlan]): PaidAccount = this.copy(planId = newPlanId)

  def withCycleStart(newCycleStart: DateTime): PaidAccount = this.copy(billingCycleStart = newCycleStart)

  def lastBillingFailed: Boolean = false //I believe Ryan is working on tracking that in this model (per github comment) so this should be filled in or replaced when that is done
}

object PaidAccountStates extends States[PaidAccount]
