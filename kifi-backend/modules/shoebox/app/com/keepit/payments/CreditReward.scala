package com.keepit.payments

import com.keepit.common.crypto.PublicId
import com.keepit.common.db.{Id, ModelWithState, State, States}
import com.keepit.common.reflection.Enumerator
import com.keepit.common.time._
import com.keepit.model.{OrganizationAvatar, Organization, User}
import com.keepit.payments.RewardStatus.WithIndependentInfo
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
  override def toString = Reward.unapply(this).toString
}

sealed abstract class RewardKind(val kind: String) extends Ordered[RewardKind] {
  type S <: RewardStatus
  protected def allStatus: Set[S]
  def applicable: S
  def writeStatus(status: S): String = status.status

  private def get(str: String): Option[S] = allStatus.find(_.status equalsIgnoreCase str)
  def readStatus(str: String): S = get(str).getOrElse(throw new IllegalArgumentException(s"Invalid status for $this reward: $str"))
  override def toString = kind

  def compare(that: RewardKind) = this.kind compare that.kind
}

sealed trait RewardStatus {
  type I
  def infoFormat: Format[I]
  def status: String
  override def toString = status
}

object Reward {
  def apply[K <: RewardKind](k: K)(s: k.S)(i: s.I): Reward = {
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

  private lazy val noneInfoFormat: Format[None.type] = Format(
    Reads {
      case JsNull => JsSuccess(None)
      case unknown => JsError(s"Unknown RewardStatus info: $unknown")
    },
    Writes(None => JsNull)
  )
}

object RewardKind extends Enumerator[RewardKind] {
  case object Coupon extends RewardKind("coupon") with RewardStatus.WithEmptyInfo {
    case object Used extends Status("used")
    protected lazy val allStatus: Set[S] = Set(Used)
    lazy val applicable: Used.type = Used
  }
  case object OrganizationReferral extends RewardKind("org_referral") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    lazy val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    case object Upgraded extends Status("upgraded")
    protected lazy val allStatus: Set[S] = Set(Created, Upgraded)
    lazy val applicable: Upgraded.type = Upgraded
  }
  case object ReferralApplied extends RewardKind("referral_applied") with RewardStatus.WithIndependentInfo[CreditCode] {
    lazy val infoFormat = CreditCode.format
    case object Applied extends Status("applied")
    protected lazy val allStatus: Set[S] = Set(Applied)
    lazy val applicable: Applied.type = Applied
  }
  case object OrganizationCreation extends RewardKind("org_creation") with RewardStatus.WithEmptyInfo {
    case object Created extends Status("created")
    protected lazy val allStatus: Set[S] = Set(Created)
    lazy val applicable: Created.type = Created
  }


  sealed abstract class RewardChecklistKind(kind: String) extends RewardKind(kind) with WithIndependentInfo[Id[Organization]] {
    lazy val infoFormat = Id.format[Organization]
    case object Started extends Status("started")
    case object Achieved extends Status("achieved")
    protected lazy val allStatus: Set[S] = Set(Started, Achieved)
    lazy val applicable: Achieved.type = Achieved
  }

  sealed abstract class ThresholdChecklistKind(val prefix: String, val threshold: Int) extends RewardChecklistKind(s"${prefix}_${threshold}") {
    override def compare(that: RewardKind) = that match {
      case thresh: ThresholdChecklistKind if this.prefix == thresh.prefix => this.threshold compare thresh.threshold
      case other => super.compare(that)
    }
  }
  sealed abstract class OrganizationMembersReached(threshold: Int) extends ThresholdChecklistKind("org_members", threshold)
  sealed abstract class OrganizationLibrariesReached(threshold: Int) extends ThresholdChecklistKind("org_libraries", threshold)

