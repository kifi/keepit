package com.keepit.payments

import java.net.URLEncoder

import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.healthcheck.AirbrakeNotifierStatic
import com.keepit.common.json.EnumFormat
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import org.joda.time.DateTime
import play.api.libs.json._
import play.api.http.Status._
import play.api.mvc.Results.Status

case class CreditCode(value: String) {
  AirbrakeNotifierStatic.verify(value == value.toUpperCase.trim, s"CreditCode $value is not normalized")
  def urlEncoded: String = URLEncoder.encode(value, UTF8)
}

object CreditCode {
  def normalize(rawCode: String): CreditCode = CreditCode(rawCode.toUpperCase.trim.replaceAllLiterally(" ", "_"))
  implicit val format: Format[CreditCode] = Format(Reads.of[String].map(normalize), Writes[CreditCode](code => JsString(code.value)))
  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[CreditCode, String](_.value, CreditCode(_))
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
  private def get(kind: String) = all.find(_.kind equalsIgnoreCase kind)

  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[CreditCodeKind, String](_.kind, get(_).get)
  }

  val reads = EnumFormat.reads(get)
}

case class CreditCodeReferrer(userId: Id[User], organizationId: Option[Id[Organization]], credit: DollarAmount)

sealed abstract class CreditCodeStatus(val value: String)
object CreditCodeStatus {
  case object Open extends CreditCodeStatus("open")
  case object Closed extends CreditCodeStatus("closed")

  private val all = Set(Open, Closed)
  private def get(value: String): Option[CreditCodeStatus] = all.find(_.value equalsIgnoreCase value)

  def columnType(db: DataBaseComponent) = {
    import db.Driver.simple._
    MappedColumnType.base[CreditCodeStatus, String](_.value, get(_).get)
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
  def withStatus(newStatus: CreditCodeStatus) = this.copy(status = newStatus)

  def isSingleUse: Boolean = CreditCodeKind.isSingleUse(kind)
}

case class CreditCodeRewards(target: CreditReward, referrer: Option[CreditReward])

case class CreditCodeApplyRequest(
  code: CreditCode,
  applierId: Id[User],
  orgId: Option[Id[Organization]])

sealed abstract class CreditRewardFail(val status: Int, val message: String, val response: String) extends Exception(message) {
  def asErrorResponse = Status(status)(Json.obj("error" -> response))
}
object CreditRewardFail {
  case class UnavailableCreditCodeException(code: CreditCode) extends CreditRewardFail(CONFLICT, s"Credit code $code is unavailable", "code_unavailable")
  case class CreditCodeNotFoundException(code: CreditCode) extends CreditRewardFail(BAD_REQUEST, s"Credit code $code does not exist", "code_nonexistent")
  case class CreditCodeAbuseException(req: CreditCodeApplyRequest) extends CreditRewardFail(BAD_REQUEST, s"Credit code ${req.code} cannot be applied by user ${req.applierId} in org ${req.orgId}", "code_invalid")
  case class CreditCodeAlreadyBurnedException(code: CreditCode) extends CreditRewardFail(BAD_REQUEST, s"Credit code $code has already been used", "code_already_used")
  case class NoPaidAccountException(userId: Id[User], orgIdOpt: Option[Id[Organization]]) extends CreditRewardFail(BAD_REQUEST, s"User $userId in org $orgIdOpt has no paid account", "no_paid_account")
  case class NoReferrerException(creditCodeInfo: CreditCodeInfo) extends CreditRewardFail(BAD_REQUEST, s"Credit code ${creditCodeInfo.code} has kind ${creditCodeInfo.kind} but no referrer", "no_referrer")
  case class UnrepeatableRewardKeyCollisionException(key: UnrepeatableRewardKey) extends CreditRewardFail(BAD_REQUEST, s"A reward with unrepeatable key $key (${key.toKey}) already exists", "unrepeatable_reward")
}
