package com.keepit.payments

import com.keepit.common.db.{Id, ModelWithState, State, States}
import com.keepit.common.time._
import com.keepit.model.{Organization, User}
import org.joda.time.DateTime
import play.api.libs.json._

import scala.util.Try

trait Reward {
  val kind: RewardKind
  val status: kind.S
  val info: status.I

  override def equals(that: Any): Boolean = that match {
    case thatReward: Reward =>
      kind == thatReward.kind &&
        status == thatReward.status &&
        info == thatReward.info
    case _ => false
  }

  override def hashCode: Int = Seq(kind, status, info).map(_.hashCode()).reduce(_ ^ _)
}

sealed abstract class RewardKind(val kind: String) {
  type S <: RewardStatus
  protected val allStatus: Set[S]
  val applicable: S
  def writeStatus(status: S): String = status.status
  def readStatus(status: String): S = allStatus.find(_.status equalsIgnoreCase status) match {
    case Some(validStatus) => validStatus
    case None => throw new IllegalArgumentException(s"Invalid status for $this reward: $status")
  }
}

sealed abstract class RewardStatus(val status: String) {
  type I
  def infoFormat: Format[I]
}

object Reward {
  def apply(k: RewardKind)(s: k.S)(i: s.I): Reward = {
    new Reward {
      val kind: k.type = k
      val status: s.type = s
      val info: status.I = i
    }
  }

  def unapply(reward: Reward): Option[(reward.kind.type, reward.kind.S, reward.status.I)] = {
    Some((
      reward.kind,
      reward.status,
      reward.info
      ))
  }

  def applyFromDbRow(kind: RewardKind, status: String, info: Option[JsValue]): Reward = {
    val validStatus: kind.S = kind.readStatus(status)
    val validInfo: validStatus.I = (info getOrElse JsNull).as(validStatus.infoFormat)
    Reward(kind)(validStatus)(validInfo)
  }

  def unapplyToDbRow(reward: Reward): Option[(RewardKind, String, Option[JsValue])] = {
    Some((
      reward.kind,
      reward.kind.writeStatus(reward.status),
      reward.status.infoFormat.writes(reward.info) match {
        case JsNull => None
        case actualInfo => Some(actualInfo)
      }
      ))
  }
}

object RewardStatus {

  trait WithIndependentInfo[F] { self: RewardKind =>
    def infoFormat: Format[F]

    sealed abstract class Status(status: String) extends RewardStatus(status) {
      type I = F
      def infoFormat = self.infoFormat
    }
    type S = Status
  }

  trait WithEmptyInfo extends WithIndependentInfo[None.type] { self: RewardKind =>
    val infoFormat: Format[None.type] = Format(
      Reads {
        case JsNull => JsSuccess(None)
        case unknown => JsError(s"Unknown RewardStatus info: $unknown")
      },
      Writes(None => JsNull)
    )
  }
}

object RewardKind {
  case object Coupon extends RewardKind("coupon") with RewardStatus.WithEmptyInfo {
    case object Used extends Status("used")
    protected val allStatus: Set[S] = Set(Used)
    val applicable: Used.type = Used
  }

  case object OrganizationCreation extends RewardKind("org_creation") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    protected val allStatus: Set[S] = Set(Created)
    val applicable: Created.type = Created
  }

  case object OrganizationReferral extends RewardKind("org_referral") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    case object Upgraded extends Status("upgraded")
    protected val allStatus: Set[S] = Set(Created, Upgraded)
    val applicable: Upgraded.type = Upgraded
  }

  private val all: Set[RewardKind] = Set(Coupon, OrganizationCreation, OrganizationReferral)

  def apply(kind: String) = all.find(_.kind equalsIgnoreCase kind) match {
    case Some(validKind) => validKind
    case None => throw new IllegalArgumentException(s"Unknown RewardKind: $kind")
  }
}

// subset of CreditCodeInfo
case class UsedCreditCode(code: CreditCode, singleUse: Boolean, usedBy: Id[User])
object UsedCreditCode {
  def apply(code: CreditCode, kind: CreditCodeKind, usedBy: Id[User]): UsedCreditCode = UsedCreditCode(code, CreditCodeKind.isSingleUse(kind), usedBy)
  def apply(codeInfo: CreditCodeInfo, usedBy: Id[User]): UsedCreditCode = UsedCreditCode(codeInfo.code, CreditCodeKind.isSingleUse(codeInfo.kind), usedBy)

  def applyFromDbRow(code: CreditCode, singleUse: Option[Boolean], usedBy: Id[User]): UsedCreditCode = UsedCreditCode(code, singleUse.contains(true), usedBy)
  def unapplyToDbRow(code: UsedCreditCode): Option[(CreditCode, Option[Boolean], Id[User])] = Some((code.code, if (code.singleUse) Some(true) else None, code.usedBy))
}

sealed trait UnrepeatableRewardKey {
  def toKey: String
}

object UnrepeatableRewardKey {
  case class ForUser(userId: Id[User]) extends UnrepeatableRewardKey { def toKey = s"user|$userId" }
  case class ForOrganization(orgId: Id[Organization]) extends UnrepeatableRewardKey { def toKey = s"org|$orgId" }
  case class ForOrganizationMember(orgId: Id[Organization], userId: Id[User]) extends UnrepeatableRewardKey { def toKey = s"org|$orgId-user$userId" }

  private object ValidLong {
    def unapply(id: String): Option[Long] = Try(id.toLong).toOption
  }
  private val userKey = """^user\|(\d+)$""".r
  private val organizationKey = """^org\|(\d+)$""".r
  private val organizationMemberKey = """^org\|(\d+)\-user\|(\d+)$""".r
  def fromKey(key: String): UnrepeatableRewardKey = key match {
    case userKey(ValidLong(userId)) => ForUser(Id(userId))
    case organizationKey(ValidLong(orgId)) => ForOrganization(Id(orgId))
    case organizationMemberKey(ValidLong(orgId), ValidLong(userId)) => ForOrganizationMember(Id(orgId), Id(userId))
    case _ => throw new IllegalArgumentException(s"Invalid reward key: $key")
  }
}

object CreditRewardStates extends States[CreditReward]

case class CreditReward(
    id: Option[Id[CreditReward]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditReward] = CreditRewardStates.ACTIVE,
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    applied: Option[Id[AccountEvent]],
    reward: Reward,
    unrepeatable: Option[UnrepeatableRewardKey],
    code: Option[UsedCreditCode]) extends ModelWithState[CreditReward] {
  def withId(id: Id[CreditReward]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}
