package com.keepit.payments

import com.keepit.common.db.{ States, ModelWithState, Id, State }
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import com.keepit.common.mail.EmailAddress
import com.keepit.social.BasicUser

import com.kifi.macros.json

import org.joda.time.DateTime

case class DollarAmount(cents: Int) extends AnyVal {
  def +(other: DollarAmount) = DollarAmount(cents + other.cents)

  override def toString = s"$$${(cents.toFloat / 100.0)}"
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
    settingsConfiguration: PlanSettingsConfiguration) extends ModelWithState[PaidAccount] {

  def withId(id: Id[PaidAccount]): PaidAccount = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime): PaidAccount = this.copy(updatedAt = now)
  def withState(state: State[PaidAccount]): PaidAccount = this.copy(state = state)
  def freeze: PaidAccount = this.copy(frozen = true) //a frozen account will not be charged anything by the payment processor until unfrozen by an admin. Intended for automatically detected data integrity issues.
}

object PaidAccountStates extends States[PaidAccount]
