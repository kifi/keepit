package com.keepit.payments

import java.net.URLEncoder

import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.http.Status._
import play.api.mvc.Results.Status

case class CreditCode(value: String) {
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object CreditCode {
  def normalize(rawCode: String): CreditCode = CreditCode(rawCode.toLowerCase.trim)
  implicit val format: Format[CreditCode] = Format(Reads.of[String].map(normalize(_)), Writes[CreditCode](code => JsString(code.value)))
  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[CreditCode, String](_.value, CreditCode.apply)
  }
}

sealed abstract class CreditCodeKind(val kind: String)
object CreditCodeKind {
  case object Coupon extends CreditCodeKind("coupon")
  case object OrganizationReferral extends CreditCodeKind("org_referral")
  case object Promotion extends CreditCodeKind("promotion")

  def isSingleUse(kind: CreditCodeKind) = kind match {
    case Coupon => true
    case OrganizationReferral => false
    case Promotion => false
  }

  private val all: Set[CreditCodeKind] = Set(Coupon, OrganizationReferral, Promotion)

  def apply(kind: String) = all.find(_.kind equalsIgnoreCase kind) match {
    case Some(validKind) => validKind
    case None => throw new IllegalArgumentException(s"Unknown CreditCodeKind: $kind")
  }

  def creditValue(kind: CreditCodeKind) = kind match {
    case Coupon => DollarAmount.dollars(42)
    case OrganizationReferral => DollarAmount.dollars(19)
    case Promotion => throw new IllegalArgumentException("Promotion credit codes do not have a defined value for the redeemer")
  }
  def referrerCreditValue(kind: CreditCodeKind) = kind match {
    case Coupon => None
    case OrganizationReferral => Some(DollarAmount.dollars(100))
    case Promotion => throw new IllegalArgumentException("Promotion credit codes do not have a defined value for the referrer")
  }
}

case class CreditCodeReferrer(userId: Id[User], organizationId: Option[Id[Organization]])
object CreditCodeReferrer {
  def fromOrg(org: Organization): CreditCodeReferrer = CreditCodeReferrer(org.ownerId, Some(org.id.get))
}

sealed abstract class CreditCodeStatus(val value: String)
object CreditCodeStatus {
  case object Open extends CreditCodeStatus("open")
  case object Closed extends CreditCodeStatus("closed")

  private val all = Set(Open, Closed)
  def apply(value: String) = all.find(_.value equalsIgnoreCase value) match {
    case Some(validStatus) => validStatus
    case None => throw new IllegalArgumentException(s"Invalid CreditCodeStatus: $value")
  }
}

object CreditCodeInfoStates extends States[CreditCodeInfo]

case class CreditCodeInfo(
    id: Option[Id[CreditCodeInfo]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditCodeInfo] = CreditCodeInfoStates.ACTIVE,
    code: CreditCode,
    kind: CreditCodeKind,
    credit: DollarAmount,
    status: CreditCodeStatus,
    referrer: Option[CreditCodeReferrer]) extends ModelWithState[CreditCodeInfo] {
  def withId(id: Id[CreditCodeInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)

  def isSingleUse: Boolean = CreditCodeKind.isSingleUse(kind)

  def referrerCredit: Option[DollarAmount] = CreditCodeKind.referrerCreditValue(kind)
}

case class CreditCodeRewards(target: CreditReward, referrer: Option[CreditReward])

case class CreditCodeApplyRequest(
  code: CreditCode,
  applierId: Id[User],
  orgId: Option[Id[Organization]])

sealed abstract class CreditRewardFail(val status: Int, val message: String) extends Exception(message) {
  def asErrorResponse = Status(status)(Json.obj("error" -> message))
}
object CreditRewardFail {
  case class UnavailableCreditCodeException(code: CreditCode) extends CreditRewardFail(CONFLICT, s"Credit code $code is unavailable")
  case class CreditCodeNotFoundException(code: CreditCode) extends CreditRewardFail(BAD_REQUEST, s"Credit code $code does not exist")
  case class CreditCodeAbuseException(req: CreditCodeApplyRequest) extends CreditRewardFail(BAD_REQUEST, s"Credit code ${req.code} cannot be applied by user ${req.applierId} in org ${req.orgId}")
  case class CreditCodeAlreadyBurnedException(code: CreditCode) extends CreditRewardFail(BAD_REQUEST, s"Credit code $code has already been used")
  case class NoPaidAccountException(userId: Id[User], orgIdOpt: Option[Id[Organization]]) extends CreditRewardFail(BAD_REQUEST, s"User $userId in org $orgIdOpt has no paid account")
  case class UnrepeatableRewardKeyCollisionException(key: UnrepeatableRewardKey) extends CreditRewardFail(BAD_REQUEST, s"A reward with unrepeatable key $key (${key.toKey}) already exists")
}
