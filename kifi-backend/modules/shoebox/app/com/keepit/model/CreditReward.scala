package com.keepit.model

import com.google.inject.{Inject, Singleton, ImplementedBy}
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{DbRepo, DataBaseComponent, Repo}
import com.keepit.common.db.{ States, ModelWithState, State, Id }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.payments.{ DollarAmount, PaidAccount }
import org.joda.time.DateTime
import play.api.libs.json._

import scala.concurrent.duration.Duration
import scala.util.Try

trait Reward {
  val kind: RewardKind
  val status: kind.S
  val info: status.I

  override def equals(that: Any): Boolean = that match {
    case thatReward: Reward => {
      kind == thatReward.kind &&
        status == thatReward.status &&
        info == thatReward.info
    }
    case _ => false
  }

  override def hashCode: Int = Seq(kind, status, info).map(_.hashCode()).reduce(_ | _)
}

sealed abstract class RewardKind(val kind: String) {
  type S <: RewardStatus
  protected val allStatus: Set[S]
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

  def unapply(reward: Reward): Option[(RewardKind, reward.kind.S, reward.status.I)] = {
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
    case object Applied extends Status("applied")
    protected val allStatus: Set[S] = Set(Applied)
  }

  case object OrgCreation extends RewardKind("org_creation") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    protected val allStatus: Set[S] = Set(Created)
  }

  case object OrgReferral extends RewardKind("org_referral") with RewardStatus.WithIndependentInfo[Id[Organization]] {
    val infoFormat = Id.format[Organization]
    case object Created extends Status("created")
    case object Upgraded extends Status("upgraded")
    protected val allStatus: Set[S] = Set(Created, Upgraded)
  }

  def isUnrepeatable(kind: RewardKind): Boolean = kind match {
    case Coupon => false
    case OrgCreation => true
    case OrgReferral => false
  }

  private val all: Set[RewardKind] = Set(Coupon, OrgCreation, OrgReferral)

  def apply(kind: String) = all.find(_.kind equalsIgnoreCase kind) match {
    case Some(validKind) => validKind
    case None => throw new IllegalArgumentException(s"Unknown RewardKind: $kind")
  }
}

case class UsedCreditCode(code: CreditCode, singleUse: Boolean)
object UsedCreditCode {
  def apply(code: CreditCode, kind: CreditCodeKind): UsedCreditCode = UsedCreditCode(code, CreditCodeKind.isSingleUse(kind))
  def apply(codeInfo: CreditCodeInfo): UsedCreditCode = UsedCreditCode(codeInfo.code, codeInfo.kind)

  def applyFromDbRow(code: CreditCode, singleUse: Option[Boolean]): UsedCreditCode = UsedCreditCode(code, singleUse.contains(true))
  def unapplyToDbRow(code: UsedCreditCode): Option[(CreditCode, Option[Boolean])] = Some((code.code, if (code.singleUse) Some(true) else None))
}

object CreditRewardStates extends States[CreditReward]

case class CreditReward(
    id: Option[Id[CreditReward]] = None,
    createdAt: DateTime = currentDateTime,
    updatedAt: DateTime = currentDateTime,
    state: State[CreditReward] = CreditRewardStates.ACTIVE,
    userId: Option[Id[User]],
    organizationId: Option[Id[Organization]],
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    reward: Reward,
    unrepeatable: Boolean,
    validityPeriod: Option[Duration],
    code: Option[UsedCreditCode]) extends ModelWithState[CreditReward] {
  def withId(id: Id[CreditReward]) = this.copy(id = Some(id))
  def withUpdateTime(now: DateTime) = this.copy(updatedAt = now)
}

