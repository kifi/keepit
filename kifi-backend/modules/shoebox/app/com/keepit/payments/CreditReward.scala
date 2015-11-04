package com.keepit.payments

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{Id, ModelWithState, State, States}
import com.keepit.common.time._
import com.keepit.model.{Organization, User}
import org.joda.time.DateTime
import play.api.libs.functional.syntax._
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
  protected def allStatus: Set[S]
  def applicable: S
  def writeStatus(status: S): String = status.status

  private def get(str: String): Option[S] = allStatus.find(_.status equalsIgnoreCase str)
  def readStatus(str: String): S = get(str).getOrElse(throw new IllegalArgumentException(s"Invalid status for $this reward: $str"))
  def category: RewardCategory
}

sealed trait RewardStatus {
  type I
  def infoFormat: Format[I]
  def status: String
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

    sealed abstract class Status(value: String) extends RewardStatus {
      type I = F
      def infoFormat = self.infoFormat
      def status = value
    }
    type S = Status
  }

  trait WithEmptyInfo extends WithIndependentInfo[None.type] { self: RewardKind =>
    def infoFormat: Format[None.type] = noneInfoFormat
  }

  private val noneInfoFormat: Format[None.type] = Format(
    Reads {
      case JsNull => JsSuccess(None)
      case unknown => JsError(s"Unknown RewardStatus info: $unknown")
    },
    Writes(None => JsNull)
  )
}

object RewardKind {
  case object Coupon extends RewardKind("coupon") with RewardStatus.WithEmptyInfo {
    case object Used extends Status("used")
    protected lazy val allStatus: Set[S] = Set(Used)
    lazy val applicable: Used.type = Used
    lazy val category = RewardCategory.CreditCodes
  }

  case object OrganizationCreation extends RewardKind("org_creation") with RewardStatus.WithEmptyInfo {
    case object Created extends Status("created")
    protected lazy val allStatus: Set[S] = Set(Created)
    lazy val applicable: Created.type = Created
    lazy val category = RewardCategory.OrganizationInformation
  }

  case object OrganizationDescriptionAdded extends RewardKind("org_description_added") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    lazy val infoFormat = Id.format[Organization]
    case object Started extends Status("started")
    case object Achieved extends Status("achieved")
    protected lazy val allStatus: Set[S] = Set(Started, Achieved)
    lazy val applicable: Achieved.type = Achieved
    lazy val category = RewardCategory.OrganizationInformation
  }

  case object OrganizationReferral extends RewardKind("org_referral") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    lazy val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    case object Upgraded extends Status("upgraded")
    protected lazy val allStatus: Set[S] = Set(Created, Upgraded)
    lazy val applicable: Upgraded.type = Upgraded
    lazy val category = RewardCategory.Referrals
  }

  case object ReferralApplied extends RewardKind("referral_applied") with RewardStatus.WithIndependentInfo[CreditCode] {
    lazy val infoFormat = CreditCode.format
    case object Applied extends Status("applied")
    protected lazy val allStatus: Set[S] = Set(Applied)
    lazy val applicable: Applied.type = Applied
    lazy val category = RewardCategory.CreditCodes
  }


  private val all: Set[RewardKind] = Set(
    Coupon,
    OrganizationCreation,
    OrganizationDescriptionAdded,
    OrganizationReferral,
    ReferralApplied
  )

  def get(kind: String) = all.find(_.kind equalsIgnoreCase kind)
  def apply(kind: String) = get(kind).getOrElse(throw new IllegalArgumentException(s"Unknown RewardKind: $kind"))
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
  // only one account can reap the reward for referring an org
  case class Referred(orgId: Id[Organization]) extends UnrepeatableRewardKey { def toKey = s"referred-org|$orgId" }
  // an account can only reap a single referral bonus when signing up (promo or referral)
  case class WasReferred(orgId: Id[Organization]) extends UnrepeatableRewardKey { def toKey = s"wasReferred-org|$orgId" }
  case class WasCreated(orgId: Id[Organization]) extends UnrepeatableRewardKey { def toKey = s"wasCreated-org|$orgId" }

  private object ValidLong {
    def unapply(id: String): Option[Long] = Try(id.toLong).toOption
  }
  private val referred = """^referred-org\|(\d+)$""".r
  private val wasReferred = """^wasReferred-org\|(\d+)$""".r
  private val wasCreated = """^wasCreated-org\|(\d+)$""".r
  def fromKey(key: String): UnrepeatableRewardKey = key match {
    case referred(ValidLong(orgId)) => Referred(Id(orgId))
    case wasReferred(ValidLong(orgId)) => WasReferred(Id(orgId))
    case wasCreated(ValidLong(orgId)) => WasCreated(Id(orgId))
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
  def withAppliedEvent(event: AccountEvent) = this.copy(applied = Some(event.id.get))
  def withReward(newReward: Reward) = this.copy(reward = newReward)
}


sealed abstract class RewardTrigger(val value: String)
object RewardTrigger {
  case class OrganizationUpgraded(orgId: Id[Organization], newPlan: PaidPlan) extends RewardTrigger(s"$orgId upgraded to plan ${newPlan.id.get}")
  case class OrganizationDescriptionAdded(orgId: Id[Organization], description: String) extends RewardTrigger(s"$orgId filled in their description")
}


sealed abstract class RewardCategory(val value: String, val priority: Int) extends Ordered[RewardCategory] {
  def compare(that: RewardCategory) = this.priority compare that.priority
}
object RewardCategory {
  case object KeepsAndLibraries extends RewardCategory("keeps_and_libraries", 0)
  case object OrganizationInformation extends RewardCategory("org_information", 1)
  case object OrganizationMembership extends RewardCategory("org_membership", 2)
  case object Referrals extends RewardCategory("referrals", 3)
  case object CreditCodes extends RewardCategory("credit_codes", 4)

  val all: Set[RewardCategory] = Set(CreditCodes, KeepsAndLibraries, OrganizationInformation, OrganizationMembership, Referrals)
  def get(str: String): Option[RewardCategory] = all.find(_.value == str)
  implicit val writes: Writes[RewardCategory] = Writes { rc => JsString(rc.value) }
}
case class ExternalCreditReward(
  description: DescriptionElements,
  applied: Option[PublicId[AccountEvent]]
)
object ExternalCreditReward {
  implicit val writes = Json.writes[ExternalCreditReward]
}

case class CreditRewardsView(rewards: Map[RewardCategory, Seq[ExternalCreditReward]])
object CreditRewardsView {
  private implicit val categorizedRewardsWrites: Writes[Map[RewardCategory, Seq[ExternalCreditReward]]] = Writes { m =>
    JsArray(m.toSeq.sortBy(_._1).map {
      case (category, rewards) => Json.obj("category" -> category, "items" -> rewards)
    })
  }
  implicit val writes = Json.writes[CreditRewardsView]
}