  case object OrganizationAvatarUploaded extends RewardChecklistKind("org_avatar_uploaded")
  case object OrganizationDescriptionAdded extends RewardChecklistKind("org_description_added")
  object OrganizationMembersReached extends Enumerator[OrganizationMembersReached] {
    case object OrganizationMembersReached5 extends OrganizationMembersReached(5)
    case object OrganizationMembersReached10 extends OrganizationMembersReached(10)
    case object OrganizationMembersReached15 extends OrganizationMembersReached(15)
    case object OrganizationMembersReached20 extends OrganizationMembersReached(20)
    case object OrganizationMembersReached25 extends OrganizationMembersReached(25)
    case object OrganizationMembersReached1_DUMMYKIND extends OrganizationMembersReached(1)

    val all = _all.toSet
  }

  object OrganizationLibrariesReached extends Enumerator[OrganizationLibrariesReached] {
    case object OrganizationLibrariesReached7 extends OrganizationLibrariesReached(7)
    val all = _all.toSet
  }

  case object OrganizationGeneralLibraryKeepsReached50 extends ThresholdChecklistKind("org_general_keeps", 50)


  val all = _all.toSet
  val deprecated: Set[RewardKind] = Set(OrganizationMembersReached.OrganizationMembersReached1_DUMMYKIND)
  val allActive = all -- deprecated

  val orgLibsReached = OrganizationLibrariesReached.all
  val orgMembersReached = OrganizationMembersReached.all

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
  def withState(newState: State[CreditReward]) = this.copy(state = newState)
}


sealed abstract class RewardTrigger(val value: String)
object RewardTrigger {
  case class OrganizationUpgraded(orgId: Id[Organization], newPlan: PaidPlan) extends RewardTrigger(s"$orgId upgraded to plan ${newPlan.id.get}")
  case class OrganizationDescriptionAdded(orgId: Id[Organization], description: String) extends RewardTrigger(s"$orgId filled in their description")
  case class OrganizationAvatarUploaded(orgId: Id[Organization]) extends RewardTrigger(s"$orgId uploaded an avatar")
  case class OrganizationKeepAddedToGeneralLibrary(orgId: Id[Organization], keepCount: Int) extends RewardTrigger(s"$orgId reached $keepCount keeps in their General library")
  case class OrganizationAddedLibrary(orgId: Id[Organization], libraryCount: Int) extends RewardTrigger(s"$orgId reached $libraryCount total libraries")
  case class OrganizationMemberAdded(orgId: Id[Organization], memberCount: Int) extends RewardTrigger(s"$orgId reached $memberCount total members")
}

sealed abstract class RewardCategory(val value: String, val priority: Int) extends Ordered[RewardCategory] {
  def compare(that: RewardCategory) = this.priority compare that.priority
}
object RewardCategory extends Enumerator[RewardCategory] {
  case object KeepsAndLibraries extends RewardCategory("keeps_and_libraries", 0)
  case object OrganizationInformation extends RewardCategory("org_information", 1)
  case object OrganizationMembership extends RewardCategory("org_membership", 2)
  case object Referrals extends RewardCategory("referrals", 3)
  case object CreditCodes extends RewardCategory("credit_codes", 4)

  val all = _all.toSet
  def get(str: String): Option[RewardCategory] = all.find(_.value == str)
  implicit val writes: Writes[RewardCategory] = Writes { rc => JsString(rc.value) }

  def forKind(k: RewardKind): RewardCategory = k match {
    case RewardKind.Coupon => CreditCodes
    case RewardKind.OrganizationReferral => Referrals
    case RewardKind.ReferralApplied => CreditCodes

    case RewardKind.OrganizationAvatarUploaded  => OrganizationInformation
    case RewardKind.OrganizationCreation => OrganizationInformation
    case RewardKind.OrganizationDescriptionAdded => OrganizationInformation
    case _: RewardKind.OrganizationMembersReached => OrganizationMembership
    case _: RewardKind.OrganizationLibrariesReached => KeepsAndLibraries
    case RewardKind.OrganizationGeneralLibraryKeepsReached50 => KeepsAndLibraries
  }
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