object CreditReward {
  def applyFromDbRow(
    id: Option[Id[CreditReward]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[CreditReward],
    userId: Option[Id[User]],
    organizationId: Option[Id[Organization]],
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    kind: RewardKind,
    status: String,
    info: Option[JsValue],
    unrepeatable: Option[Boolean],
    validityPeriod: Option[Duration],
    code: Option[CreditCode],
    singleUse: Option[Boolean]
  ): CreditReward = {
    CreditReward(
      id,
      createdAt,
      updatedAt,
      state,
      userId,
      organizationId,
      accountId,
      credit,
      Reward.applyFromDbRow(kind, status, info),
      unrepeatable.contains(true),
      validityPeriod,
      code.map(UsedCreditCode.applyFromDbRow(_, singleUse))
    )
  }

  def unapplyToDbRow(creditReward: CreditReward) = {
    Reward.unapplyToDbRow(creditReward.reward).map { case (kind, status, info) =>
      val (code, singleUse): (Option[CreditCode], Option[Boolean]) = creditReward.code.flatMap(UsedCreditCode.unapplyToDbRow).map {
        case (actualCode, singleUse) => (Some(actualCode), singleUse)
      } getOrElse (None, None)
      (
        creditReward.id,
        creditReward.createdAt,
        creditReward.updatedAt,
        creditReward.state,
        creditReward.userId,
        creditReward.organizationId,
        creditReward.accountId,
        creditReward.credit,
        kind,
        status,
        info,
        if (creditReward.unrepeatable) Some(true) else None,
        creditReward.validityPeriod,
        code,
        singleUse
      )
    }
  }
}

@ImplementedBy(classOf[CreditRewardRepoImpl])
trait CreditRewardRepo extends Repo[CreditReward] {
  def getByAccount(accountId: Id[PaidAccount], excludeState: Option[State[CreditReward]] = Some(CreditRewardStates.INACTIVE))(implicit session: RSession): Set[CreditReward]
  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward]
}

// Unique index on (code, singleUse) - single-use codes can only be used once overall
// Unique index on (accountId, code) - a code can only be applied to the same account once
// Unique index on (accountId, kind, unrepeatable) - two codes of the same unrepeatable kind cannot be applied to the same account
// Referential integrity constraint from CreditReward.(code, kind) to CreditCode.(code, kind)
// Referential integrity constraint from CreditReward.accountId to PaidAccount.id

@Singleton
class CreditRewardRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
  extends DbRepo[CreditReward] with CreditRewardRepo with Logging {

  import db.Driver.simple._

  implicit val dollarAmountColumnType = DollarAmount.columnType(db)
  implicit val kindColumnType = MappedColumnType.base[RewardKind, String](_.kind, RewardKind.apply)
  implicit val creditCodeTypeMapper = CreditCode.columnType(db)

  type RepoImpl = CreditRewardTable
  class CreditRewardTable(tag: Tag) extends RepoTable[CreditReward](db, tag, "credit_reward") {

    def userId = column[Option[Id[User]]]("user_id", O.Nullable)
    def organizationId = column[Option[Id[Organization]]]("organization_id", O.Nullable)
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)

    def kind = column[RewardKind]("kind", O.NotNull)
    def status = column[String]("status", O.NotNull)
    def info = column[Option[JsValue]]("info", O.Nullable)

    def unrepeatable = column[Option[Boolean]]("unrepeatable", O.Nullable)
    def validityPeriod = column[Option[Duration]]("validity_period", O.Nullable)

    def code = column[Option[CreditCode]]("code", O.Nullable)
    def singleUse = column[Option[Boolean]]("single_use", O.Nullable)

    def * = (id.?, createdAt, updatedAt, state, userId, organizationId, accountId, credit, kind, status, info, unrepeatable, validityPeriod, code, singleUse) <> ((CreditReward.applyFromDbRow _).tupled, CreditReward.unapplyToDbRow)
  }

  def table(tag: Tag) = new CreditRewardTable(tag)
  initTable()

  override def deleteCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}

  override def invalidateCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}

  def getByAccount(accountId: Id[PaidAccount], excludeState: Option[State[CreditReward]] = Some(CreditRewardStates.INACTIVE))(implicit session: RSession): Set[CreditReward] = ???
  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward] = ???
}
