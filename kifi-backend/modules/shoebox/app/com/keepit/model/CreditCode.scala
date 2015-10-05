package com.keepit.model

import java.net.URLEncoder

import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.payments.{ PaidAccount, DollarAmount }
import org.joda.time.DateTime
import play.api.libs.json._

import scala.concurrent.duration.Duration

case class CreditCode(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object CreditCode {
  def normalize(rawCode: String): CreditCode = CreditCode(rawCode.toLowerCase.trim)
  implicit def format: Format[CreditCode] = Format(Reads.of[String].map(normalize(_)), Writes[CreditCode](code => JsString(code.value)))
}

sealed abstract class CreditCodeKind(val value: String)
object CreditCodeKind {
  case object Immediate extends CreditCodeKind("immediate")
  case object UserReferral extends CreditCodeKind("user_referral")
  case object OrgCreation extends CreditCodeKind("org_creation")
  case object OrgCreditCardSetup extends CreditCodeKind("org_credit_card_setup")
  case object OrgFirstPayment extends CreditCodeKind("org_first_payment")
}

sealed abstract class CreditCodeCampaign(val value: String)
object CreditCodeCampaign {
  case object OrgLaunch extends CreditCodeCampaign("org_launch")
}

sealed abstract class CreditCodeStatus(val value: String)
object CreditCodeStatus {
  case object Open extends CreditCodeStatus("open")
  case object Closed extends CreditCodeStatus("closed")
}

// Unique index on (code)
case class CreditCodeInfo(
    id: Option[Id[CreditCodeInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditCodeInfo] = CreditCodeInfoStates.ACTIVE,
    code: CreditCode,
    kind: CreditCodeKind, // used to determine reward
    credit: DollarAmount,
    status: CreditCodeStatus,
    campaign: Option[CreditCodeCampaign], // way of grouping codes together
    validityPeriod: Option[Duration],
    referrerUserId: Option[Id[User]], // do we need all three of these fields?
    referrerOrganizationId: Option[Id[Organization]], // do we need all three of these fields?
    referrerAccount: Option[PaidAccount], // do we need all three of these fields?
    referrerCredit: Option[DollarAmount]) extends ModelWithState[CreditCodeInfo] {
  def withId(id: Id[CreditCodeInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object CreditCodeInfoStates extends States[CreditCodeInfo]