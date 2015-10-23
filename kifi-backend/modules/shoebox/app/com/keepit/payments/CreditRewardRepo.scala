package com.keepit.payments

import com.google.inject.{ ImplementedBy, Inject, Singleton }
import com.keepit.common.db.{ State, Id }
import com.keepit.common.db.slick.DBSession.RSession
import com.keepit.common.db.slick.{ DataBaseComponent, DbRepo, Repo }
import com.keepit.common.logging.Logging
import com.keepit.common.time._
import com.keepit.model.User
import org.joda.time.DateTime
import play.api.libs.json._

@ImplementedBy(classOf[CreditRewardRepoImpl])
trait CreditRewardRepo extends Repo[CreditReward] {
  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward]
}

// Unique index on (code, singleUse) - single-use codes can only be used once overall
// Unique index on (kind, unrepeatable) - two codes of the same unrepeatable kind cannot be applied
// Referential integrity constraint from CreditReward.code CreditCode.code
// Referential integrity constraint from CreditReward.accountId to PaidAccount.id
// Referential integrity constraint from CreditReward.{accountId, applied, credit} to AccountEvent.{accountId, id, credit}
@Singleton
class CreditRewardRepoImpl @Inject() (
  val db: DataBaseComponent,
  val clock: Clock)
    extends DbRepo[CreditReward] with CreditRewardRepo with Logging {

  import db.Driver.simple._

  implicit val dollarAmountColumnType = DollarAmount.columnType(db)
  implicit val kindColumnType = MappedColumnType.base[RewardKind, String](_.kind, RewardKind.apply)
  implicit val creditCodeTypeMapper = CreditCode.columnType(db)
  implicit val unrepeatableTypeMapper = MappedColumnType.base[UnrepeatableRewardKey, String](_.toKey, UnrepeatableRewardKey.fromKey)

  type RepoImpl = CreditRewardTable
  class CreditRewardTable(tag: Tag) extends RepoTable[CreditReward](db, tag, "credit_reward") {
    def accountId = column[Id[PaidAccount]]("account_id", O.NotNull)
    def credit = column[DollarAmount]("credit", O.NotNull)
    def applied = column[Option[Id[AccountEvent]]]("applied", O.Nullable)
    def kind = column[RewardKind]("kind", O.NotNull)
    def status = column[String]("status", O.NotNull)
    def info = column[Option[JsValue]]("info", O.Nullable)
    def unrepeatable = column[Option[UnrepeatableRewardKey]]("unrepeatable", O.Nullable)

    def code = column[Option[CreditCode]]("code", O.Nullable)
    def singleUse = column[Option[Boolean]]("single_use", O.Nullable)
    def usedBy = column[Option[Id[User]]]("used_by", O.Nullable)
    def * = (id.?, createdAt, updatedAt, state, accountId, credit, applied, kind, status, info, unrepeatable, code, singleUse, usedBy) <> ((CreditRewardRepo.applyFromDbRow _).tupled, CreditRewardRepo.unapplyToDbRow)
  }

  def table(tag: Tag) = new CreditRewardTable(tag)
  initTable()

  override def deleteCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}
  override def invalidateCache(creditReward: CreditReward)(implicit session: RSession): Unit = {}

  def getByReward(reward: Reward)(implicit session: RSession): Set[CreditReward] = {
    val kind = reward.kind
    val status = reward.kind.writeStatus(reward.status)
    val info = reward.status.infoFormat.writes(reward.info)
    rows.filter(row => row.kind === kind && row.status === status && row.info === info).list.toSet
  }
}

object CreditRewardRepo {
  def applyFromDbRow(
    id: Option[Id[CreditReward]],
    createdAt: DateTime,
    updatedAt: DateTime,
    state: State[CreditReward],
    accountId: Id[PaidAccount],
    credit: DollarAmount,
    applied: Option[Id[AccountEvent]],
    kind: RewardKind,
    status: String,
    info: Option[JsValue],
    unrepeatable: Option[UnrepeatableRewardKey],
    code: Option[CreditCode],
    singleUse: Option[Boolean],
    usedBy: Option[Id[User]]): CreditReward = {
    CreditReward(
      id,
      createdAt,
      updatedAt,
      state,
      accountId,
      credit,
      applied,
      Reward.applyFromDbRow(kind, status, info),
      unrepeatable,
      code.map(UsedCreditCode.applyFromDbRow(_, singleUse, usedBy.get))
    )
  }

  def unapplyToDbRow(creditReward: CreditReward) = {
    Reward.unapplyToDbRow(creditReward.reward).map {
      case (kind, status, info) =>
        val (code, singleUse, usedBy) = creditReward.code.flatMap(UsedCreditCode.unapplyToDbRow).map {
          case (actualCode, su, userId) => (Some(actualCode), su, Some(userId))
        }.getOrElse(None, None, None)
        (
          creditReward.id,
          creditReward.createdAt,
          creditReward.updatedAt,
          creditReward.state,
          creditReward.accountId,
          creditReward.credit,
          creditReward.applied,
          kind,
          status,
          info,
          creditReward.unrepeatable,
          code,
          singleUse,
          usedBy
        )
    }
  }
}
