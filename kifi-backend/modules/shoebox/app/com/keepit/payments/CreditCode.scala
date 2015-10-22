package com.keepit.payments

import java.net.URLEncoder

import com.keepit.common.db.slick._
import com.keepit.common.db.{ Id, ModelWithState, State, States }
import com.keepit.common.strings._
import com.keepit.common.time._
import com.keepit.model.{ Organization, User }
import org.joda.time.DateTime
import play.api.libs.json._

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

  def isSingleUse(kind: CreditCodeKind) = kind match {
    case Coupon => true
    case OrganizationReferral => false
  }

  private val all: Set[CreditCodeKind] = Set(Coupon, OrganizationReferral)

  def apply(kind: String) = all.find(_.kind equalsIgnoreCase kind) match {
    case Some(validKind) => validKind
    case None => throw new IllegalArgumentException(s"Unknown CreditCodeKind: $kind")
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
    status: CreditCodeStatus,
    referrer: Option[CreditCodeReferrer]) extends ModelWithState[CreditCodeInfo] {
  def withId(id: Id[CreditCodeInfo]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object CreditCodeInfo {
}

case class CreditCodeRewards(target: CreditReward, referrer: Option[CreditReward])
