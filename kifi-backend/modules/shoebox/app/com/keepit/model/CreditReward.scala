package com.keepit.model

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.mail.EmailAddress
import com.keepit.common.time._
import com.keepit.payments.{ DollarAmount, PaidAccount }
import org.joda.time.DateTime

import scala.concurrent.duration.Duration
import scala.util.Try
import scala.util.matching.Regex

// todo(Léo): CompanionTypeSystem?
sealed abstract class CreditRedemption(val trigger: String)
object CreditRedemption {
  private val immediate = "immediate"
  case object Immediate extends CreditRedemption(immediate)

  private val user_joins = "user_joins"
  case class UserJoins(email: EmailAddress) extends CreditRedemption(user_joins)

  private val user_created_organization = "user_created_organization"
  case class UserCreatesOrganization(userId: Id[User]) extends CreditRedemption(user_created_organization)

  private val org_adds_credit_card = "org_adds_credit_card"
  case class OrgAddsCreditCard(orgId: Id[Organization]) extends CreditRedemption(org_adds_credit_card)

  private val org_is_charged = "org_is_charged"
  case class OrgIsCharged(orgId: Id[Organization]) extends CreditRedemption(org_is_charged)

  private object Long {
    def unapply(id: String): Option[Long] = Try(id.toLong).toOption
  }

  private object Email {
    def unapply(id: String): Option[EmailAddress] = EmailAddress.validate(id).toOption
  }

  def apply(trigger: String, triggerId: Option[String]): CreditRedemption = (trigger, triggerId) match {
    case (`immediate`, None) => Immediate
    case (`user_joins`, Some(Email(email))) => UserJoins(email)
    case (`user_created_organization`, Some(Long(id))) => UserCreatesOrganization(Id(id))
    case (`org_adds_credit_card`, Some(Long(id))) => OrgAddsCreditCard(Id(id))
    case (`org_is_charged`, Some(Long(id))) => OrgIsCharged(Id(id))
  }
}

sealed abstract class CreditRewardStatus(val value: String)
object CreditRewardStatus {
  case object Pending extends CreditRewardStatus("pending")
  case object Applied extends CreditRewardStatus("applied")
  case object Expired extends CreditRewardStatus("expired")
}

// Unique index on (account, code) - a code can only be applied to an account once
// Unique index on (account, campaign) - two codes from the same campaign cannot be applied to the same account
// Referential integrity constraint from CreditReward.code to CreditCode.code
// Referential integrity constraint from CreditReward.(code, campaign) to CreditCode.(code, campaign)
// Referential integrity constraint from CreditReward.accountId to PaidAccount.id

case class CreditReward(
    id: Option[Id[CreditReward]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditReward] = CreditRewardStates.ACTIVE,
    code: CreditCode,
    campaign: Option[CreditCodeCampaign],
    status: CreditRewardStatus,
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    userId: Id[User],
    validityPeriod: Option[Duration],
    redemption: CreditRedemption) extends ModelWithState[CreditReward] {
  def withId(id: Id[CreditReward]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object CreditRewardStates extends States[CreditReward]
